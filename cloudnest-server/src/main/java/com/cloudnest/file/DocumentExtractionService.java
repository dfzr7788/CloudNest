package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.Problem;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

@Service
public class DocumentExtractionService {
  public static final String VERSION = "structured-v1";
  public static final int MAX_CHARS = 262144;

  public record Block(String sourceId, String locator, String text) {}

  public record Document(
      long revision,
      String hash,
      String extractorVersion,
      String status,
      List<Block> blocks,
      int processedCharacters,
      int processedPages,
      Integer totalPages,
      boolean truncated,
      String reason) {
    public String text() {
      return String.join(
          "\n\n",
          blocks.stream()
              .map(b -> "[" + b.sourceId() + " " + b.locator() + "]\n" + b.text())
              .toList());
    }
  }

  private final DriveService drive;
  private final ContentService content;

  public DocumentExtractionService(DriveService drive, ContentService content) {
    this.drive = drive;
    this.content = content;
  }

  public static boolean supported(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|markdown|pdf|docx)");
  }

  public Document extract(String uid, String id) {
    var file = drive.file(uid, id);
    var blob = content.blob(uid, id);
    String name = s(file, "name").toLowerCase(Locale.ROOT);
    var blocks = new ArrayList<Block>();
    int[] chars = {0};
    boolean[] truncated = {false};
    int processedPages = 0;
    Integer totalPages = null;
    String reason = "";
    if (!supported(name))
      throw new Problem(400, "AI_UNSUPPORTED_DOCUMENT", "AI 暂支持 TXT、Markdown、带文本层 PDF 和 DOCX");
    if (n(blob, "size") > (name.endsWith(".pdf") || name.endsWith(".docx") ? 10 : 2) * 1024 * 1024)
      throw new Problem(413, "AI_DOCUMENT_TOO_LARGE", "文本上限 2 MiB，PDF/DOCX 上限 10 MiB，请缩小文件范围");
    try (var input = content.open(blob)) {
      if (name.endsWith(".pdf")) {
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(input.readNBytes(10 * 1024 * 1024 + 1))) {
          totalPages = pdf.getNumberOfPages();
          if (pdf.isEncrypted() && !pdf.getCurrentAccessPermission().canExtractContent())
            throw new Problem(422, "AI_DOCUMENT_ENCRYPTED", "PDF 不允许提取文本");
          for (int page = 1; page <= Math.min(100, totalPages) && chars[0] < MAX_CHARS; page++) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var out = new StringBuilder();
            stripper.writeText(
                pdf,
                new Writer() {
                  public void write(char[] c, int off, int len) {
                    int take = Math.min(len, MAX_CHARS + 1 - out.length());
                    if (take > 0) out.append(c, off, take);
                  }

                  public void flush() {}

                  public void close() {}
                });
            add(blocks, chars, truncated, "第 " + page + " 页", out.toString());
            processedPages = page;
          }
          if (processedPages < totalPages) truncated[0] = true;
        }
      } else if (name.endsWith(".docx")) {
        try (var doc = new XWPFDocument(input)) {
          int paragraph = 0, table = 0;
          for (var element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph p)
              add(blocks, chars, truncated, "段落 " + (++paragraph), p.getText());
            else if (element instanceof XWPFTable t) {
              int row = 0;
              table++;
              for (var r : t.getRows())
                add(
                    blocks,
                    chars,
                    truncated,
                    "表格 " + table + " 行 " + (++row),
                    String.join(
                        " | ", r.getTableCells().stream().map(XWPFTableCell::getText).toList()));
            }
            if (chars[0] >= MAX_CHARS) {
              truncated[0] = true;
              break;
            }
          }
          reason = "DOCX 按段落与表格行提取；不含图片文字、页眉页脚、批注和文本框";
        }
      } else {
        // Reject invalid encodings instead of silently replacing text with gibberish.
        byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        String[] lines = text.split("\n", -1);
        String heading = "";
        for (int start = 0; start < lines.length; start += 80) {
          int end = Math.min(lines.length, start + 80);
          for (int line = start; line < end; line++)
            if (lines[line].matches("^#{1,6} .*")) heading = lines[line].strip();
          add(
              blocks,
              chars,
              truncated,
              "行 " + (start + 1) + "–" + end + (heading.isEmpty() ? "" : " " + heading),
              String.join("\n", Arrays.copyOfRange(lines, start, end)));
          if (chars[0] >= MAX_CHARS) {
            if (end < lines.length) truncated[0] = true;
            break;
          }
        }
      }
    } catch (Problem p) {
      throw p;
    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
      throw new Problem(422, "AI_DOCUMENT_ENCRYPTED", "PDF 已加密，需要先解密");
    } catch (CharacterCodingException e) {
      throw new Problem(422, "AI_ENCODING_UNSUPPORTED", "请将文本转换为 UTF-8 后重试");
    } catch (Exception e) {
      throw new Problem(422, "AI_EXTRACTION_FAILED", "文档损坏、已加密或无法提取文本");
    }
    String status =
        blocks.isEmpty() ? "NO_EXTRACTABLE_TEXT" : truncated[0] ? "PARTIAL" : "COMPLETE";
    if (truncated[0]) reason = "达到 100 页或 256K 字符提取上限；" + reason;
    if (blocks.isEmpty()) reason = "未提取到文本；扫描件和图片尚不支持 OCR";
    return new Document(
        n(file, "revision"),
        s(blob, "sha256"),
        VERSION,
        status,
        List.copyOf(blocks),
        chars[0],
        processedPages,
        totalPages,
        truncated[0],
        reason);
  }

  private static void add(
      List<Block> blocks, int[] chars, boolean[] truncated, String locator, String text) {
    if (text.isBlank()) return;
    int remaining = MAX_CHARS - chars[0];
    if (text.length() > remaining) {
      text = text.substring(0, remaining);
      truncated[0] = true;
    }
    if (!text.isBlank()) {
      blocks.add(new Block("S" + (blocks.size() + 1), locator, text));
      chars[0] += text.length();
    }
  }
}
