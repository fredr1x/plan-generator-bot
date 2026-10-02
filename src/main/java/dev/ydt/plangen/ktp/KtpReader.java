package dev.ydt.plangen.ktp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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

    /**
     * headText: the paragraphs before the first table; rows: the cells of every row of the lesson table.
     * flat is true for a PDF: there are no cells, every row is one text line as ["x", "text"] where x is the
     * horizontal start of the line in points (lines of one column start at the same x).
     */
    public record RawDoc(List<String> headText, List<List<String>> rows, boolean flat) {
        public RawDoc(List<String> headText, List<List<String>> rows) {
            this(headText, rows, false);
        }
    }

    /** Lines shorter than this in total mean a scan without a text layer. */
    private static final int MIN_PDF_CHARS = 300;

    /** Gap in points between two words of a line above which they are taken as two table cells. */
    private static final float CELL_GAP = 10f;

    public static RawDoc read(byte[] data) throws IOException {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            return readDocx(data);
        }
        if (data.length > 4 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF) {
            return readDoc(data);
        }
        if (data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            return readPdf(data);
        }
        throw new IOException("Поддерживаются файлы Word (.docx, .doc) и PDF с текстом");
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

    /**
     * A PDF with a text layer. The text is taken in the order it was written (for a PDF made from Word this keeps
     * the text of one table cell together), one row per line with the x position of the line.
     */
    private static RawDoc readPdf(byte[] data) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(data)) {
            StringBuilder cur = new StringBuilder();
            float[] firstX = {-1};
            float[] lastEnd = {0};
            PDFTextStripper stripper = new PDFTextStripper() {
                private void flush() {
                    String t = clean(cur.toString());
                    if (!t.isEmpty()) rows.add(List.of(String.valueOf(Math.round(firstX[0])), t));
                    cur.setLength(0);
                    firstX[0] = -1;
                }

                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    // PDFBox hands over whole text runs ("44 Parts of body"), so cells are told apart by the
                    // gap between glyphs: a wide gap inside a run means the next word is in the next table cell
                    boolean space = false;
                    for (TextPosition tp : positions) {
                        String u = tp.getUnicode();
                        if (u == null || u.isBlank()) {
                            space = true;
                            continue;
                        }
                        float x = tp.getXDirAdj();
                        if (cur.length() > 0 && x - lastEnd[0] > CELL_GAP) {
                            flush();
                        } else if (space && cur.length() > 0) {
                            cur.append(' ');
                        }
                        space = false;
                        if (firstX[0] < 0) firstX[0] = x;
                        cur.append(u);
                        lastEnd[0] = x + tp.getWidthDirAdj();
                    }
                }

                @Override
                protected void writeWordSeparator() {
                    cur.append(' ');
                }

                @Override
                protected void writeLineSeparator() {
                    flush();
                }

                @Override
                protected void writeParagraphEnd() {
                    flush();
                }

                @Override
                protected void writePageEnd() {
                    flush();
                }
            };
            stripper.setSortByPosition(false);
            stripper.getText(doc);
        }
        int chars = rows.stream().mapToInt(r -> r.get(1).length()).sum();
        if (chars < MIN_PDF_CHARS) {
            throw new IOException("в этом PDF нет текста (похоже на скан). Пришлите Word или PDF, из которого "
                    + "можно выделить текст");
        }
        List<String> head = new ArrayList<>();
        for (int i = 0; i < rows.size() && head.size() < 6; i++) head.add(rows.get(i).get(1));
        return new RawDoc(head, rows, true);
    }

    /** Word cell text: control characters (cell and row marks) become spaces, whitespace is collapsed. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u0000-\\u001F\\u00A0\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").strip();
    }
}
