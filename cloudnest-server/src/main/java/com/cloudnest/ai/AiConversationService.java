package com.cloudnest.ai;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Document-bound conversation data. TaskService remains the only paid-call executor. */
@Service
public class AiConversationService {
  private final Db db;
  private final DriveService drive;
  private final AiConnectionService configs;
  private final ObjectMapper json;
  static final String NO_EVIDENCE = "在本次检索的内容中未找到依据。可以换一种问法或查看原文。";

  public AiConversationService(
      Db db, DriveService drive, AiConnectionService configs, ObjectMapper json) {
    this.db = db;
    this.drive = drive;
    this.configs = configs;
    this.json = json;
  }

  public Map<String, Object> create(String uid, Map<String, Object> in) {
    if (!Set.of("fileId", "modelId", "expectedRevision").containsAll(in.keySet()))
      throw Problem.invalid("不支持的会话字段");
    return db.atomic(
        uid,
        () -> {
          configs.requireEnabled();
          String fileId = AiConnectionService.text(in, "fileId", 36),
              mid = AiConnectionService.text(in, "modelId", 36);
          var file = drive.file(uid, fileId);
          var model = configs.available(uid, mid);
          var connection = configs.connection(uid, s(model, "connection_id"));
          if (!(in.get("expectedRevision") instanceof Number r)
              || r.longValue() != n(file, "revision")) throw changed();
          if (db.count("SELECT COUNT(*) FROM ai_conversation WHERE user_id=?", uid) >= 200)
            throw new Problem(429, "AI_QUOTA_EXCEEDED", "最多保存 200 个会话，请删除旧会话");
          String id = UUID.randomUUID().toString();
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO"
                  + " ai_conversation(id,user_id,file_id,revision,model_id,connection_id,model_version,connection_version,model_name,destination,created_at,updated_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              uid,
              fileId,
              file.get("revision"),
              mid,
              connection.get("id"),
              model.get("config_version"),
              connection.get("config_version"),
              model.get("name"),
              connection.get("base_url"),
              now,
              now);
          return get(uid, id);
        });
  }

  private Problem changed() {
    return new Problem(409, "AI_REVISION_CHANGED", "文件或模型配置已变化，旧会话仅供阅读，请创建新会话");
  }

  public Map<String, Object> require(String uid, String id, boolean writable) {
    var c = db.one("SELECT * FROM ai_conversation WHERE id=? AND user_id=?", id, uid);
    if (n(c, "updated_at") < System.currentTimeMillis() - 30L * 86400000) throw Problem.missing();
    var f = drive.file(uid, s(c, "file_id"));
    boolean readonly = n(f, "revision") != n(c, "revision");
    try {
      var m = configs.available(uid, s(c, "model_id"));
      var connection = configs.connection(uid, s(c, "connection_id"));
      readonly =
          readonly
              || n(m, "config_version") != n(c, "model_version")
              || n(connection, "config_version") != n(c, "connection_version");
    } catch (Problem e) {
      readonly = true;
    }
    c.put("readOnly", readonly);
    c.put("currentRevision", f.get("revision"));
    if (writable && readonly) throw changed();
    return c;
  }

  public List<Map<String, Object>> list(String uid, String fileId, int page) {
    if (page < 1 || page > 100000) throw Problem.invalid("页码无效");
    if (fileId != null) drive.file(uid, fileId);
    var rows =
        fileId == null
            ? db.list(
                "SELECT id FROM ai_conversation WHERE user_id=? ORDER BY updated_at DESC,id LIMIT"
                    + " 20 OFFSET ?",
                uid,
                (page - 1) * 20)
            : db.list(
                "SELECT id FROM ai_conversation WHERE user_id=? AND file_id=? ORDER BY updated_at"
                    + " DESC,id LIMIT 20 OFFSET ?",
                uid,
                fileId,
                (page - 1) * 20);
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows)
      try {
        result.add(require(uid, s(row, "id"), false));
      } catch (Problem ignored) {
      }
    return result;
  }

  public Map<String, Object> get(String uid, String id) {
    return db.atomic(uid, () -> getLocked(uid, id));
  }

  private Map<String, Object> getLocked(String uid, String id) {
    var c = require(uid, id, false);
    // A bounded conversation never returns an unbounded transcript.
    var messages =
        db.list(
            "SELECT m.*,t.status,t.error_message,t.error_code,t.coverage_json FROM ai_message m"
                + " JOIN ai_task t ON t.id=m.task_id WHERE m.conversation_id=? ORDER BY"
                + " t.created_at,t.id,CASE WHEN m.role='user' THEN 0 ELSE 1 END",
            id);
    for (var m : messages) {
      m.put(
          "citations",
          db.list("SELECT id,source_id,locator FROM ai_citation WHERE message_id=?", m.get("id")));
      if (m.get("coverage_json") != null) {
        try {
          m.put("scope", json.readTree(s(m, "coverage_json")));
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      m.remove("coverage_json");
    }
    c.put("messages", messages);
    var active =
        db.list(
            "SELECT id FROM ai_task WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
            id);
    c.put("activeTaskId", active.isEmpty() ? null : active.getFirst().get("id"));
    return c;
  }

  public void canQueue(String uid, String id, String fileId, String mid) {
    var c = require(uid, id, true);
    if (!s(c, "file_id").equals(fileId) || !s(c, "model_id").equals(mid))
      throw Problem.invalid("会话绑定不匹配");
    if (db.count(
            "SELECT COUNT(*) FROM ai_task WHERE conversation_id=? AND status IN"
                + " ('QUEUED','RUNNING')",
            id)
        > 0) throw Problem.conflict("这个会话已有生成任务，请等待或停止");
    if (db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND role='user'", id)
        >= 100) throw new Problem(429, "AI_QUOTA_EXCEEDED", "每个会话最多 100 个问题，请创建新会话");
  }

  public void queued(String uid, String cid, String tid, String question) {
    long now = System.currentTimeMillis();
    db.update(
        "UPDATE ai_task SET conversation_id=?,expires_at=? WHERE id=?",
        cid,
        now + 30L * 86400000,
        tid);
    db.update("UPDATE ai_task SET expires_at=? WHERE conversation_id=?", now + 30L * 86400000, cid);
    db.update("UPDATE ai_conversation SET updated_at=? WHERE id=? AND user_id=?", now, cid, uid);
    db.update(
        "INSERT INTO ai_message(id,conversation_id,task_id,role,content,created_at)"
            + " VALUES(?,?,?,'user',?,?)",
        UUID.randomUUID().toString(),
        cid,
        tid,
        question,
        now);
  }

  public record Source(String sourceId, String locator, String text) {}

  public record Context(String text, List<Source> sources, Map<String, Object> coverage) {}

  public Context context(
      String uid,
      String cid,
      String question,
      DocumentExtractionService.Document doc,
      Map<String, Object> model) {
    require(uid, cid, true);
    int budget =
        Math.min(22000, ((int) n(model, "context_limit") - (int) n(model, "max_output") - 4096) / 3)
            - question.length();
    if (budget < 200) throw new Problem(400, "AI_CONTEXT_TOO_LARGE", "问题和规则超过模型输入预算");
    var history =
        db.list(
            "SELECT u.content AS question,a.content AS answer FROM ai_message u JOIN ai_message a"
                + " ON a.task_id=u.task_id AND a.role='assistant' JOIN ai_task t ON t.id=u.task_id"
                + " WHERE u.conversation_id=? AND u.role='user' AND t.status='SUCCEEDED' ORDER BY"
                + " u.created_at DESC,u.id LIMIT 6",
            cid);
    var turns = new ArrayList<Map<String, Object>>();
    int used = 0;
    for (var h : history) {
      int size = encode(h).length();
      if (used + size > Math.min(2000, budget / 4)) break;
      turns.add(h);
      used += size;
    }
    Collections.reverse(turns);
    budget -= used + 200;
    var all = new ArrayList<Source>();
    for (var block : doc.blocks()) {
      // Preserve complete paragraphs/lines; an oversized single paragraph is omitted, never cut.
      var paragraphs = block.text().split("(?<=\\n)");
      StringBuilder part = new StringBuilder();
      int segment = 1;
      for (String paragraph : paragraphs) {
        if (part.length() + paragraph.length() > Math.min(2500, budget) && !part.isEmpty()) {
          all.add(
              new Source(
                  "S" + (all.size() + 1), block.locator() + " · 片段 " + segment++, part.toString()));
          part.setLength(0);
        }
        if (paragraph.length() <= Math.min(2500, budget)) part.append(paragraph);
      }
      if (!part.isEmpty())
        all.add(
            new Source(
                "S" + (all.size() + 1), block.locator() + " · 片段 " + segment, part.toString()));
    }
    int total = all.stream().mapToInt(x -> encode(x).length()).sum();
    boolean full =
        !doc.truncated()
            && total <= budget
            && all.stream().mapToInt(x -> x.text().length()).sum()
                == doc.blocks().stream().mapToInt(x -> x.text().length()).sum();
    var candidates = new ArrayList<>(all);
    String query = question + (turns.isEmpty() ? "" : " " + turns.getLast().get("question"));
    var terms = terms(query);
    if (!full)
      candidates.sort(
          Comparator.comparingInt((Source source) -> score(source.text(), terms)).reversed());
    var selected = new ArrayList<Source>();
    int chars = 0;
    for (var source : candidates) {
      int length = encode(source).length();
      if (!full && score(source.text(), terms) == 0) continue;
      if (chars + length > budget) continue;
      selected.add(source);
      chars += length;
    }
    var coverage = new LinkedHashMap<String, Object>();
    coverage.put("revision", doc.revision());
    coverage.put("partial", !full);
    coverage.put("selectedCharacters", selected.stream().mapToInt(x -> x.text().length()).sum());
    coverage.put("selectedBlocks", selected.size());
    coverage.put("extractedCharacters", doc.processedCharacters());
    coverage.put("reason", full ? "完整提取范围" : "仅本次关键词检索选中的完整片段；未覆盖全部文档");
    return new Context(
        encode(Map.of("question", question, "history", turns, "sources", selected)),
        selected,
        coverage);
  }

  private Set<String> terms(String question) {
    var terms = new HashSet<String>();
    var matcher =
        Pattern.compile("[a-z0-9_]{2,}|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE)
            .matcher(question.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      if (word.codePoints()
          .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
        for (int i = 0; i < word.length() - 1; i++) terms.add(word.substring(i, i + 2));
      } else terms.add(word);
    }
    return terms;
  }

  private int score(String text, Set<String> terms) {
    String lower = text.toLowerCase(Locale.ROOT);
    return (int) terms.stream().filter(lower::contains).count();
  }

  public Map<String, Object> answer(String raw, Context context) {
    try {
      var node = json.readTree(raw);
      if (!node.isObject()
          || !node.path("answer").isTextual()
          || node.path("answer").asText().isBlank()
          || node.path("answer").asText().length() > 20000
          || !node.path("citations").isArray()) throw new IllegalArgumentException();
      var allowed = new HashMap<String, Source>();
      context.sources().forEach(x -> allowed.put(x.sourceId(), x));
      var citations = new ArrayList<Source>();
      var seen = new HashSet<String>();
      boolean invalid = false;
      for (var item : node.path("citations")) {
        if (!item.isTextual() || !allowed.containsKey(item.asText())) {
          invalid = true;
          continue;
        }
        if (seen.add(item.asText())) citations.add(allowed.get(item.asText()));
      }
      // Never display an unsupported substantive answer with invented references.
      return Map.of(
          "text",
          citations.isEmpty()
              ? NO_EVIDENCE
              : node.path("answer").asText() + (invalid ? "\n（部分来源标识未通过校验，已移除；请核对原文。）" : ""),
          "sources",
          citations,
          "invalidCitations",
          invalid,
          "scope",
          context.coverage());
    } catch (Exception e) {
      throw new Problem(502, "AI_INVALID_ANSWER", "模型未返回有效问答 JSON；不会自动付费修复，请手动重试");
    }
  }

  public void publish(String uid, String cid, String tid, Map<String, Object> result) {
    require(uid, cid, true);
    String mid = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO ai_message(id,conversation_id,task_id,role,content,created_at)"
            + " VALUES(?,?,?,'assistant',?,?)",
        mid,
        cid,
        tid,
        result.get("text"),
        now);
    @SuppressWarnings("unchecked")
    var sources = (List<Source>) result.get("sources");
    for (var source : sources)
      db.update(
          "INSERT INTO ai_citation(id,message_id,source_id,locator,excerpt) VALUES(?,?,?,?,?)",
          UUID.randomUUID().toString(),
          mid,
          source.sourceId(),
          source.locator(),
          source.text());
    db.update("UPDATE ai_conversation SET updated_at=? WHERE id=?", now, cid);
    db.update("UPDATE ai_task SET expires_at=? WHERE conversation_id=?", now + 30L * 86400000, cid);
  }

  public Map<String, Object> citation(String uid, String cid, String id) {
    return db.atomic(uid, () -> citationLocked(uid, cid, id));
  }

  private Map<String, Object> citationLocked(String uid, String cid, String id) {
    var c = require(uid, cid, false);
    var row =
        db.one(
            "SELECT c.* FROM ai_citation c JOIN ai_message m ON m.id=c.message_id WHERE c.id=? AND"
                + " m.conversation_id=?",
            id,
            cid);
    row.put("fileId", c.get("file_id"));
    row.put("revision", c.get("revision"));
    row.put("readOnly", c.get("readOnly"));
    return row;
  }

  public void delete(String uid, String id) {
    db.atomic(
        uid,
        () -> {
          require(uid, id, false);
          erase(id);
          return null;
        });
  }

  private void erase(String id) {
    db.update(
        "UPDATE ai_task SET"
            + " cancel_requested=TRUE,status='CANCELLED',stage='FINISHED',request_json='{}',result_json=NULL,coverage_json=NULL,error_code='AI_ARTIFACT_DELETED',error_message=NULL"
            + " WHERE conversation_id=?",
        id);
    db.update(
        "DELETE FROM ai_citation WHERE message_id IN (SELECT id FROM ai_message WHERE"
            + " conversation_id=?)",
        id);
    db.update("DELETE FROM ai_message WHERE conversation_id=?", id);
    db.update("DELETE FROM ai_conversation WHERE id=?", id);
  }

  public void retention() {
    for (var c :
        db.list(
            "SELECT id,user_id FROM ai_conversation WHERE updated_at<? OR NOT EXISTS(SELECT 1 FROM"
                + " drive_entry e WHERE e.id=ai_conversation.file_id AND"
                + " e.user_id=ai_conversation.user_id)",
            System.currentTimeMillis() - 30L * 86400000)) {
      db.atomic(
          s(c, "user_id"),
          () -> {
            if (db.count(
                    "SELECT COUNT(*) FROM ai_conversation c WHERE id=? AND (updated_at<? OR NOT"
                        + " EXISTS(SELECT 1 FROM drive_entry e WHERE e.id=c.file_id AND"
                        + " e.user_id=c.user_id))",
                    c.get("id"),
                    System.currentTimeMillis() - 30L * 86400000)
                > 0) erase(s(c, "id"));
            return null;
          });
    }
  }

  private String encode(Object object) {
    try {
      return json.writeValueAsString(object);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
