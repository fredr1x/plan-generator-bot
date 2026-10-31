package dev.ydt.plangen.doc;

import dev.ydt.plangen.llm.LessonContent;
import dev.ydt.plangen.llm.LessonContent.Stage;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;

/**
 * Builds the short term plan as in the school sample (Grade7_Three_Lessons.docx): "Short term plan", a header table
 * (unit | lesson N, school, teacher, date, grade | present | absent, lesson title, learning objectives, lesson
 * objectives, values) and the procedure table (Beginning, one Middle row per assessed exercise, End). In the End row
 * the students' actions and the assessment are one merged cell, as in the sample.
 */
public final class PlanDocx {

    /**
     * classCounts is "7 А=25;7 Б=24" (may be null for old plans). lessonNo is the number of the lesson in the KTP
     * (null when the plan is not from the KTP).
     */
    public record Data(String lang, String school, String teacher, LocalDate date, String classes,
                       int students, String section, String topic, String objectives,
                       LessonContent content, String classCounts, boolean termTest, Integer lessonNo) {}

    private static final String FONT = "Times New Roman";
    private static final int SIZE = 12;
    private static final String HEADER_BORDER = "8496B0";
    private static final String STAGE_BORDER = "B5C7EA";
    private static final int[] HEADER_COLS = {3598, 2272, 2006, 2981};    // total 10857 twips, as in the sample
    private static final int[] STAGE_COLS = {1385, 3920, 2250, 1839, 1463};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Lines printed in bold: the exercise reference, ANSWERS and the points line, as in the sample. */
    /** "AI prompt: "..."" line of the AI task; the prompt becomes a link that opens ChatGPT with it. */
    private static final Pattern AI_PROMPT = Pattern.compile("(?i)^\\s*(AI prompt:)\\s*[\"“«](.+?)[\"”»]\\s*$");
    private static final Pattern BOLD_LINE = Pattern.compile("(?i)^\\s*(ex\\.?\\s*:?\\s*\\d.*|answers\\b.*|ответы\\b.*"
            + "|жауаптар\\b.*|exercise\\s+\\d+.*points?.*|упражнение\\s+\\d+.*балл.*|\\d+-жаттығу.*балл.*)$");

    private PlanDocx() {}

