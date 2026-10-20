package com.cloudnest.security;

import com.cloudnest.common.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecretKeySpec jwtKey(@Value("${cloudnest.data-dir}") String dir) throws IOException {
    Path path = Path.of(dir).toAbsolutePath().resolve("jwt.key");
    Files.createDirectories(path.getParent());
    if (!Files.exists(path)) {
      try {
        Files.writeString(path, Util.token(), StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException ignored) {
      }
    }
    byte[] bytes = Files.readString(path).strip().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalStateException("jwt.key must contain at least 32 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder encoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder decoder(SecretKeySpec key) {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  SecurityFilterChain chain(
      HttpSecurity http,
      JwtDecoder decoder,
      DavAuthentication dav,
      com.cloudnest.sync.SyncDevices sync,
      Db db,
      @Value("${cloudnest.secure-cookies}") boolean secure)
      throws Exception {
    var repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repo.setCookieCustomizer(c -> c.path("/").sameSite("Lax").secure(secure));
    return http.sessionManagement(
            s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                    .sessionAuthenticationStrategy(
                        new org.springframework.security.web.authentication.session
                            .NullAuthenticatedSessionStrategy()))
        .csrf(
            c ->
                c.csrfTokenRepository(repo)
                    .ignoringRequestMatchers("/dav", "/dav/**", "/api/public/office/callback", "/api/public/nodes/*/heartbeat")
                    .sessionAuthenticationStrategy(
                        new org.springframework.security.web.authentication.session
                            .NullAuthenticatedSessionStrategy())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/public/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> json(res, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, ex) -> json(res, 403, "FORBIDDEN", "请求未获授权或安全令牌已过期，请刷新后重试")))
        .headers(
            h ->
                h.frameOptions(f -> f.sameOrigin())
                    .contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'none'; frame-ancestors 'self'; sandbox"
                                    + " allow-same-origin")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                if (req.getRequestURI().equals("/dav") || req.getRequestURI().startsWith("/dav/")) {
                  SecurityContextHolder.clearContext();
                  org.springframework.security.core.Authentication auth;
                  try {
                    auth = dav.authenticate(req);
                  } catch (Problem p) {
                    json(res, p.status, p.code, p.getMessage());
                    return;
                  }
                  if (auth == null) {
                    res.setHeader(
                        "WWW-Authenticate", "Basic realm=\"CloudNest WebDAV\", charset=\"UTF-8\"");
                    json(res, 401, "DAV_AUTH_REQUIRED", "请使用独立 WebDAV 应用密码");
                    return;
                  }
                  SecurityContextHolder.getContext().setAuthentication(auth);
                  chain.doFilter(req, res);
                  return;
                }
                String authorization = req.getHeader("Authorization");
                if (authorization != null
                    && authorization.regionMatches(
                        true, 0, "SyncDevice ", 0, "SyncDevice ".length())) {
                  SecurityContextHolder.clearContext();
                  try {
                    SecurityContextHolder.getContext().setAuthentication(sync.authenticate(req));
                  } catch (Problem p) {
                    json(res, p.status, p.code, p.getMessage());
                    return;
                  }
                  chain.doFilter(req, res);
                  return;
                }
                String cookie =
                    Arrays.stream(Optional.ofNullable(req.getCookies()).orElse(new Cookie[0]))
                        .filter(c -> c.getName().equals("CN_AUTH"))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
                if (cookie != null)
                  try {
                    Jwt jwt = decoder.decode(cookie);
                    String uid = jwt.getSubject(), sid = jwt.getClaimAsString("sid");
                    if (uid != null
                        && sid != null
                        && db.count(
                                "SELECT COUNT(*) FROM auth_session s JOIN sys_user u ON"
                                    + " u.id=s.user_id WHERE s.id=? AND s.user_id=? AND"
                                    + " u.enabled=TRUE AND s.expires_at>?",
                                sid,
                                uid,
                                Instant.now().toEpochMilli())
                            == 1) {
                      var auth = new UsernamePasswordAuthenticationToken(uid, sid, List.of());
                      long now = System.currentTimeMillis();
                      db.update(
                          "UPDATE auth_session SET last_seen=? WHERE id=? AND last_seen<?",
                          now,
                          sid,
                          now - 60000);
                      SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                  } catch (JwtException | IllegalArgumentException ignored) {
                  }
                chain.doFilter(req, res);
              }
            },
            AnonymousAuthenticationFilter.class)
        .build();
  }

  static void json(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
  }

  @Bean
  org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer
      davMethods() {
    var firewall = new org.springframework.security.web.firewall.StrictHttpFirewall();
    firewall.setAllowedHttpMethods(
        List.of(
            "GET",
            "HEAD",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS",
            "PROPFIND",
            "PROPPATCH",
            "MKCOL",
            "MOVE",
            "COPY",
            "LOCK",
            "UNLOCK"));
    return web -> web.httpFirewall(firewall);
  }
}
