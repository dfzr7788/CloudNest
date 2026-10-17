package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.upload.UploadIngest;
import com.cloudnest.user.RuntimeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@Controller
public class OfficeController {
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
  public void cleanup() {
    db.update("DELETE FROM office_session WHERE expires_at<?", System.currentTimeMillis());
  }

  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final RuntimeSettings settings;
  private final UploadIngest ingest;
  private final JwtEncoder signer;
  private final JwtDecoder decoder;
  private final ObjectMapper json;

  public OfficeController(
      Db db,
      DriveService drive,
      ContentService content,
      RuntimeSettings settings,
      UploadIngest ingest,
      JwtEncoder signer,
      JwtDecoder decoder,
      ObjectMapper json) {
    this.db = db;
    this.drive = drive;
    this.content = content;
    this.settings = settings;
    this.ingest = ingest;
    this.signer = signer;
    this.decoder = decoder;
    this.json = json;
  }

  private Map<String, String> config() {
    var config = settings.get("office");
    if (config.getOrDefault("url", "").isBlank() || config.getOrDefault("secret", "").length() < 32)
      throw new Problem(503, "OFFICE_DISABLED", "管理员尚未配置 OnlyOffice 文档服务");
    RuntimeSettings.httpUrl(config.get("url"));
    RuntimeSettings.httpUrl(config.getOrDefault("baseUrl", ""));
    return config;
  }

