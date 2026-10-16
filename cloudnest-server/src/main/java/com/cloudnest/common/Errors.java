package com.cloudnest.common;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  private final Db db;
  private final com.cloudnest.security.RateLimits limits;

  public Errors(Db db, com.cloudnest.security.RateLimits limits) {
    this.db = db;
    this.limits = limits;
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e) {
    return problem(Problem.missing());
  }

  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem p) {
    if (p.status == 403 || p.status == 404) {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (authentication != null
          && authentication.isAuthenticated()
          && !authentication.getName().equals("anonymousUser"))
        try {
          limits.check("denial-audit:" + authentication.getName(), 60);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,created_at) VALUES(?,?,?,?)",
              Util.id(),
              authentication.getName(),
              "ACCESS_DENIED",
              System.currentTimeMillis());
        } catch (Exception ignored) {
          /* Auditing must not change the original authorization response. */
        }
    }
    return ResponseEntity.status(p.status).body(Map.of("code", p.code, "message", p.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return problem(
        Problem.invalid(
            e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("参数错误")));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate(DataIntegrityViolationException e) {
    return problem(Problem.conflict("名称或账号已存在，或者资源仍被引用"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large(Exception e) {
    return problem(new Problem(413, "TOO_LARGE", "请求超过上传大小限制"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> input(Exception e) {
    return problem(Problem.invalid("请求参数格式错误"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    LoggerFactory.getLogger(Errors.class).error("Request failed", e);
    return problem(new Problem(500, "INTERNAL_ERROR", "操作失败，请重试；如果持续发生请检查服务日志"));
  }
}
