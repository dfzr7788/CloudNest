package com.cloudnest.security;

import com.cloudnest.common.Problem;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final Map<String, Window> local = new ConcurrentHashMap<>();

  private record Window(long expires, int count) {}

  public RateLimits(
      StringRedisTemplate redis, @Value("${cloudnest.redis-enabled}") boolean enabled) {
    this.redis = redis;
    this.enabled = enabled;
  }

  public void check(String key, int limit) {
    long count;
    if (enabled) {
      try {
        Long result =
            redis.execute(
                new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                        + " redis.call('EXPIRE',KEYS[1],60) end; return n",
                    Long.class),
                List.of("cn:rate:" + key));
        count = result == null ? limit + 1 : result;
      } catch (Exception e) {
        throw new Problem(503, "RATE_LIMIT_UNAVAILABLE", "限流服务暂时不可用，请稍后重试");
      }
    } else {
      long now = System.currentTimeMillis();
      if (local.size() > 10000) local.entrySet().removeIf(e -> e.getValue().expires < now);
      if (local.size() > 20000 && !local.containsKey(key))
        throw new Problem(429, "RATE_LIMITED", "请求过于频繁，请稍后重试");
      count =
          local.compute(
                  key,
                  (k, w) ->
                      w == null || w.expires < now
                          ? new Window(now + 60000, 1)
                          : new Window(w.expires, w.count + 1))
              .count;
    }
    if (count > limit) throw new Problem(429, "RATE_LIMITED", "请求过于频繁，请稍后重试");
  }
}