  private String ticket(String session, boolean edit) {
    return signer
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .subject(session)
                    .claim("purpose", "office")
                    .claim("edit", edit)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(12 * 3600))
                    .build()))
        .getTokenValue();
  }

  private Map<String, Object> session(String ticket) {
    try {
      var claims = decoder.decode(ticket);
      if (!"office".equals(claims.getClaimAsString("purpose"))) throw Problem.missing();
      var s =
          db.one(
              "SELECT o.* FROM office_session o JOIN sys_user u ON u.id=o.user_id WHERE o.id=? AND"
                  + " o.expires_at>? AND u.enabled=TRUE",
              claims.getSubject(),
              System.currentTimeMillis());
      var entry = drive.file(s(s, "user_id"), s(s, "file_id"));
      if (n(entry, "revision") != n(s, "current_revision"))
        throw Problem.conflict("文件已经被其他操作修改，请重新打开 Office");
      s.put("edit", Boolean.TRUE.equals(claims.getClaim("edit")));
      return s;
    } catch (org.springframework.security.oauth2.jwt.JwtException | IllegalArgumentException e) {
      throw new Problem(401, "OFFICE_TICKET_INVALID", "文档授权已失效，请重新打开");
    }
  }

  @GetMapping("/api/office/status")
  @ResponseBody
  public Map<String, Object> status(Authentication a) {
    var cfg = settings.get("office");
    return Map.of(
        "enabled",
        !cfg.getOrDefault("url", "").isBlank() && cfg.getOrDefault("secret", "").length() >= 32);
  }

  @GetMapping("/api/office/{id}/editor")
  public void editor(
      Authentication a,
      @PathVariable String id,
      @RequestParam(defaultValue = "view") String mode,
      HttpServletResponse res)
      throws Exception {
    var e = drive.file(a.getName(), id);
    var cfg = config();
    String name = s(e, "name"),
        extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx").contains(extension))
      throw Problem.invalid("该文件类型不能交给 Office 服务");
    if (!Set.of("view", "edit").contains(mode)) throw Problem.invalid("编辑模式无效");
    boolean edit = mode.equals("edit");
    String sid =
        db.atomic(
            a.getName(),
            () -> {
              var existing =
                  db.list(
                      "SELECT id FROM office_session WHERE user_id=? AND file_id=? AND"
                          + " current_revision=? AND expires_at>? ORDER BY created_at DESC LIMIT 1",
                      a.getName(),
                      id,
                      e.get("revision"),
                      System.currentTimeMillis());
              if (!existing.isEmpty()) return s(existing.getFirst(), "id");
              String session = Util.id();
              long now = System.currentTimeMillis();
              db.update(
                  "INSERT INTO"
                      + " office_session(id,user_id,file_id,current_revision,expires_at,created_at)"
                      + " VALUES(?,?,?,?,?,?)",
                  session,
                  a.getName(),
                  id,
                  e.get("revision"),
                  now + 12 * 3600000L,
                  now);
              return session;
            });
    String token = ticket(sid, edit), base = cfg.get("baseUrl").replaceAll("/+$", "");
    String type =
        extension.startsWith("xls") ? "cell" : extension.startsWith("ppt") ? "slide" : "word";
    var config = new LinkedHashMap<String, Object>();
    config.put("documentType", type);
    config.put(
        "document",
        Map.of(
            "fileType",
            extension,
            "key",
            sid,
            "title",
            name,
            "url",
            base + "/api/public/office/file?ticket=" + token,
            "permissions",
            Map.of("edit", edit, "download", true, "print", true)));
    config.put(
        "editorConfig",
        Map.of(
            "mode",
            mode,
            "lang",
            "zh-CN",
            "callbackUrl",
            base + "/api/public/office/callback?ticket=" + token,
            "user",
            Map.of(
                "id",
                a.getName(),
                "name",
                s(db.one("SELECT nickname FROM sys_user WHERE id=?", a.getName()), "nickname"))));
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), JWTClaimsSet.parse(config));
    signed.sign(new MACSigner(cfg.get("secret").getBytes(StandardCharsets.UTF_8)));
    config.put("token", signed.serialize());
    URI service = RuntimeSettings.httpUrl(cfg.get("url"));
    String origin = service.getScheme() + "://" + service.getAuthority(),
        nonce = Util.token(),
        sdk = cfg.get("url").replaceAll("/+$", "") + "/web-apps/apps/api/documents/api.js";
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'nonce-"
            + nonce
            + "' "
            + origin
            + "; frame-src "
            + origin
            + "; style-src 'unsafe-inline'; img-src data:; connect-src "
            + origin
            + "; base-uri 'none'; frame-ancestors 'self'");
    res.setContentType("text/html;charset=UTF-8");
    String encoded =
        json.writeValueAsString(config)
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026");
    res.getWriter()
        .write(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>CloudNest"
                + " Office</title><style>html,body,#office{height:100%;margin:0}</style><div"
                + " id=\"office\"></div><script nonce=\""
                + nonce
                + "\" src=\""
                + HtmlUtils.htmlEscape(sdk)
                + "\"></script><script nonce=\""
                + nonce
                + "\">new DocsAPI.DocEditor('office',"
                + encoded
                + ");</script></html>");
  }

  @GetMapping("/api/public/office/file")
  public void file(@RequestParam String ticket, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    var session = session(ticket);
    content.send(s(session, "user_id"), s(session, "file_id"), false, req, res);
  }

  @PostMapping("/api/public/office/callback")
  @ResponseBody
  public Map<String, Object> callback(
      @RequestParam String ticket, @RequestBody Map<String, Object> body, HttpServletRequest req)
      throws Exception {
    var cfg = config();
    String bearer = Optional.ofNullable(req.getHeader("Authorization")).orElse("");
    String token =
        bearer.startsWith("Bearer ")
            ? bearer.substring(7)
            : Objects.toString(body.get("token"), "");
    SignedJWT signed;
    try {
      signed = SignedJWT.parse(token);
      if (!signed.getHeader().getAlgorithm().equals(JWSAlgorithm.HS256)
          || !signed.verify(new MACVerifier(cfg.get("secret").getBytes(StandardCharsets.UTF_8))))
        throw new IllegalArgumentException();
      if (signed.getJWTClaimsSet().getExpirationTime() != null
          && signed.getJWTClaimsSet().getExpirationTime().before(new Date()))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new Problem(401, "OFFICE_SIGNATURE_INVALID", "Office 回调签名无效");
    }
    var claims = signed.getJWTClaimsSet().getClaims();
    Map<?, ?> payload = claims.get("payload") instanceof Map<?, ?> p ? p : claims;
    for (String field : List.of("status", "url"))
      if (body.containsKey(field)
          && !Objects.toString(body.get(field)).equals(Objects.toString(payload.get(field))))
        throw new Problem(401, "OFFICE_PAYLOAD_INVALID", "Office 回调内容与签名不符");
    if (!(body.getOrDefault("status", 0) instanceof Number)) throw Problem.invalid("Office 状态无效");
    int state = ((Number) body.getOrDefault("status", 0)).intValue();
    if (state != 2 && state != 6) return Map.of("error", 0);
    var session = session(ticket);
    if (!Boolean.TRUE.equals(session.get("edit")))
      throw new Problem(403, "OFFICE_READ_ONLY", "文档仅允许查看");
    URI download;
    try {
      download = URI.create(Objects.toString(body.get("url"), ""));
    } catch (Exception ex) {
      throw Problem.invalid("Office 下载地址无效");
    }
    var service = RuntimeSettings.httpUrl(cfg.get("url"));
    if (!Objects.equals(download.getScheme(), service.getScheme())
        || !Objects.equals(download.getAuthority(), service.getAuthority())
        || !download.getPath().startsWith(service.getPath().replaceAll("/+$", "") + "/cache/")
        || download.getUserInfo() != null)
      throw new Problem(403, "OFFICE_SOURCE_INVALID", "Office 回调文件来源不可信");
    var client =
        new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(20))
            .build();
    try (var response =
        client.newCall(new okhttp3.Request.Builder().url(download.toString()).build()).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw new IOException("Office document download failed");
      InputStream input = response.body().byteStream();
      db.atomic(
          s(session, "user_id"),
          () -> {
            var fresh = session(ticket);
            var entry = drive.file(s(fresh, "user_id"), s(fresh, "file_id"));
            try {
              ingest.receive(
                  s(fresh, "user_id"),
                  s(entry, "parent_id"),
                  s(entry, "name"),
                  input,
                  10 * 1024 * 1024L,
                  s(entry, "id"),
                  n(entry, "revision"));
            } catch (IOException ex) {
              throw new UncheckedIOException(ex);
            }
            db.update(
                "UPDATE office_session SET current_revision=current_revision+1 WHERE id=?",
                fresh.get("id"));
            return null;
          });
    }
    return Map.of("error", 0);
  }
}
