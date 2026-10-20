package com.cloudnest.sync;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.upload.UploadService;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Reconciled metadata feed, serialized by the same user lock as normal drive mutations. */
@Service
public class SyncService {
  public static final long RETENTION = 30L * 86400000;
  private final Db db;
  private final DriveService drive;
  private final SyncDevices devices;
  private final UploadService uploads;
  private final ObjectMapper json;

  public SyncService(
      Db db, DriveService drive, SyncDevices devices, UploadService uploads, ObjectMapper json) {
    this.db = db;
    this.drive = drive;
    this.devices = devices;
    this.uploads = uploads;
    this.json = json;
  }

  private String encode(Object value) {
    return json.valueToTree(value).toString();
  }

  private JsonNode decode(String value) {
    try {
      return json.readTree(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> metadata(Map<String, Object> entry) {
    var value = new TreeMap<String, Object>();
    for (String key : List.of("id", "name", "kind", "size", "revision"))
      value.put(key, entry.get(key));
    value.put("parentId", entry.get("parent_id"));
    value.put("sha256", entry.get("sha256"));
    value.put("updatedAt", entry.get("updated_at"));
    value.put("changeToken", Util.hash(encode(value)));
    return value;
  }

  private Map<String, Object> current(String uid, String id) {
    drive.live(uid, id);
    return metadata(
        db.one(
            "SELECT e.*,b.sha256 FROM drive_entry e LEFT JOIN file_blob b ON b.id=e.blob_id AND"
                + " b.user_id=e.user_id WHERE e.user_id=? AND e.id=?",
            uid,
            id));
  }

  private Map<String, Object> reconcile(String uid) {
    if (db.count("SELECT COUNT(*) FROM sync_clock WHERE user_id=?", uid) == 0)
      db.update("INSERT INTO sync_clock(user_id,generation) VALUES(?,?)", uid, Util.id());
    var clock = db.one("SELECT * FROM sync_clock WHERE user_id=?", uid);
    long seq = n(clock, "seq"), now = System.currentTimeMillis();
    var all =
        db.list(
            "SELECT e.*,b.sha256 FROM drive_entry e LEFT JOIN file_blob b ON b.id=e.blob_id AND"
                + " b.user_id=e.user_id WHERE e.user_id=? AND e.deleted=FALSE ORDER BY e.id LIMIT"
                + " 10002",
            uid);
    if (all.size() > 10001)
      throw new Problem(
          422, "SYNC_LIBRARY_TOO_LARGE", "Development sync supports 10000 entries plus root");
    Map<String, List<Map<String, Object>>> children = new HashMap<>();
    for (var e : all) children.computeIfAbsent(s(e, "parent_id"), k -> new ArrayList<>()).add(e);
    var queue = new ArrayDeque<String>();
    queue.add(drive.root(uid));
    var live = new TreeMap<String, Map<String, Object>>();
    var seen = new HashSet<String>();
    while (!queue.isEmpty()) {
      String id = queue.remove();
      if (!seen.add(id)) throw Problem.conflict("Invalid directory graph");
      for (var e : children.getOrDefault(id, List.of())) {
        live.put(s(e, "id"), metadata(e));
        if ("FOLDER".equals(s(e, "kind"))) queue.add(s(e, "id"));
      }
    }
    var previous = new HashMap<String, Map<String, Object>>();
    for (var e : db.list("SELECT * FROM sync_entry WHERE user_id=?", uid))
      previous.put(s(e, "entry_id"), e);
    for (var e : live.entrySet()) {
      var prior = previous.remove(e.getKey());
      String token = s(e.getValue(), "changeToken"), payload = encode(e.getValue());
      if (prior != null && token.equals(s(prior, "change_token"))) continue;
      db.update("DELETE FROM sync_entry WHERE user_id=? AND entry_id=?", uid, e.getKey());
      db.update(
          "INSERT INTO sync_entry(user_id,entry_id,change_token,payload_json) VALUES(?,?,?,?)",
          uid,
          e.getKey(),
          token,
          payload);
      db.update(
          "INSERT INTO drive_change(user_id,seq,payload_json,created_at) VALUES(?,?,?,?)",
          uid,
          ++seq,
          encode(Map.of("operation", "UPSERT", "entry", e.getValue())),
          now);
    }
    for (String id : new TreeSet<>(previous.keySet())) {
      db.update("DELETE FROM sync_entry WHERE user_id=? AND entry_id=?", uid, id);
      db.update(
          "INSERT INTO drive_change(user_id,seq,payload_json,created_at) VALUES(?,?,?,?)",
          uid,
          ++seq,
          encode(Map.of("operation", "DELETE", "entryId", id, "tombstone", true)),
          now);
    }
    long floor =
        Math.max(
            n(clock, "floor_seq"),
            db.count(
                "SELECT COALESCE(MAX(seq),0) FROM drive_change WHERE user_id=? AND (created_at<? OR"
                    + " seq<=?)",
                uid,
                now - RETENTION,
                seq - 100000));
    db.update("DELETE FROM drive_change WHERE user_id=? AND seq<=?", uid, floor);
    db.update("UPDATE sync_clock SET seq=?,floor_seq=? WHERE user_id=?", seq, floor, uid);
    db.update(
        "DELETE FROM sync_operation WHERE device_id IN (SELECT id FROM sync_device WHERE user_id=?)"
            + " AND created_at<?",
        uid,
        now - RETENTION);
    db.update(
        "DELETE FROM sync_upload WHERE device_id IN (SELECT id FROM sync_device WHERE user_id=?)"
            + " AND created_at<?",
        uid,
        now - RETENTION);
    return db.one("SELECT * FROM sync_clock WHERE user_id=?", uid);
  }

  private String cursor(Map<String, Object> clock, long seq) {
    return s(clock, "generation") + ":" + seq;
  }

  public Map<String, Object> bootstrap(SyncDevices.Identity who) {
    return db.atomic(
        who.userId(),
        () -> {
          devices.check(who);
          var clock = reconcile(who.userId());
          var entries =
              db
                  .list(
                      "SELECT payload_json FROM sync_entry WHERE user_id=? ORDER BY entry_id",
                      who.userId())
                  .stream()
                  .map(e -> decode(s(e, "payload_json")))
                  .toList();
          return Map.of(
              "protocol",
              1,
              "semantics",
              "RECONCILED_FINAL_STATE",
              "rootId",
              drive.root(who.userId()),
              "entries",
              entries,
              "cursor",
              cursor(clock, n(clock, "seq")),
              "snapshotVersion",
              cursor(clock, n(clock, "seq")),
              "retentionDays",
              30);
        });
  }

  public Map<String, Object> changes(SyncDevices.Identity who, String cursor) {
    if (cursor == null || cursor.length() > 70) throw expired();
    return db.atomic(
        who.userId(),
        () -> {
          devices.check(who);
          var clock = reconcile(who.userId());
          String[] parts = cursor.split(":", -1);
          long seq;
          try {
            if (parts.length != 2 || !parts[0].equals(s(clock, "generation"))) throw expired();
            seq = Long.parseLong(parts[1]);
          } catch (NumberFormatException e) {
            throw expired();
          }
          if (seq < n(clock, "floor_seq") || seq > n(clock, "seq")) throw expired();
          var rows =
              db.list(
                  "SELECT * FROM drive_change WHERE user_id=? AND seq>? ORDER BY seq LIMIT 201",
                  who.userId(),
                  seq);
          boolean more = rows.size() > 200;
          if (more) rows = rows.subList(0, 200);
          var events =
              rows.stream()
                  .map(e -> Map.of("seq", n(e, "seq"), "change", decode(s(e, "payload_json"))))
                  .toList();
          long next = more ? n(rows.getLast(), "seq") : n(clock, "seq");
          return Map.of(
              "events",
              events,
              "cursor",
              cursor(clock, next),
              "hasMore",
              more,
              "snapshotVersion",
              cursor(clock, n(clock, "seq")),
              "semantics",
              "RECONCILED_FINAL_STATE");
        });
  }

  private static Problem expired() {
    return new Problem(
        410,
        "SYNC_CURSOR_EXPIRED",
        "Rebootstrap and compare against local baselines; do not delete local edits");
  }

  private static String text(JsonNode input, String key, int max) {
    var v = input.path(key);
    if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > max)
      throw Problem.invalid("Invalid " + key);
    return v.asText();
  }

  public JsonNode operate(SyncDevices.Identity who, JsonNode input) {
    if (!input.isObject()) throw Problem.invalid("Expected object");
    String op = text(input, "operationId", 36), kind = text(input, "kind", 20);
    try {
      UUID.fromString(op);
    } catch (IllegalArgumentException e) {
      throw Problem.invalid("Invalid operationId");
    }
    if (!input.path("createdAt").isIntegralNumber() || !input.path("createdAt").canConvertToLong())
      throw Problem.invalid("Invalid createdAt");
    long created = input.path("createdAt").asLong(), now = System.currentTimeMillis();
    if (created < now - RETENTION || created > now + 300000)
      throw new Problem(
          410, "SYNC_OPERATION_EXPIRED", "Operation expired; reconcile before creating another");
    var fields = new HashSet<>(Set.of("operationId", "kind", "createdAt"));
    fields.addAll(
        switch (kind) {
          case "MKDIR" -> Set.of("parentId", "name");
          case "EDIT" -> Set.of("entryId", "expectedToken", "name", "parentId");
          case "TRASH" -> Set.of("entryId", "expectedToken");
          case "RESTORE" -> Set.of("entryId", "expectedRevision");
          case "INIT_UPLOAD" ->
              Set.of("parentId", "name", "size", "sha256", "entryId", "expectedToken");
          default -> throw Problem.invalid("Unsupported sync operation");
        });
    input
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!fields.contains(k)) throw Problem.invalid("Unexpected field " + k);
            });
    var canonical = new TreeMap<String, Object>();
    input.fields().forEachRemaining(e -> canonical.put(e.getKey(), e.getValue()));
    String hash = Util.hash(encode(canonical));
    return db.atomic(
        who.userId(),
        () -> {
          devices.check(who);
          var existing =
              db.list(
                  "SELECT * FROM sync_operation WHERE device_id=? AND operation_id=?",
                  who.deviceId(),
                  op);
          if (!existing.isEmpty()) {
            if (!hash.equals(s(existing.getFirst(), "request_hash")))
              throw Problem.conflict("Operation ID reused with different input");
            return decode(s(existing.getFirst(), "result_json"));
          }
          if (db.count(
                  "SELECT COUNT(*) FROM sync_operation o JOIN sync_device d ON d.id=o.device_id"
                      + " WHERE d.user_id=?",
                  who.userId())
              >= 100000)
            throw new Problem(429, "SYNC_OPERATION_LIMIT", "Operation history limit reached");
          String uid = who.userId(),
              entryId = input.has("entryId") ? text(input, "entryId", 36) : null;
          Map<String, Object> entry = null;
          if (Set.of("EDIT", "TRASH").contains(kind)
              || kind.equals("INIT_UPLOAD") && entryId != null) {
            entry = current(uid, entryId);
            if (!s(entry, "changeToken").equals(text(input, "expectedToken", 64)))
              throw new Problem(
                  409, "SYNC_CONFLICT", "Cloud entry changed; preserve a conflict copy");
          }
          Object result;
          switch (kind) {
            case "MKDIR" ->
                result =
                    drive.createFolder(uid, text(input, "parentId", 36), text(input, "name", 180));
            case "EDIT" ->
                result =
                    drive.edit(
                        uid,
                        entryId,
                        input.has("name") ? text(input, "name", 180) : null,
                        input.has("parentId") ? text(input, "parentId", 36) : null,
                        s(entry, "kind"));
            case "TRASH" -> {
              if ("FOLDER".equals(s(entry, "kind"))
                  && db.count(
                          "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND"
                              + " deleted=FALSE",
                          uid,
                          entryId)
                      > 0)
                throw new Problem(
                    409,
                    "SYNC_FOLDER_NOT_EMPTY",
                    "Sync must reconcile children before deleting an empty folder");
              drive.trash(uid, entryId, s(entry, "kind"));
              result = Map.of("entryId", entryId, "trashed", true);
            }
            case "RESTORE" -> {
              var deleted = drive.entry(uid, entryId, true);
              if (!input.path("expectedRevision").isIntegralNumber()
                  || !input.path("expectedRevision").canConvertToLong()
                  || n(deleted, "revision") != input.path("expectedRevision").asLong())
                throw new Problem(409, "SYNC_CONFLICT", "Deleted entry revision changed");
              drive.restore(uid, entryId, s(deleted, "kind"));
              result = current(uid, entryId);
            }
            case "INIT_UPLOAD" -> {
              if (!input.path("size").isIntegralNumber()
                  || !input.path("size").canConvertToLong()
                  || input.path("size").asLong() < 0) throw Problem.invalid("Invalid file size");
              result =
                  uploads.init(
                      uid,
                      text(input, "parentId", 36),
                      text(input, "name", 180),
                      input.path("size").asLong(),
                      text(input, "sha256", 64),
                      entryId,
                      entry == null ? null : n(entry, "revision"));
              String uploadId = s((Map<String, Object>) result, "id");
              var mapped = db.list("SELECT device_id FROM sync_upload WHERE upload_id=?", uploadId);
              if (mapped.isEmpty())
                db.update(
                    "INSERT INTO sync_upload(upload_id,device_id,created_at) VALUES(?,?,?)",
                    uploadId,
                    who.deviceId(),
                    now);
              else if (!who.deviceId().equals(s(mapped.getFirst(), "device_id")))
                throw Problem.conflict("Upload belongs to another device");
            }
            default -> throw new IllegalStateException();
          }
          var value = decode(encode(result));
          db.update(
              "INSERT INTO"
                  + " sync_operation(device_id,operation_id,request_hash,result_json,created_at)"
                  + " VALUES(?,?,?,?,?)",
              who.deviceId(),
              op,
              hash,
              value.toString(),
              now);
          return value;
        });
  }

  public void requireUpload(SyncDevices.Identity who, String id) {
    devices.check(who);
    db.one(
        "SELECT upload_id FROM sync_upload WHERE upload_id=? AND device_id=?", id, who.deviceId());
  }
}
