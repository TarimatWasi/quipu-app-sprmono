package com.tarimatwasi.quipu.shared.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/** PDF files written by hand for the tests, to place objects where no writer would. */
final class HandMadePdfs {

  private HandMadePdfs() {}

  // ---- files written by hand, to place objects where no writer would

  static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    return out.toByteArray();
  }

  static byte[] stream(String dictionary, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int end = dictionary.lastIndexOf(">>");
    out.writeBytes(
        ascii(
            dictionary.substring(0, end)
                + " /Length "
                + data.length
                + " >>\nstream\n"
                + dictionary.substring(end + 2)));
    out.writeBytes(data);
    out.writeBytes(ascii("\nendstream"));
    return out.toByteArray();
  }

  /**
   * Objects 1 (catalog), 2 (pages) and 3 (page), then the given ones from 4 on, with a classic
   * cross-reference table that lists them all.
   */
  static byte[] handMade(byte[]... extra) {
    return handMadePage("", extra);
  }

  /** As {@link #handMade}, with more entries in the dictionary of the page. */
  static byte[] handMadePage(String pageEntries, byte[]... extra) {
    return handMadeDocument("/Type /Catalog", pageEntries, extra);
  }

  /** As {@link #handMadePage}, with other entries than {@code /Type} in the catalog. */
  static byte[] handMadeDocument(String catalogEntries, String pageEntries, byte[]... extra) {
    List<byte[]> objects = new ArrayList<>();
    objects.add(ascii("<< " + catalogEntries + " /Pages 2 0 R >>"));
    objects.add(ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
    objects.add(
        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " + pageEntries + " >>"));
    objects.addAll(List.of(extra));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ascii("%PDF-1.7\n"));
    StringBuilder xref = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n");
    xref.append("0000000000 65535 f \n");
    for (int i = 0; i < objects.size(); i++) {
      xref.append(String.format("%010d 00000 n \n", out.size()));
      out.writeBytes(ascii((i + 1) + " 0 obj\n"));
      out.writeBytes(objects.get(i));
      out.writeBytes(ascii("\nendobj\n"));
    }
    int start = out.size();
    out.writeBytes(ascii(xref.toString()));
    out.writeBytes(
        ascii(
            "trailer\n<< /Size "
                + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n"
                + start
                + "\n%%EOF\n"));
    return out.toByteArray();
  }

  /**
   * As {@link #handMade}, with a cross-reference stream (W [1 4 2], one row per object) instead of
   * a table: its dictionary gets {@code streamEntries}, and its data {@code padding} zero bytes
   * after the rows; with {@code predictor} each row starts with the byte of the PNG filter None.
   */
  static byte[] withXrefStream(String streamEntries, int padding, boolean predictor) {
    byte[] table = handMade();
    String text = new String(table, StandardCharsets.ISO_8859_1);
    int start = text.indexOf("xref\n");
    ByteArrayOutputStream rows = new ByteArrayOutputStream();
    writeRow(rows, predictor, 0, 0, 0xFFFF);
    for (int i = 1; i <= 3; i++) {
      writeRow(rows, predictor, 1, text.indexOf("\n" + i + " 0 obj") + 1, 0);
    }
    writeRow(rows, predictor, 1, start, 0); // object 4: the cross-reference stream itself
    rows.writeBytes(new byte[padding]);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(table, 0, start);
    out.writeBytes(
        stream(
            "4 0 obj\n<< /Type /XRef /Size 5 /W [1 4 2] /Root 1 0 R " + streamEntries + " >>",
            deflate(rows.toByteArray())));
    out.writeBytes(ascii("\nendobj\nstartxref\n" + start + "\n%%EOF\n"));
    return out.toByteArray();
  }

  static void writeRow(
      ByteArrayOutputStream rows, boolean predictor, int type, int field2, int field3) {
    if (predictor) {
      rows.write(0);
    }
    rows.write(type);
    rows.write(field2 >>> 24);
    rows.write(field2 >>> 16);
    rows.write(field2 >>> 8);
    rows.write(field2);
    rows.write(field3 >>> 8);
    rows.write(field3);
  }
}
