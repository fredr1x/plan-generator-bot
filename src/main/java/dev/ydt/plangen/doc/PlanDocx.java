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
import java.util.List;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
 * Builds the short-term lesson plan: header grid as in the sample (section/school, date/teacher,
 * class/present/absent, topic, objectives) followed by the lesson procedure table. Black borders only.
 */
public final class PlanDocx {

    public record Data(String lang, String school, String teacher, LocalDate date, String classes,
                       int students, String section, String topic, String objectives,
                       LessonContent content) {}

    private static final String FONT = "Times New Roman";
    private static final int SIZE = 11;
    private static final String BLACK = "000000";
    private static final int[] HEADER_COLS = {3100, 3900, 3205};          // total 10205 twips (18 cm)
    private static final int[] STAGE_COLS = {1500, 2900, 2800, 1700, 1305};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] STAGE_NAMES = {"Beginning of the class", "Middle of the class", "End of the class"};

    private PlanDocx() {}

    public static void write(Data d, Path file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            Labels l = Labels.of(d.lang());
            page(doc);

            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            title.setSpacingAfter(160);
            run(title, l.title(), true, 13);

            header(doc, l, d);

            XWPFParagraph progress = doc.createParagraph();
            progress.setSpacingBefore(160);
            progress.setSpacingAfter(60);
            run(progress, l.progress(), true, SIZE);

            stages(doc, l, d.content().stages());
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
        mar.setLeft(BigInteger.valueOf(850));
        mar.setRight(BigInteger.valueOf(850));
        mar.setTop(BigInteger.valueOf(850));
        mar.setBottom(BigInteger.valueOf(850));
    }

    // ---------- header grid ----------

    private static void header(XWPFDocument doc, Labels l, Data d) {
        XWPFTable t = table(doc, 7, HEADER_COLS);

        // row 0: section | school (span 2)
        labelled(t.getRow(0).getCell(0), l.section(), d.section());
        XWPFTableCell c01 = span(t.getRow(0), 1, HEADER_COLS);
        labelled(c01, l.school(), d.school());

        // row 1: date | teacher (span 2)
        labelled(t.getRow(1).getCell(0), l.date(), d.date().format(DATE));
        XWPFTableCell c11 = span(t.getRow(1), 1, HEADER_COLS);
        labelled(c11, l.teacher(), d.teacher());

        // row 2: class | present | absent (left empty on purpose)
        labelled(t.getRow(2).getCell(0), l.cls(), d.classes());
        labelled(t.getRow(2).getCell(1), l.present(), String.valueOf(d.students()));
        labelled(t.getRow(2).getCell(2), l.absent(), "");

        // row 3: topic
        label(t.getRow(3).getCell(0), l.topic());
        text(span(t.getRow(3), 1, HEADER_COLS), d.topic(), false);

        // row 4: learning objectives exactly as the teacher typed them (codes)
        label(t.getRow(4).getCell(0), l.learningObjectives());
        text(span(t.getRow(4), 1, HEADER_COLS), d.objectives(), false);

        // row 5: value of the Birtutas tarbiye programme, the whole line in bold
        label(t.getRow(5).getCell(0), l.valuesLabel());
        XWPFTableCell valueCell = span(t.getRow(5), 1, HEADER_COLS);
        if (d.content().programValue() != null) {
            run(valueCell.getParagraphs().get(0),
                    l.valuesPrefix() + " - " + d.content().programValue().title(d.lang()), true, SIZE);
        }

        // row 6: lesson objectives, one heading and short dashed sentences
        label(t.getRow(6).getCell(0), l.lessonObjectives());
        dashed(span(t.getRow(6), 1, HEADER_COLS), l.learnersWillBeAble(), d.content().lessonObjectives());
    }

    /** Optional bold heading, then one "- item" paragraph per item. */
    private static void dashed(XWPFTableCell cell, String heading, List<String> items) {
        boolean first = true;
        if (heading != null) {
            XWPFParagraph h = cell.getParagraphs().get(0);
            h.setSpacingAfter(20);
            run(h, heading, true, SIZE);
            first = false;
        }
        if (items == null) {
            return;
        }
        for (String item : items) {
            XWPFParagraph p = first ? cell.getParagraphs().get(0) : cell.addParagraph();
            first = false;
            p.setSpacingAfter(20);
            run(p, "- " + item, false, SIZE);
        }
    }

    // ---------- lesson procedure ----------

    private static void stages(XWPFDocument doc, Labels l, List<Stage> stageList) {
        List<Stage> stages = stageList == null ? List.of() : stageList;
        XWPFTable t = table(doc, stages.size() + 1, STAGE_COLS);
        String[] heads = {l.stage(), l.teacherActions(), l.studentActions(), l.assessment(), l.resources()};
        XWPFTableRow head = t.getRow(0);
        head.setRepeatHeader(true);
        for (int i = 0; i < heads.length; i++) {
            XWPFParagraph p = head.getCell(i).getParagraphs().get(0);
            p.setAlignment(ParagraphAlignment.CENTER);
            run(p, heads[i], true, SIZE);
        }
        for (int i = 0; i < stages.size(); i++) {
            Stage s = stages.get(i);
            XWPFTableRow row = t.getRow(i + 1);
            // always English here: "Beginning of the class 10 min" (how long the stage takes, not an interval)
            String name = stages.size() == STAGE_NAMES.length ? STAGE_NAMES[i] : (s.name() == null ? "" : s.name());
            text(row.getCell(0), name + " " + s.minutes() + " min", false);
            text(row.getCell(1), s.teacherActions(), false);
            text(row.getCell(2), s.studentActions(), false);
            text(row.getCell(3), s.assessment(), false);
            text(row.getCell(4), s.resources(), false);
        }
    }

    // ---------- helpers ----------

    private static XWPFTable table(XWPFDocument doc, int rows, int[] widths) {
        XWPFTable t = doc.createTable(rows, widths.length);
        t.setTableAlignment(TableRowAlign.CENTER);
        t.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
        t.setWidth(String.valueOf(sum(widths)));
        t.setCellMargins(40, 90, 40, 90);
        t.setTopBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
        t.setBottomBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
        t.setLeftBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
        t.setRightBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
        t.setInsideHBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
        t.setInsideVBorder(XWPFBorderType.SINGLE, 6, 0, BLACK);
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

    /** Merges the cells from {@code from} to the end of the row into one cell and returns it. */
    private static XWPFTableCell span(XWPFTableRow row, int from, int[] widths) {
        XWPFTableCell cell = row.getCell(from);
        int count = widths.length - from;
        int width = 0;
        for (int i = from; i < widths.length; i++) width += widths[i];
        CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        pr.addNewGridSpan().setVal(BigInteger.valueOf(count));
        cell.setWidth(String.valueOf(width));
        for (int i = widths.length - 1; i > from; i--) {
            row.getCtRow().removeTc(i);
        }
        return cell;
    }

    private static void label(XWPFTableCell cell, String label) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        run(p, label, true, SIZE);
    }

    private static void labelled(XWPFTableCell cell, String label, String value) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        run(p, label + ": ", true, SIZE);
        if (value != null && !value.isBlank()) {
            run(p, value, false, SIZE);
        }
    }

    /** Writes multi-line text into the cell, one paragraph per line. */
    private static void text(XWPFTableCell cell, String value, boolean bold) {
        String[] lines = (value == null ? "" : value).split("\\R");
        for (int i = 0; i < lines.length; i++) {
            XWPFParagraph p = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
            p.setSpacingAfter(30);
            run(p, lines[i], bold, SIZE);
        }
    }

    private static XWPFRun run(XWPFParagraph p, String text, boolean bold, int size) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(text);
        return r;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }
}
