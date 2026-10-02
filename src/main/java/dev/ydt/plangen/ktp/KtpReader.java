package dev.ydt.plangen.ktp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * Reads a KTP file (.docx or the old binary .doc) into plain text: the lines above the table (the title) and the
 * rows of the biggest table. What the columns mean is decided later by {@link KtpParser}.
 */
public final class KtpReader {
    private KtpReader() {}

    /** headText: the paragraphs before the first table; rows: the cells of every row of the lesson table. */
    public record RawDoc(List<String> headText, List<List<String>> rows) {}

    public static RawDoc read(byte[] data) throws IOException {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            return readDocx(data);
        }
        if (data.length > 4 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF) {
            return readDoc(data);
        }
        throw new IOException("Поддерживаются файлы Word: .docx и .doc");
    }

    private static RawDoc readDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            List<String> head = new ArrayList<>();
            XWPFTable best = null;
            boolean seenTable = false;
            for (IBodyElement e : doc.getBodyElements()) {
                if (e instanceof XWPFParagraph p) {
                    String t = clean(p.getText());
                    if (!seenTable && !t.isEmpty() && head.size() < 30) head.add(t);
                } else if (e instanceof XWPFTable t) {
                    seenTable = true;
                    if (best == null || t.getRows().size() > best.getRows().size()) best = t;
                }
            }
            List<List<String>> rows = new ArrayList<>();
            if (best != null) {
                for (XWPFTableRow r : best.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell c : r.getTableCells()) cells.add(clean(c.getText()));
                    rows.add(cells);
                }
            }
            return new RawDoc(head, rows);
        }
    }

    private static RawDoc readDoc(byte[] data) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data))) {
            Range range = doc.getRange();
            List<String> head = new ArrayList<>();
            for (int i = 0; i < range.numParagraphs() && head.size() < 30; i++) {
                Paragraph p = range.getParagraph(i);
                if (p.isInTable()) break;
                String t = clean(p.text());
                if (!t.isEmpty()) head.add(t);
            }
            List<List<String>> best = new ArrayList<>();
            TableIterator it = new TableIterator(range);
            while (it.hasNext()) {
                Table t = it.next();
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < t.numRows(); r++) {
                    TableRow row = t.getRow(r);
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.numCells(); c++) cells.add(clean(row.getCell(c).text()));
                    rows.add(cells);
                }
                if (rows.size() > best.size()) best = rows;
            }
            return new RawDoc(head, best);
        }
    }

    /** Word cell text: control characters (cell and row marks) become spaces, whitespace is collapsed. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u0000-\\u001F\\u00A0\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").strip();
    }
}
