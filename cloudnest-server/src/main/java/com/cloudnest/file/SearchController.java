package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SearchController {
  @org.springframework.beans.factory.annotation.Value("${cloudnest.index-enabled:true}")
  private boolean enabled;

  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final WordPreview office;

  public SearchController(Db db, DriveService drive, ContentService content, WordPreview office) {
    this.db = db;
    this.drive = drive;
    this.content = content;
    this.office = office;
  }

  public record Tags(@NotNull @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags) {}

  @GetMapping("/entries/{id}/tags")
  public List<String> tags(Authentication a, @PathVariable String id) {
    drive.live(a.getName(), id);
    return db.list("SELECT tag FROM entry_tag WHERE entry_id=? ORDER BY tag", id).stream()
        .map(e -> s(e, "tag"))
        .toList();
  }

  @PutMapping("/entries/{id}/tags")
  public void tags(Authentication a, @PathVariable String id, @Valid @RequestBody Tags in) {
    db.atomic(
        a.getName(),
        () -> {
          drive.live(a.getName(), id);
          db.update("DELETE FROM entry_tag WHERE entry_id=?", id);
          for (String tag :
              new LinkedHashSet<>(
                  in.tags().stream().map(t -> t.strip().toLowerCase(Locale.ROOT)).toList())) {
            String value = tag.strip();
            if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl))
              throw Problem.invalid("标签无效");
            db.update("INSERT INTO entry_tag(entry_id,tag) VALUES(?,?)", id, value);
          }
          return null;
        });
  }

  @GetMapping("/tags")
  public List<Map<String, Object>> tags(Authentication a) {
    return db.list(
        "SELECT t.tag,COUNT(*) AS count FROM entry_tag t JOIN drive_entry e ON e.id=t.entry_id"
            + " WHERE e.user_id=? AND e.deleted=FALSE GROUP BY t.tag ORDER BY t.tag",
        a.getName());
  }

  @GetMapping("/search")
  public Map<String, Object> search(
      Authentication a,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String tag,
      @RequestParam(defaultValue = "false") boolean content,
      @RequestParam(defaultValue = "1") int page) {
    if (q.length() > 180 || tag.length() > 40) throw Problem.invalid("搜索条件过长");
    page = Math.max(1, page);
    String where = "e.user_id=? AND e.deleted=FALSE AND e.parent_id IS NOT NULL";
    var args = new ArrayList<Object>(List.of(a.getName()));
    if (!tag.isBlank()) {
      where += " AND EXISTS(SELECT 1 FROM entry_tag t WHERE t.entry_id=e.id AND t.tag=?)";
      args.add(tag);
    }
    if (!q.isBlank()) {
      String pattern =
          "%"
              + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
              + "%";
      where += " AND (LOWER(e.name) LIKE ? ESCAPE '!'";
      args.add(pattern);
      if (content) {
        where +=
            " OR EXISTS(SELECT 1 FROM entry_index i WHERE i.entry_id=e.id AND i.revision=e.revision"
                + " AND i.status='READY' AND LOWER(i.body) LIKE ? ESCAPE '!')";
        args.add(pattern);
      }
      where += ")";
    }
    long total = db.count("SELECT COUNT(*) FROM drive_entry e WHERE " + where, args.toArray());
    args.add(50);
    args.add((page - 1L) * 50);
    var rows =
        db.list(
            "SELECT e.* FROM drive_entry e WHERE "
                + where
                + " ORDER BY e.updated_at DESC,e.id LIMIT ? OFFSET ?",
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var e : rows) {
      try {
        drive.live(a.getName(), s(e, "id"));
        var view = drive.view(e);
        view.put(
            "path",
            String.join(
                " / ",
                drive.breadcrumbs(a.getName(), s(e, "parent_id")).stream()
                    .map(p -> s(p, "name"))
                    .toList()));
        view.put("tags", tags(a, s(e, "id")));
        items.add(view);
      } catch (Problem ignored) {
      }
    }
    long pending =
        db.count(
            "SELECT COUNT(*) FROM drive_entry e LEFT JOIN entry_index i ON i.entry_id=e.id WHERE"
                + " e.user_id=? AND e.kind='FILE' AND e.deleted=FALSE AND (i.entry_id IS NULL OR"
                + " i.revision<>e.revision)",
            a.getName());
    return Map.of("items", items, "total", total, "page", page, "pageSize", 50, "pending", pending);
  }

  @PostMapping("/search/reindex")
  public void reindex(Authentication a) {
    db.update(
        "DELETE FROM entry_index WHERE entry_id IN (SELECT id FROM drive_entry WHERE user_id=?)",
        a.getName());
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 15000)
  public void index() {
    if (enabled) indexBatch();
  }

  public void indexBatch() {
    for (var e :
        db.list(
            "SELECT e.* FROM drive_entry e LEFT JOIN entry_index i ON i.entry_id=e.id WHERE"
                + " e.kind='FILE' AND e.deleted=FALSE AND (i.entry_id IS NULL OR"
                + " i.revision<>e.revision) ORDER BY e.id LIMIT 20")) {
      String text = "", status = "UNSUPPORTED";
      try {
        String uid = s(e, "user_id"), id = s(e, "id"), mime = s(e, "mime");
        drive.file(uid, id);
        long size = n(e, "size");
        if (size <= 2 * 1024 * 1024
            && (mime.startsWith("text/")
                || s(e, "name").matches("(?i).*\\.(md|markdown|txt|json|java|py|cpp|csv|log)"))) {
          try (var in = content.open(content.blob(uid, id))) {
            text =
                new String(in.readNBytes(2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
          }
          status = "READY";
        } else if (size <= WordPreview.MAX_SIZE && WordPreview.supported(s(e, "name"), mime)) {
          try (var in = content.open(content.blob(uid, id))) {
            text = office.render(in, s(e, "name"), mime).replaceAll("<[^>]*>", " ");
          }
          text = org.springframework.web.util.HtmlUtils.htmlUnescape(text);
          status = "READY";
        } else if (size <= 10 * 1024 * 1024 && mime.equals("application/pdf")) {
          try (var in = content.open(content.blob(uid, id));
              var pdf = org.apache.pdfbox.Loader.loadPDF(in.readNBytes(10 * 1024 * 1024 + 1))) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setEndPage(Math.min(100, pdf.getNumberOfPages()));
            var extracted = new StringBuilder();
            stripper.writeText(
                pdf,
                new Writer() {
                  @Override
                  public void write(char[] c, int off, int len) throws IOException {
                    int remaining = 262144 - extracted.length();
                    if (remaining > 0) extracted.append(c, off, Math.min(len, remaining));
                  }

                  @Override
                  public void flush() {}

                  @Override
                  public void close() {}
                });
            text = extracted.toString();
            status = "READY";
          }
        }
      } catch (Exception failure) {
        status = "FAILED";
      }
      if (text.length() > 262144) text = text.substring(0, 262144);
      String body = text, mark = status;
      try {
        db.atomic(
            s(e, "user_id"),
            () -> {
              var live =
                  db.list(
                      "SELECT revision FROM drive_entry WHERE id=? AND user_id=? AND deleted=FALSE",
                      e.get("id"),
                      e.get("user_id"));
              if (live.isEmpty() || n(live.getFirst(), "revision") != n(e, "revision")) return null;
              db.update("DELETE FROM entry_index WHERE entry_id=?", e.get("id"));
              db.update(
                  "INSERT INTO entry_index(entry_id,revision,body,status,updated_at)"
                      + " VALUES(?,?,?,?,?)",
                  e.get("id"),
                  e.get("revision"),
                  body,
                  mark,
                  System.currentTimeMillis());
              return null;
            });
      } catch (Exception ignored) {
      }
    }
  }
}
