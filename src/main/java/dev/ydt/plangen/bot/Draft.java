package dev.ydt.plangen.bot;

import java.util.ArrayList;
import java.util.List;

/** Lesson data collected step by step; serialised into conv_state.payload. */
public class Draft {
    /** ISO date of the lesson. */
    public String date;
    public List<Long> classIds = new ArrayList<>();
    public String section;
    public String topic;
    public String objectives;
    /** null = not asked yet, empty = skipped. */
    public String notes;
    /** Set when the teacher reuses a finished lesson for another class or date. */
    public Long sourcePlanId;
    /** KTP step: asked once; ktpLessonId is the KTP lesson the section, topic and objectives were taken from. */
    public boolean ktpAsked;
    public Long ktpLessonId;
    /** Textbook step: asked once; the lists stay empty when the teacher skipped it. */
    public boolean bookAsked;
    /** Lesson length chosen for this plan; null = the teacher's default from the profile. */
    public Integer minutes;
    /** Unit summative assessment (BZhB): the lesson is 20 minutes and the plan is built around the written task. */
    public boolean summative;
    /** Term summative assessment (TZhB / SOCh): 3 + 40 + 2 minutes, the tasks are sent by the teacher as text. */
    public boolean termTest;
    public String testText;
    /** Books with their pages, in the order they were added (student's book first, workbook after it). */
    public List<BookRef> books = new ArrayList<>();
    /** The book whose pages are being asked right now. */
    public Long pendingBook;

    /** A book and the pages used from it, e.g. "45-46". */
    public static class BookRef {
        public Long bookId;
        public String pages;

        public BookRef() {}

        public BookRef(Long bookId, String pages) {
            this.bookId = bookId;
            this.pages = pages;
        }
    }
}
