package com.cloudnest.ai;

import com.cloudnest.common.Problem;
import java.net.*;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Public HTTPS only. A new pool and pinned DNS snapshot are used for each request. */
@Component
public class AiOutboundPolicy {
  public static Problem blocked() {
    return new Problem(400, "AI_ENDPOINT_BLOCKED", "仅允许 HTTPS 公网地址和 443 端口；不支持本地或内网端点");
  }

  public String normalize(String raw) {
    try {
      if (raw == null
          || raw.length() > 2048
          || raw.chars().anyMatch(c -> c <= 32 || c == 127)
          || raw.contains("\\")) throw blocked();
      var uri = new URI(raw);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getHost() == null
          || (uri.getPort() != -1 && uri.getPort() != 443)) throw blocked();
      var url = HttpUrl.get(raw);
      String path = url.encodedPath().replaceAll("/+$", "");
      if (path.toLowerCase(Locale.ROOT).endsWith("/chat/completions")
          || path.toLowerCase(Locale.ROOT).endsWith("/embeddings")
          || path.contains("%")
          || path.contains("//")) throw blocked();
      return url.newBuilder()
          .encodedPath(path.isEmpty() ? "/" : path)
          .build()
          .toString()
          .replaceAll("/+$", "");
    } catch (Exception e) {
      throw blocked();
    }
  }

  public static boolean publicAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) return false;
    byte[] a = address.getAddress();
    if (a.length == 4) {
      int x = a[0] & 255, y = a[1] & 255, z = a[2] & 255;
      return !(x == 0
          || x == 10
          || x == 127
          || x >= 224
          || (x == 100 && y >= 64 && y <= 127)
          || (x == 169 && y == 254)
          || (x == 172 && y >= 16 && y <= 31)
          || (x == 192 && (y == 0 || y == 168 || (y == 88 && z == 99) || (y == 2)))
          || (x == 198 && (y == 18 || y == 19 || (y == 51 && z == 100)))
          || (x == 203 && y == 0 && z == 113));
    }
    // Only globally routed unicast; exclude special/documentation/transition ranges.
    int x = a[0] & 255, y = a[1] & 255;
    if ((x & 0xe0) != 0x20) return false;
    if (x == 0x20 && y == 0x02) return false; // 6to4
    if (x == 0x20
        && y == 0x01
        && ((a[2] & 255) < 2 || ((a[2] & 255) == 0x0d && (a[3] & 255) == 0xb8))) return false;
    return !(x == 0x3f && y == 0xff); // documentation
  }

  public List<InetAddress> validate(List<InetAddress> addresses) {
    if (addresses.isEmpty() || addresses.stream().anyMatch(a -> !publicAddress(a))) throw blocked();
    return List.copyOf(addresses);
  }

  public OkHttpClient client(String base) throws UnknownHostException {
    String host = HttpUrl.get(normalize(base)).host();
    var addresses = validate(resolve(host));
    return new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(
            name -> {
              if (!host.equals(name)) throw new UnknownHostException("AI destination changed");
              return addresses;
            })
        .connectionPool(new ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(120))
        .callTimeout(java.time.Duration.ofSeconds(120))
        .build();
  }

  protected List<InetAddress> resolve(String host) throws UnknownHostException {
    return Dns.SYSTEM.lookup(host);
  }
}
