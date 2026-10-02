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
}