    public static void write(Data d, Path file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            Labels l = Labels.of(d.lang());
            page(doc);

            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            title.setSpacingAfter(120);
            run(title, l.title(), true, 14);

            header(doc, l, d);
            doc.createParagraph().setSpacingAfter(0);
            stages(doc, l, d.content().stages(), d.termTest());
            doc.write(out);
        }
    }

    // ---------- page ----------

    private static void page(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        sect.addNewPgSz().setW(BigInteger.valueOf(11906));
        sect.getPgSz().setH(BigInteger.valueOf(16838)); // A4 portrait
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setLeft(BigInteger.valueOf(1800));
        mar.setRight(BigInteger.valueOf(1800));
        mar.setTop(BigInteger.valueOf(1440));
        mar.setBottom(BigInteger.valueOf(1440));
    }

    // ---------- header table ----------

    private static void header(XWPFDocument doc, Labels l, Data d) {
        XWPFTable t = table(doc, 9, HEADER_COLS, HEADER_BORDER);

        // row 0: unit | lesson N
        XWPFTableRow r0 = t.getRow(0);
        text(span(r0, 0, 2, HEADER_COLS), nz(d.section()), true);
        text(span(r0, 1, 2, HEADER_COLS), d.lessonNo() == null ? "" : l.lesson() + " " + d.lessonNo(), false);

        labelRow(t.getRow(1), l.school() + ":", d.school(), true);
        labelRow(t.getRow(2), l.teacher() + ":", d.teacher(), false);
        labelRow(t.getRow(3), l.date() + ":", d.date().format(DATE), false);

        // row 4: grade (two columns) | number present | absent
        XWPFTableRow r4 = t.getRow(4);
        text(span(r4, 0, 2, HEADER_COLS), l.cls() + ": " + nz(d.classes()), false);
        List<String[]> perClass = parseCounts(d.classCounts());
        if (perClass.size() > 1) {
            perClassLines(r4.getCell(1), l.present(), perClass, true);
            perClassLines(r4.getCell(2), l.absent(), perClass, false);
        } else {
            text(r4.getCell(1), l.present() + ": " + d.students(), false);
            text(r4.getCell(2), l.absent() + ":", false);
        }

        wideRow(t.getRow(5), l.topic(), d.topic(), true);
        wideRow(t.getRow(6), l.learningObjectives(), d.objectives(), true);

        List<String> objectives = new ArrayList<>();
        objectives.add(l.learnersWillBeAble());
        if (d.content().lessonObjectives() != null) {
            d.content().lessonObjectives().forEach(o -> objectives.add("- " + o));
        }
        wideRow(t.getRow(7), l.lessonObjectives(), String.join("\n", objectives), true);

        List<String> values = new ArrayList<>();
        if (d.content().values() != null) {
            for (LessonContent.ValueLink v : d.content().values()) {
                if (v == null || v.value() == null) continue;
                String name = v.value().title(d.lang());
                if (!"kk".equals(d.lang())) name += " (" + v.value().title("kk") + ")";
                values.add("-" + name + (v.action() == null || v.action().isBlank() ? "" : ": " + v.action().strip()));
            }
        }
        wideRow(t.getRow(8), l.valuesLabel(), String.join("\n", values), false);
    }

    /** Caption over the first two columns, value over the last two (School:, Teacher name:, Date:). */
    private static void labelRow(XWPFTableRow row, String caption, String value, boolean bold) {
        text(span(row, 0, 2, HEADER_COLS), caption, bold);
        text(span(row, 1, 2, HEADER_COLS), nz(value), false);
    }

    /** Bold caption in the first column, the value over the other three. */
    private static void wideRow(XWPFTableRow row, String caption, String value, boolean boldValue) {
        text(row.getCell(0), caption, true);
        text(span(row, 1, 3, HEADER_COLS), nz(value), boldValue);
    }

    /** "7 А=25;7 Б=24" into [["7 А", "25"], ["7 Б", "24"]]. */
    private static List<String[]> parseCounts(String counts) {
        List<String[]> out = new ArrayList<>();
        if (counts == null || counts.isBlank()) {
            return out;
        }
        for (String part : counts.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq > 0) {
                out.add(new String[] {part.substring(0, eq).strip(), part.substring(eq + 1).strip()});
            }
        }
        return out;
    }

    /** Caption, then one line per class: "7 А: 25" (present) or "7 А:" with nothing after it (absent). */
    private static void perClassLines(XWPFTableCell cell, String caption, List<String[]> classes,
                                      boolean withNumbers) {
        StringBuilder sb = new StringBuilder(caption).append(':');
        for (String[] c : classes) {
            sb.append('\n').append(c[0]).append(": ").append(withNumbers ? c[1] : "");
        }
        text(cell, sb.toString(), false);
    }

    // ---------- lesson procedure ----------

    private static void stages(XWPFDocument doc, Labels l, List<Stage> stageList, boolean termTest) {
        List<Stage> stages = stageList == null ? List.of() : stageList;
        XWPFTable t = table(doc, stages.size() + 1, STAGE_COLS, STAGE_BORDER);
        String[] heads = {l.stage(), l.teacherActions(), l.studentActions(), l.assessment(), l.resources()};
        XWPFTableRow head = t.getRow(0);
        head.setRepeatHeader(true);
        for (int i = 0; i < heads.length; i++) {
            text(head.getCell(i), heads[i], true);
        }
        for (int i = 0; i < stages.size(); i++) {
            Stage s = stages.get(i);
            XWPFTableRow row = t.getRow(i + 1);
            boolean last = i == stages.size() - 1;
            // always English, by position: "Beginning 5 min", "Middle 10 min", then only "8 min" for the next
            // Middle rows, "End 5 min" (how long the row takes, not an interval)
            String name = i == 0 ? "Beginning\n" : last ? "End\n" : i == 1 ? "Middle\n" : "";
            text(row.getCell(0), name + s.minutes() + " min", false);
            marked(row.getCell(1), s.teacherActions());
            if (last) {
                // the sample: the summary of the points covers the students' actions and the assessment
                XWPFTableCell merged = span(row, 2, 2, STAGE_COLS);
                marked(merged, s.studentActions() + (termTest ? "" : "\n" + l.scale()));
                marked(row.getCell(3), s.resources());
            } else {
                marked(row.getCell(2), s.studentActions());
                marked(row.getCell(3), s.assessment());
                marked(row.getCell(4), s.resources());
            }
        }
    }

    // ---------- helpers ----------

    private static XWPFTable table(XWPFDocument doc, int rows, int[] widths, String color) {
        XWPFTable t = doc.createTable(rows, widths.length);
        t.setTableAlignment(TableRowAlign.CENTER);
        t.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
        t.setWidth(String.valueOf(sum(widths)));
        t.setCellMargins(0, 108, 0, 108);
        t.setTopBorder(XWPFBorderType.SINGLE, 6, 0, color);
        t.setBottomBorder(XWPFBorderType.SINGLE, 6, 0, color);
        t.setLeftBorder(XWPFBorderType.SINGLE, 6, 0, color);
        t.setRightBorder(XWPFBorderType.SINGLE, 6, 0, color);
        t.setInsideHBorder(XWPFBorderType.SINGLE, 4, 0, color);
        t.setInsideVBorder(XWPFBorderType.SINGLE, 4, 0, color);
        CTTblGrid grid = t.getCTTbl().getTblGrid() != null ? t.getCTTbl().getTblGrid() : t.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
        for (XWPFTableRow r : t.getRows()) {
            for (int i = 0; i < widths.length; i++) {
                r.getCell(i).setWidth(String.valueOf(widths[i]));
            }
        }
        return t;
    }

    /**
     * Merges {@code count} grid columns starting at the cell with index {@code cell} (an index among the cells that
     * are left in the row after earlier merges) and returns the merged cell.
     */
    private static XWPFTableCell span(XWPFTableRow row, int cell, int count, int[] widths) {
        int gridStart = 0;
        for (int i = 0; i < cell; i++) {
            CTTcPr pr = row.getCell(i).getCTTc().getTcPr();
            gridStart += pr != null && pr.isSetGridSpan() ? pr.getGridSpan().getVal().intValue() : 1;
        }
        XWPFTableCell c = row.getCell(cell);
        int width = 0;
        for (int i = gridStart; i < gridStart + count; i++) width += widths[i];
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        pr.addNewGridSpan().setVal(BigInteger.valueOf(count));
        c.setWidth(String.valueOf(width));
        for (int i = 1; i < count; i++) {
            row.removeCell(cell + 1); // keeps the row's cell list in sync, removeTc on the XML left orphaned cells
        }
        return c;
    }

    /** Multi-line text, one paragraph per line; the exercise reference, ANSWERS and points lines are bold. */
    private static void marked(XWPFTableCell cell, String value) {
        String[] lines = (value == null ? "" : value).split("\\R");
        for (int i = 0; i < lines.length; i++) {
            XWPFParagraph p = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
            p.setSpacingAfter(0);
            Matcher ai = AI_PROMPT.matcher(lines[i]);
            if (ai.matches()) {
                run(p, ai.group(1) + " ", true, SIZE);
                link(p, "https://chatgpt.com/?q=" + URLEncoder.encode(ai.group(2), StandardCharsets.UTF_8)
                        .replace("+", "%20"), ai.group(2));
            } else {
                run(p, lines[i], BOLD_LINE.matcher(lines[i]).matches(), SIZE);
            }
        }
    }

    /** Writes multi-line text into the cell, one paragraph per line. */
    private static void text(XWPFTableCell cell, String value, boolean bold) {
        String[] lines = (value == null ? "" : value).split("\\R");
        for (int i = 0; i < lines.length; i++) {
            XWPFParagraph p = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
            p.setSpacingAfter(0);
            run(p, lines[i], bold, SIZE);
        }
    }

    private static void link(XWPFParagraph p, String url, String text) {
        XWPFHyperlinkRun r = p.createHyperlinkRun(url);
        r.setFontFamily(FONT);
        r.setFontSize(SIZE);
        r.setColor("0563C1");
        r.setUnderline(UnderlinePatterns.SINGLE);
        r.setText(text);
    }

    private static XWPFRun run(XWPFParagraph p, String text, boolean bold, int size) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(text);
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }
}
