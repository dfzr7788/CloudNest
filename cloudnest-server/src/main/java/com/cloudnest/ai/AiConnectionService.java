package com.cloudnest.ai;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiConnectionService {
  public final Db db;
  private final AiSecrets secrets;
  private final AiOutboundPolicy policy;
  private final com.cloudnest.user.RuntimeSettings settings;

  @Value("${cloudnest.ai.enabled:false}")
  private boolean enabled;

  public AiConnectionService(
      Db db,
      AiSecrets secrets,
      AiOutboundPolicy policy,
      com.cloudnest.user.RuntimeSettings settings) {
    this.db = db;
    this.secrets = secrets;
    this.policy = policy;
    this.settings = settings;
  }

  public boolean enabled() {
    return Boolean.parseBoolean(
        settings.get("ai").getOrDefault("enabled", Boolean.toString(enabled)));
  }

  public void setEnabled(String uid, boolean value) {
    settings.admin(uid);
    settings.set("ai", Map.of("enabled", Boolean.toString(value)));
  }

  public void requireEnabled() {
    if (!enabled()) throw new Problem(403, "AI_DISABLED", "站点尚未开启 AI，请联系管理员");
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String text(Map<String, Object> input, String field, int max) {
    Object value = input.get(field);
    if (!(value instanceof String str)
        || str.isBlank()
        || str.length() > max
        || str.chars().anyMatch(Character::isISOControl)) throw Problem.invalid(field + " 无效");
    return str.strip();
  }

  private static int number(Map<String, Object> input, String field, int min, int max) {
    Object value = input.get(field);
    if (!(value instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < min
        || n.intValue() > max) throw Problem.invalid(field + " 超出范围");
    return n.intValue();
  }

  private static boolean flag(Map<String, Object> input) {
    if (!(input.get("enabled") instanceof Boolean b)) throw Problem.invalid("enabled 必须为布尔值");
    return b;
  }

  private void activeUser(String uid) {
    db.one("SELECT id FROM sys_user WHERE id=? AND enabled=TRUE", uid);
  }

  public Map<String, Object> connection(String uid, String id) {
    return db.one("SELECT * FROM ai_connection WHERE user_id=? AND id=?", uid, id);
  }

  public Map<String, Object> model(String uid, String id) {
    return db.one("SELECT * FROM ai_model WHERE user_id=? AND id=?", uid, id);
  }

  public Map<String, Object> connectionView(Map<String, Object> c) {
    var view = new LinkedHashMap<>(c);
    view.remove("secret_cipher");
    view.put("keyConfigured", c.get("secret_cipher") != null);
    view.put("destinationFingerprint", hash(s(c, "base_url")));
    view.put("protocol", "OPENAI_COMPATIBLE_CHAT");
    view.put(
        "consented",
        db.count(
                "SELECT COUNT(*) FROM ai_consent WHERE user_id=? AND connection_id=? AND"
                    + " destination_fingerprint=? AND notice_version=1",
                c.get("user_id"),
                c.get("id"),
                hash(s(c, "base_url")))
            > 0);
    return view;
  }

  public List<Map<String, Object>> connections(String uid) {
    return db.list("SELECT * FROM ai_connection WHERE user_id=? ORDER BY created_at", uid).stream()
        .map(this::connectionView)
        .toList();
  }

  public Map<String, Object> saveConnection(String uid, String id, Map<String, Object> input) {
    requireEnabled();
    return db.atomic(
        uid,
        () -> {
          activeUser(uid);
          var previous = id == null ? null : connection(uid, id);
          if (previous == null
              && db.count("SELECT COUNT(*) FROM ai_connection WHERE user_id=?", uid) >= 5)
            throw Problem.invalid("最多配置 5 个连接");
          if (previous != null) version(previous, input);
          var merged = new HashMap<String, Object>();
          if (previous != null) {
            merged.put("name", previous.get("name"));
            merged.put("baseUrl", previous.get("base_url"));
            merged.put("enabled", b(previous, "enabled"));
          }
          merged.putAll(input);
          String name = text(merged, "name", 60),
              base = policy.normalize(text(merged, "baseUrl", 2048));
          if (previous != null
              && !base.equals(s(previous, "base_url"))
              && !input.containsKey("apiKey")) throw Problem.invalid("更换目的地必须重新输入 API Key");
          String secret = previous == null ? null : s(previous, "secret_cipher");
          if (input.containsKey("apiKey")) secret = secrets.encrypt(text(input, "apiKey", 4096));
          if (secret == null && flag(merged)) throw Problem.invalid("请先设置 API Key");
          if (previous == null) {
            String created = UUID.randomUUID().toString();
            db.update(
                "INSERT INTO"
                    + " ai_connection(id,user_id,name,base_url,secret_cipher,enabled,config_version,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,1,?,?)",
                created,
                uid,
                name,
                base,
                secret,
                flag(merged),
                System.currentTimeMillis(),
                System.currentTimeMillis());
            return connectionView(connection(uid, created));
          }
          invalidate(uid, id, null);
          db.update(
              "UPDATE ai_connection SET"
                  + " name=?,base_url=?,secret_cipher=?,enabled=?,config_version=config_version+1,last_test_status='UNTESTED',updated_at=?"
                  + " WHERE id=? AND user_id=?",
              name,
              base,
              secret,
              flag(merged),
              System.currentTimeMillis(),
              id,
              uid);
          if (!flag(merged))
            db.update(
                "UPDATE ai_preference SET default_chat_model_id=NULL WHERE user_id=? AND"
                    + " default_chat_model_id IN (SELECT id FROM ai_model WHERE connection_id=?)",
                uid,
                id);
          return connectionView(connection(uid, id));
        });
  }

  public static void version(Map<String, Object> previous, Map<String, Object> input) {
    if (!(input.get("configVersion") instanceof Number n)
        || n.longValue() != n(previous, "config_version")) throw Problem.conflict("配置已变化，请刷新后编辑");
  }

  public void revoke(String uid, String id, boolean delete) {
    db.atomic(
        uid,
        () -> {
          connection(uid, id);
          invalidate(uid, id, null);
          db.update(
              "UPDATE ai_connection SET"
                  + " secret_cipher=NULL,enabled=FALSE,config_version=config_version+1,last_test_status='UNTESTED'"
                  + " WHERE id=? AND user_id=?",
              id,
              uid);
          db.update(
              "UPDATE ai_preference SET default_chat_model_id=NULL WHERE user_id=? AND"
                  + " default_chat_model_id IN (SELECT id FROM ai_model WHERE connection_id=?)",
              uid,
              id);
          if (delete) {
            db.update("DELETE FROM ai_consent WHERE connection_id=? AND user_id=?", id, uid);
            db.update("DELETE FROM ai_model WHERE connection_id=? AND user_id=?", id, uid);
            db.update("DELETE FROM ai_connection WHERE id=? AND user_id=?", id, uid);
          }
          return null;
        });
  }

  public List<Map<String, Object>> models(String uid) {
    return db.list("SELECT * FROM ai_model WHERE user_id=? ORDER BY name,id", uid);
  }

  public Map<String, Object> saveModel(String uid, String id, Map<String, Object> input) {
    requireEnabled();
    return db.atomic(
        uid,
        () -> {
          var previous = id == null ? null : model(uid, id);
          var merged = new HashMap<String, Object>();
          if (previous != null) {
            version(previous, input);
            merged.put("purpose", previous.get("purpose"));
            merged.put("embeddingDimensions", previous.get("embedding_dimensions"));
            merged.put("maxBatchSize", previous.get("max_batch_size"));
            merged.put("requestDimensions", b(previous, "request_dimensions"));
            merged.put("connectionId", previous.get("connection_id"));
            merged.put("name", previous.get("name"));
            merged.put("modelId", previous.get("model_id"));
            merged.put("contextLimit", previous.get("context_limit"));
            merged.put("maxOutput", previous.get("max_output"));
            merged.put("outputParamMode", previous.get("output_param_mode"));
            merged.put("enabled", b(previous, "enabled"));
          }
          merged.putAll(input);
          String cid = text(merged, "connectionId", 36);
          connection(uid, cid);
          if (id == null
              && db.count(
                      "SELECT COUNT(*) FROM ai_model WHERE user_id=? AND connection_id=?", uid, cid)
                  >= 10) throw Problem.invalid("每连接最多配置 10 个模型");
          if (previous != null && !cid.equals(s(previous, "connection_id")))
            throw Problem.invalid("请在新连接下创建模型");
          String purpose = Objects.toString(merged.getOrDefault("purpose", "CHAT"));
          if (!Set.of("CHAT", "EMBEDDING").contains(purpose))
            throw Problem.invalid("模型用途只支持 CHAT 或 EMBEDDING");
          if (previous != null && !purpose.equals(s(previous, "purpose")))
            throw Problem.invalid("模型用途不可更改，请新建模型");
          boolean embedding = purpose.equals("EMBEDDING");
          String name = text(merged, "name", 60), model = text(merged, "modelId", 200);
          String mode = embedding ? "NONE" : text(merged, "outputParamMode", 30);
          if (!embedding && !Set.of("max_tokens", "max_completion_tokens").contains(mode))
            throw Problem.invalid("输出参数不支持");
          int context =
              number(merged, "contextLimit", embedding ? 512 : 1024, embedding ? 32768 : 2000000);
          int output = embedding ? 0 : number(merged, "maxOutput", 64, 2048);
          if (!embedding && context < output + 512) throw Problem.invalid("上下文窗口需要为输入保留空间");
          Integer dimensions = embedding ? number(merged, "embeddingDimensions", 1, 4096) : null;
          Integer batch = embedding ? number(merged, "maxBatchSize", 1, 32) : null;
          Object requested = merged.getOrDefault("requestDimensions", false);
          if (!(requested instanceof Boolean)) throw Problem.invalid("requestDimensions 必须为布尔值");
          boolean requestDimensions = embedding && Boolean.TRUE.equals(requested);
          if (embedding && (input.containsKey("maxOutput") || input.containsKey("outputParamMode")))
            throw Problem.invalid("向量模型不使用聊天输出参数");
          if (!embedding
              && (input.containsKey("embeddingDimensions")
                  || input.containsKey("maxBatchSize")
                  || Boolean.TRUE.equals(input.get("requestDimensions"))))
            throw Problem.invalid("聊天模型不使用向量参数");
          if (id == null) {
            String created = UUID.randomUUID().toString();
            db.update(
                "INSERT INTO"
                    + " ai_model(id,user_id,connection_id,name,model_id,context_limit,max_output,output_param_mode,enabled,purpose,embedding_dimensions,max_batch_size,request_dimensions,config_version)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1)",
                created,
                uid,
                cid,
                name,
                model,
                context,
                output,
                mode,
                flag(merged),
                purpose,
                dimensions,
                batch,
                requestDimensions);
            return model(uid, created);
          }
          invalidate(uid, cid, id);
          db.update(
              "UPDATE ai_model SET"
                  + " name=?,model_id=?,context_limit=?,max_output=?,output_param_mode=?,enabled=?,embedding_dimensions=?,max_batch_size=?,request_dimensions=?,last_test_status='UNTESTED',last_test_at=NULL,config_version=config_version+1"
                  + " WHERE id=? AND user_id=?",
              name,
              model,
              context,
              output,
              mode,
              flag(merged),
              dimensions,
              batch,
              requestDimensions,
              id,
              uid);
          db.update("UPDATE ai_connection SET last_test_status='UNTESTED' WHERE id=?", cid);
          if (!flag(merged))
            db.update(
                "UPDATE ai_preference SET default_chat_model_id=NULL WHERE user_id=? AND"
                    + " default_chat_model_id=?",
                uid,
                id);
          return model(uid, id);
        });
  }

  public void deleteModel(String uid, String id) {
    db.atomic(
        uid,
        () -> {
          var m = model(uid, id);
          invalidate(uid, s(m, "connection_id"), id);
          db.update(
              "UPDATE ai_preference SET default_chat_model_id=NULL WHERE user_id=? AND"
                  + " default_chat_model_id=?",
              uid,
              id);
          db.update("DELETE FROM ai_model WHERE user_id=? AND id=?", uid, id);
          return null;
        });
  }

  public Map<String, Object> preference(String uid) {
    var rows = db.list("SELECT default_chat_model_id FROM ai_preference WHERE user_id=?", uid);
    return rows.isEmpty()
        ? Collections.singletonMap("default_chat_model_id", null)
        : rows.getFirst();
  }

  public void preference(String uid, String id) {
    db.atomic(
        uid,
        () -> {
          if (id != null) available(uid, id);
          db.update("DELETE FROM ai_preference WHERE user_id=?", uid);
          db.update(
              "INSERT INTO ai_preference(user_id,default_chat_model_id) VALUES(?,?)", uid, id);
          return null;
        });
  }

  public void consent(String uid, String cid, String fingerprint) {
    requireEnabled();
    db.atomic(
        uid,
        () -> {
          var c = connection(uid, cid);
          if (!hash(s(c, "base_url")).equals(fingerprint))
            throw Problem.conflict("目的地已变化，请重新查看数据发送说明");
          db.update("DELETE FROM ai_consent WHERE user_id=? AND connection_id=?", uid, cid);
          db.update(
              "INSERT INTO"
                  + " ai_consent(user_id,connection_id,destination_fingerprint,notice_version,accepted_at)"
                  + " VALUES(?,?,?,1,?)",
              uid,
              cid,
              fingerprint,
              System.currentTimeMillis());
          return null;
        });
  }

  public Map<String, Object> available(String uid, String mid) {
    return available(uid, mid, "CHAT");
  }

  public Map<String, Object> available(String uid, String mid, String purpose) {
    requireEnabled();
    activeUser(uid);
    var m = model(uid, mid);
    if (!purpose.equals(s(m, "purpose")))
      throw new Problem(
          400,
          "AI_MODEL_PURPOSE_MISMATCH",
          "模型用途不匹配，请选择" + (purpose.equals("CHAT") ? "聊天" : "向量") + "模型");
    var c = connection(uid, s(m, "connection_id"));
    if (!b(m, "enabled") || !b(c, "enabled") || c.get("secret_cipher") == null)
      throw new Problem(403, "AI_NOT_CONFIGURED", "模型或连接未启用，或未设置密钥");
    if (db.count(
            "SELECT COUNT(*) FROM ai_consent WHERE user_id=? AND connection_id=? AND"
                + " destination_fingerprint=? AND notice_version=1",
            uid,
            c.get("id"),
            hash(s(c, "base_url")))
        == 0) throw new Problem(403, "AI_CONSENT_REQUIRED", "请先确认数据发送对象和费用说明");
    return m;
  }

  private void invalidate(String uid, String cid, String mid) {
    db.update(
        "UPDATE ai_model SET last_test_status='UNTESTED',last_test_at=NULL WHERE user_id=? AND"
            + " connection_id=? AND (? IS NULL OR id=?)",
        uid,
        cid,
        mid,
        mid);
    // Mark intent immediately. Worker releases any active call and owns its terminal transition.
    db.update(
        "UPDATE ai_task SET cancel_requested=TRUE,updated_at=? WHERE user_id=? AND connection_id=?"
            + " AND (? IS NULL OR model_id=?) AND status IN ('QUEUED','RUNNING')",
        System.currentTimeMillis(),
        uid,
        cid,
        mid,
        mid);
  }
}
