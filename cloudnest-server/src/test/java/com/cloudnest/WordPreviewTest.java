package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudnest.common.Problem;
import com.cloudnest.file.WordPreview;
import java.io.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class WordPreviewTest {
  @Test
  void rendersExcelAndPowerPointAsEscapedReadingViews() throws Exception {
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      book.createSheet("成绩").createRow(0).createCell(0).setCellValue("<script>unsafe</script>");
      book.write(out);
      String html =
          new WordPreview()
              .render(
                  new ByteArrayInputStream(out.toByteArray()),
                  "test.xlsx",
                  "application/octet-stream");
      assertTrue(html.contains("成绩"));
      assertTrue(html.contains("&lt;script&gt;"));
      assertFalse(html.contains("<script>"));
    }
    try (var slides = new org.apache.poi.xslf.usermodel.XMLSlideShow();
        var out = new ByteArrayOutputStream()) {
      slides.createSlide().createTextBox().setText("演示文稿内容");
      slides.write(out);
      assertTrue(
          new WordPreview()
              .render(
                  new ByteArrayInputStream(out.toByteArray()),
                  "test.pptx",
                  "application/octet-stream")
              .contains("演示文稿内容"));
    }
  }

  @Test
  void rendersDocxStructureAndEscapesActiveContent() throws Exception {
    byte[] data;
    try (var doc = new XWPFDocument();
        var out = new ByteArrayOutputStream()) {
      var heading = doc.createParagraph();
      heading.setStyle("Heading1");
      heading.createRun().setText("中文标题");
      var run = doc.createParagraph().createRun();
      run.setBold(true);
      run.setText("<script>alert('x')</script>");
      doc.createTable(1, 2).getRow(0).getCell(0).setText("表格内容");
      doc.write(out);
      data = out.toByteArray();
    }
    String html =
        new WordPreview().render(new ByteArrayInputStream(data), "test.docx", "application/zip");
    assertTrue(html.contains("<h1>中文标题</h1>"));
    assertTrue(html.contains("<strong>&lt;script&gt;"));
    assertTrue(html.contains("<table>"));
    assertTrue(html.contains("表格内容"));
    assertFalse(html.contains("<script>"));
  }

  @Test
  void rejectsDamagedDocAndExpandedZipBomb() throws Exception {
    var preview = new WordPreview();
    assertThrows(
        Problem.class,
        () ->
            preview.render(
                new ByteArrayInputStream(new byte[10]), "bad.doc", "application/msword"));
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < 33; i++) zip.write(block);
      zip.closeEntry();
    }
    assertThrows(
        Problem.class,
        () ->
            preview.render(
                new ByteArrayInputStream(out.toByteArray()), "bomb.docx", "application/zip"));
    assertThrows(
        Problem.class,
        () ->
            preview.render(
                new ByteArrayInputStream(out.toByteArray()), "bomb.xlsx", "application/zip"));
    assertThrows(
        Problem.class,
        () ->
            preview.render(
                new ByteArrayInputStream(out.toByteArray()), "bomb.pptx", "application/zip"));
  }
}
