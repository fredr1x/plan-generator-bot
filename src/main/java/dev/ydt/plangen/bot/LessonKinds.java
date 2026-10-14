package dev.ydt.plangen.bot;

import dev.ydt.plangen.domain.KtpLesson;

/** Extra instructions for special KTP lessons. They go to the model, so they are in English. */
final class LessonKinds {
    private LessonKinds() {}

    private static final String CHECKPOINT =
            "This is a summative assessment lesson (checkpoint) for the unit. About 20 minutes of the lesson is a "
                    + "written summative task covering the unit objectives with clear marking criteria; the rest is "
                    + "short preparation and feedback. Do not teach new material. Stage timings must still add up to "
                    + "the full lesson duration.";
    private static final String INTERIM =
            "This is an interim summative assessment lesson for the term. About 40 minutes of the lesson is the "
                    + "written term assessment (tasks on listening, reading, use of English and writing as the term "
                    + "objectives require) with clear marking criteria; keep the introduction and feedback very short. "
                    + "Do not teach new material. Stage timings must still add up to the full lesson duration.";
    private static final String SUMMATIVE =
            "This is a 20-minute lesson of the unit summative assessment (BZhB). 3-4 minutes for the organisational "
                    + "moment and instructions, about 13 minutes for the written summative task covering the unit "
                    + "objectives with the points and marking criteria, 3-4 minutes for self-check and feedback. "
                    + "Do not teach new material. Stage timings must add up to exactly 20 minutes.";
    private static final String PROJECT =
            "This is a project lesson: learners work in groups on the unit project and present it. Include group "
                    + "roles, a short success checklist and peer assessment.";
    private static final String CLIL =
            "This is a CLIL lesson: the language is learned through the content of another school subject. Name "
                    + "that content area in the aims and use content vocabulary tasks.";

    /** The teacher's notes with the instruction for the lesson kind added, or the notes as they are. */
    static String withKindNote(String notes, Long ktpLessonId, boolean summative) {
        if (ktpLessonId == null) {
            return summative ? join(notes, SUMMATIVE) : notes;
        }
        KtpLesson l = KtpLesson.findById(ktpLessonId);
        if (l != null && l.parts > 1) {
            String partNote = "This is lesson " + l.part + " of " + l.parts + " on the same KTP topic (the KTP gives the "
                    + "topic " + l.parts + " hours). " + (l.part == 1 ? "Introduce the topic and start the practice; "
                    + "the next lesson will continue it." : "Continue from the previous lesson(s) on this topic: do "
                    + "not repeat the introduction, give new practice, and " + (l.part == l.parts
                    ? "finish with consolidation and a short check of the whole topic." : "leave the final "
                    + "consolidation to the last lesson of the topic."));
            notes = notes == null || notes.isBlank() ? partNote : notes + "\n" + partNote;
        }
        String extra = l == null ? null : switch (l.kind == null ? "" : l.kind) {
            case "checkpoint" -> summative ? SUMMATIVE : CHECKPOINT;
            case "interim" -> summative ? SUMMATIVE : INTERIM;
            case "project" -> PROJECT;
            case "clil" -> CLIL;
            default -> null;
        };
        if (extra == null && summative) extra = SUMMATIVE;
        return extra == null ? notes : join(notes, extra);
    }

    private static String join(String notes, String extra) {
        return notes == null || notes.isBlank() ? extra : notes + "\n" + extra;
    }
}
