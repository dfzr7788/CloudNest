package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class Thumbnails {
  private final DriveService drive;
  private final ContentService content;
  private final Db db;
  private final Path cache;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(40),
          Thread.ofPlatform().daemon().name("thumbnail-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public Thumbnails(
      DriveService drive, ContentService content, Db db, @Value("${cloudnest.data-dir}") String dir)
      throws IOException {
    this.drive = drive;
    this.content = content;
    this.db = db;
    cache = Path.of(dir).toAbsolutePath().resolve("thumbnails");
    Files.createDirectories(cache);
  }

  @GetMapping("/api/files/{id}/thumbnail")
  public void read(Authentication a, @PathVariable String id, HttpServletResponse res)
      throws IOException {
    drive.file(a.getName(), id);
    var b = content.blob(a.getName(), id);
    String mime = s(b, "mime"), bid = s(b, "id");
    if (!Set.of("image/png", "image/jpeg", "image/gif", "image/bmp").contains(mime)
        || n(b, "size") > 32 * 1024 * 1024)
      throw new Problem(415, "THUMBNAIL_UNSUPPORTED", "此图片保留原图预览");
    Path path = cache.resolve(bid + ".png");
    if (Files.exists(path)) {
      res.setContentType("image/png");
      res.setHeader("Cache-Control", "no-store");
      res.setContentLengthLong(Files.size(path));
      try (var in = Files.newInputStream(path)) {
        in.transferTo(res.getOutputStream());
      }
      return;
    }
    if (pending.add(bid))
      try {
        executor.execute(
            () -> {
              Path temporary = cache.resolve(bid + ".writing");
              try {
                try (var input = content.open(b);
                    var stream = ImageIO.createImageInputStream(input)) {
                  var readers = ImageIO.getImageReaders(stream);
                  if (!readers.hasNext()) return;
                  var reader = readers.next();
                  try {
                    reader.setInput(stream, true, true);
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 1 || h < 1 || (long) w * h > 40000000) return;
                    var param = reader.getDefaultReadParam();
                    int sampling = Math.max(1, Math.max(w, h) / 512);
                    param.setSourceSubsampling(sampling, sampling, 0, 0);
                    BufferedImage source = reader.read(0, param);
                    double ratio =
                        Math.min(1, 256.0 / Math.max(source.getWidth(), source.getHeight()));
                    var image =
                        new BufferedImage(
                            Math.max(1, (int) (source.getWidth() * ratio)),
                            Math.max(1, (int) (source.getHeight() * ratio)),
                            BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    try {
                      graphics.setRenderingHint(
                          RenderingHints.KEY_INTERPOLATION,
                          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                      graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
                    } finally {
                      graphics.dispose();
                      source.flush();
                    }
                    ImageIO.write(image, "png", temporary.toFile());
                    image.flush();
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                  } finally {
                    reader.dispose();
                  }
                }
              } catch (Exception ignored) {
              } finally {
                pending.remove(bid);
                try {
                  Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
              }
            });
      } catch (RejectedExecutionException ex) {
        pending.remove(bid);
      }
    res.setStatus(202);
    res.setHeader("Retry-After", "1");
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public void cleanup() throws IOException {
    try (var paths = Files.list(cache)) {
      for (Path p : paths.toList()) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".png")) continue;
        String id = name.substring(0, name.length() - 4);
        if (db.count("SELECT COUNT(*) FROM file_blob WHERE id=?", id) == 0 && !pending.contains(id))
          Files.deleteIfExists(p);
      }
    }
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }
}
