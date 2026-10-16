package com.cloudnest.file;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DriveController {
  private final DriveService drive;
  private final com.cloudnest.common.Db db;

  public DriveController(DriveService drive, com.cloudnest.common.Db db) {
    this.drive = drive;
    this.db = db;
  }

  public record FolderInput(String parentId, @NotBlank String name) {}

  public record EditInput(String name, String parentId) {}

  @GetMapping("/entries")
  public Map<String, Object> entries(
      Authentication a,
      @RequestParam(required = false) String parentId,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize,
      @RequestParam(defaultValue = "all") String collection,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(defaultValue = "0") long minSize,
      @RequestParam(defaultValue = "9223372036854775807") long maxSize,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "9223372036854775807") long before) {
    return drive.list(
        a.getName(),
        parentId,
        q,
        scope,
        sort,
        direction,
        page,
        pageSize,
        collection,
        type,
        minSize,
        maxSize,
        after,
        before);
  }

  @GetMapping("/stats")
  public Map<String, Object> stats(Authentication a) {
    String uid = a.getName();
    return Map.of(
        "files",
        db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND kind='FILE' AND deleted=FALSE",
            uid),
        "folders",
        db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND kind='FOLDER' AND deleted=FALSE"
                + " AND parent_id IS NOT NULL",
            uid),
        "images",
        db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND kind='FILE' AND deleted=FALSE AND"
                + " mime LIKE 'image/%'",
            uid),
        "documents",
        db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND kind='FILE' AND deleted=FALSE AND"
                + " (mime LIKE 'text/%' OR mime='application/pdf')",
            uid));
  }

  @GetMapping("/folders")
  public List<Map<String, Object>> folders(
      Authentication a, @RequestParam(required = false) String parentId) {
    var f = drive.folder(a.getName(), parentId);
    return db
        .list(
            "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND kind='FOLDER' AND"
                + " deleted=FALSE ORDER BY name,id",
            a.getName(),
            f.get("id"))
        .stream()
        .map(drive::view)
        .toList();
  }

  @PostMapping("/folders")
  public Map<String, Object> create(Authentication a, @Valid @RequestBody FolderInput in) {
    return drive.createFolder(a.getName(), in.parentId, in.name);
  }

  @GetMapping("/folders/{id}/breadcrumbs")
  public List<Map<String, Object>> crumbs(Authentication a, @PathVariable String id) {
    return drive.breadcrumbs(a.getName(), id);
  }

  @PatchMapping("/folders/{id}")
  public Map<String, Object> editFolder(
      Authentication a, @PathVariable String id, @RequestBody EditInput in) {
    return drive.edit(a.getName(), id, in.name, in.parentId, "FOLDER");
  }

  @PatchMapping("/files/{id}")
  public Map<String, Object> editFile(
      Authentication a, @PathVariable String id, @RequestBody EditInput in) {
    return drive.edit(a.getName(), id, in.name, in.parentId, "FILE");
  }

  @DeleteMapping("/folders/{id}")
  public void deleteFolder(Authentication a, @PathVariable String id) {
    drive.trash(a.getName(), id, "FOLDER");
  }

  @DeleteMapping("/files/{id}")
  public void deleteFile(Authentication a, @PathVariable String id) {
    drive.trash(a.getName(), id, "FILE");
  }

  @GetMapping("/trash")
  public List<Map<String, Object>> trash(Authentication a) {
    return drive.trashList(a.getName());
  }

  private String kind(String type) {
    if (type.equals("files")) return "FILE";
    if (type.equals("folders")) return "FOLDER";
    throw com.cloudnest.common.Problem.invalid("资源类型错误");
  }

  @PostMapping("/trash/{type}/{id}/restore")
  public Map<String, Object> restore(
      Authentication a, @PathVariable String type, @PathVariable String id) {
    return drive.restore(a.getName(), id, kind(type));
  }

  @DeleteMapping("/trash/{type}/{id}")
  public void purge(Authentication a, @PathVariable String type, @PathVariable String id) {
    drive.purge(a.getName(), id, kind(type));
  }
}
