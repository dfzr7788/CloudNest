package com.cloudnest.common;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Db {
  public final JdbcTemplate sql;
  private final TransactionTemplate tx;

  public Db(JdbcTemplate sql, org.springframework.transaction.PlatformTransactionManager manager) {
    this.sql = sql;
    this.tx = new TransactionTemplate(manager);
  }

  public Map<String, Object> one(String query, Object... args) {
    var rows = sql.queryForList(query, args);
    if (rows.isEmpty()) throw Problem.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> list(String query, Object... args) {
    return sql.queryForList(query, args);
  }

  public int update(String query, Object... args) {
    return sql.update(query, args);
  }

  public long count(String query, Object... args) {
    Long v = sql.queryForObject(query, Long.class, args);
    return v == null ? 0 : v;
  }

  public <T> T atomic(String uid, Supplier<T> fn) {
    return tx.execute(
        status -> {
          one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", uid);
          return fn.get();
        });
  }

  public <T> T transaction(Supplier<T> fn) {
    return tx.execute(status -> fn.get());
  }

  public static String s(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? null : v.toString();
  }

  public static long n(Map<String, Object> m, String key) {
    return ((Number) m.get(key)).longValue();
  }

  public static boolean b(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Boolean x ? x : ((Number) v).intValue() != 0;
  }
}
