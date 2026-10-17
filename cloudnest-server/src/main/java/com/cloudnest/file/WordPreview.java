package com.cloudnest.file;

import com.cloudnest.common.Problem;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipInputStream;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Produces a reading view, without running Office macros or loading external resources. */
@Component
public class WordPreview {
  public static final long MAX_SIZE = 10 * 1024 * 1024;
  private final Semaphore slots = new Semaphore(2);

  public static boolean supported(String name, String mime) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".doc")
        || lower.endsWith(".docx")
        || lower.matches(".*\\.(xls|xlsx|ppt|pptx)")
        || mime.equals("application/msword")
        || mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  }

  public String render(InputStream input, String name, String mime) throws IOException {
    if (!slots.tryAcquire()) throw new Problem(503, "PREVIEW_BUSY", "文档预览繁忙，请稍后重试");
    try {
      byte[] data = input.readNBytes((int) MAX_SIZE + 1);
      if (data.length > MAX_SIZE)
        throw new Problem(413, "PREVIEW_TOO_LARGE", "Word 文档超过 10 MiB，请下载后查看");
      StringBuilder html = new StringBuilder();
      String lower = name.toLowerCase(Locale.ROOT);
      if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
        // Bound expanded OOXML input before POI builds the document model.
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
          long expanded = 0;
          int entries = 0;
          byte[] buffer = new byte[8192];
          while (zip.getNextEntry() != null) {
            if (++entries > 4000) throw tooComplex();
            int count;
            while ((count = zip.read(buffer)) != -1) {
              expanded += count;
              if (expanded > 32 * 1024 * 1024) throw tooComplex();
            }
          }
        }
      }
      if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {}
      if (lower.matches(".*\\.(xls|xlsx)")) {
        spreadsheet(data, html);
        return html.toString();
      }
      if (lower.matches(".*\\.(ppt|pptx)")) {
        presentation(data, html);
        return html.toString();
      }
      if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(data))) {
          for (var body : doc.getBodyElements()) {
            if (body instanceof XWPFParagraph paragraph) paragraph(html, paragraph);
            else if (body instanceof XWPFTable table) {
              html.append("<table><tbody>");
              for (var row : table.getRows()) {
                html.append("<tr>");
                for (var cell : row.getTableCells()) {
                  html.append("<td>");
                  for (var paragraph : cell.getParagraphs()) paragraph(html, paragraph);
                  html.append("</td>");
                }
                html.append("</tr>");
                limit(html);
              }
              html.append("</tbody></table>");
            }
            limit(html);
          }
        }
      } else {
        try (var doc = new WordExtractor(new ByteArrayInputStream(data))) {
          for (String paragraph : doc.getParagraphText()) {
            html.append("<p>").append(escape(WordExtractor.stripFields(paragraph))).append("</p>");
            limit(html);
          }
        }
      }
      if (html.isEmpty()) html.append("<p>文档没有可预览的文本内容。</p>");
      return html.toString();
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(422, "DOCUMENT_PREVIEW_FAILED", "文档损坏、已加密或格式不受支持，请下载原文件查看");
    } finally {
      slots.release();
    }
  }

  private static void paragraph(StringBuilder html, XWPFParagraph paragraph) {
    String style = paragraph.getStyle();
    String tag =
        style != null && style.matches("(?i)heading[1-6]")
            ? "h" + style.charAt(style.length() - 1)
            : "p";
    html.append('<').append(tag).append('>');
    for (var run : paragraph.getRuns()) {
      if (run.isBold()) html.append("<strong>");
      if (run.isItalic()) html.append("<em>");
      html.append(escape(run.text()));
      if (run.isItalic()) html.append("</em>");
      if (run.isBold()) html.append("</strong>");
      limit(html);
    }
    html.append("</").append(tag).append('>');
  }

  private static void spreadsheet(byte[] data, StringBuilder html) throws IOException {
    try (var book =
        org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(data))) {
      var format = new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
      format.setUseCachedValuesForFormulaCells(true);
      int cells = 0;
      for (var sheet : book) {
        html.append("<h2>").append(escape(sheet.getSheetName())).append("</h2><table><tbody>");
        if (sheet.getLastRowNum() > 2000) throw tooComplex();
        for (var row : sheet) {
          if (row.getLastCellNum() > 100) throw tooComplex();
          html.append("<tr>");
          for (int c = 0; c < row.getLastCellNum(); c++) {
            if (++cells > 20000) throw tooComplex();
            html.append("<td>")
                .append(escape(format.formatCellValue(row.getCell(c))))
                .append("</td>");
          }
          html.append("</tr>");
          limit(html);
        }
        html.append("</tbody></table>");
      }
    }
  }

  private static void presentation(byte[] data, StringBuilder html) throws IOException {
    try (var slides =
        org.apache.poi.sl.usermodel.SlideShowFactory.create(new ByteArrayInputStream(data))) {
      int n = 0;
      for (var slide : slides.getSlides()) {
        if (++n > 200) throw tooComplex();
        html.append("<h2>幻灯片 ").append(n).append("</h2>");
        for (var shape : slide.getShapes()) {
          if (shape instanceof org.apache.poi.sl.usermodel.TextShape<?, ?> text)
            html.append("<p>").append(escape(text.getText())).append("</p>");
          limit(html);
        }
      }
    }
  }

  private static String escape(String text) {
    return HtmlUtils.htmlEscape(text)
        .replace("\r", "")
        .replace("\n", "<br>")
        .replace("\u0007", " ");
  }

  private static void limit(StringBuilder html) {
    if (html.length() > 2 * 1024 * 1024) throw tooComplex();
  }

  private static Problem tooComplex() {
    return new Problem(413, "PREVIEW_TOO_LARGE", "文档内容过大或过于复杂，请下载后查看");
  }
}
