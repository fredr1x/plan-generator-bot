package dev.ydt.plangen.service;

import dev.ydt.plangen.domain.Ktp;
import dev.ydt.plangen.domain.KtpClass;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpSlot;
import dev.ydt.plangen.domain.NonTeachingDay;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.SchoolTerm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Puts the lessons of a KTP on dates. A slot is a school day on which the class has a lesson in the timetable and
 * which is inside a term and not a day off. The lessons of a term fill the slots of that term in order; lessons
 * that do not fit move to the next term and are reported, free slots are a reserve.
 */
@ApplicationScoped
public class SlotService {

    private static final Logger LOG = Logger.getLogger(SlotService.class);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM");

    /** Hours needed in a term, slots available, hours that did not fit and the numbers of those lessons. */
    public record TermReport(int termNo, int need, int slots, int left, List<Integer> leftSeqs) {}

    public record Result(List<TermReport> terms, int placed, int unplaced, int perWeek, LocalDate first,
                         LocalDate last, boolean noSchedule, boolean noCalendar, boolean fromFile) {}

    public record Built(Ktp ktp, SchoolClass cls, Result result) {}

    @Inject
    TimeService time;

    /**
     * Builds the dates of one class. The first time they are placed from the first day of the school year, from
     * the first lesson on. Later (a change of the timetable or the calendar) lessons that are prepared, done or lie
     * in the past keep their slots and everything else is placed again from today.
     */
    public Result build(Ktp k, SchoolClass c, int startSeq) {
        LocalDate today = time.today();
        // all versions of the class's timetable; the version that is in force on a day decides its lessons
        List<ScheduleEntry> entries = ScheduleEntry.list("teacherId = ?1 and classId = ?2", c.teacherId, c.id);
        if (entries.isEmpty()) {
            // nothing is taught any more: the lessons that are still ahead lose their dates
            KtpSlot.delete("ktpId = ?1 and classId = ?2 and status = 'planned' and slotDate >= ?3", k.id, c.id, today);
            return new Result(List.of(), 0, 0, 0, null, null, true, false, false);
        }
        List<SchoolTerm> terms = yearTerms(today);
        if (terms.isEmpty()) {
            return new Result(List.of(), 0, 0, entries.size(), null, null, false, true, false);
        }
        List<KtpLesson> lessons = KtpLesson.ofKtp(k.id);
        List<KtpSlot> existing = KtpSlot.list("ktpId = ?1 and classId = ?2", k.id, c.id);
        LocalDate yearStart = terms.get(0).startsOn;
        LocalDate from = existing.isEmpty() || today.isBefore(yearStart) ? yearStart : today;
        Set<Long> fixed = new HashSet<>();
        Set<String> taken = new HashSet<>();
        for (KtpSlot other : KtpSlot.<KtpSlot>list("classId = ?1 and ktpId <> ?2", c.id, k.id)) {
            taken.add(key(other.slotDate, other.lessonNo));
        }
        for (KtpSlot s : existing) {
            boolean keep = !KtpSlot.PLANNED.equals(s.status) || s.slotDate.isBefore(from);
            if (keep) {
                taken.add(key(s.slotDate, s.lessonNo));
                if (!KtpSlot.SKIPPED.equals(s.status)) fixed.add(s.lessonId);
            }
        }
        KtpSlot.delete("ktpId = ?1 and classId = ?2 and status = 'planned' and slotDate >= ?3", k.id, c.id, from);
        KtpSlot.getEntityManager().flush();

        List<KtpLesson> queue = lessons.stream().filter(l -> l.seq >= startSeq && !fixed.contains(l.id)).toList();

        List<NonTeachingDay> off = NonTeachingDay.visible(c.teacherId, from).stream()
                .filter(d -> d.appliesTo(c.grade)).toList();
        List<LocalDate> versions = ScheduleEntry.versionDates(c.teacherId);
        java.util.Map<LocalDate, TreeMap<Integer, List<Integer>>> byVersion = new java.util.HashMap<>();
        for (ScheduleEntry e : entries) {
            byVersion.computeIfAbsent(e.validFrom, v -> new TreeMap<>())
                    .computeIfAbsent(e.weekday, w -> new ArrayList<>()).add(e.lessonNo);
        }
        byVersion.values().forEach(m -> m.values().forEach(l -> l.sort(null)));

        // dates written in the KTP file are used when every lesson has one (a lesson on a day without a lesson of
        // this class or a day off moves to the next free day, summative works never move), and they stay on later
        // recalculations
        if (!lessons.isEmpty() && lessons.stream().allMatch(l -> l.sourceDate != null)) {
            if (!existing.isEmpty()) {
                return new Result(List.of(), 0, 0, entries.size(), null, null, false, false, true);
            }
            java.util.function.Predicate<LocalDate> teaching = d -> terms.stream().anyMatch(t -> t.contains(d))
                    && !isOff(d, off) && !periodsOn(versions, byVersion, d).isEmpty();
            return fromFileDates(k, c, queue, lessons, entries, teaching, terms.get(terms.size() - 1).endsOn);
        }

        List<TermReport> report = new ArrayList<>();
        int placed = 0;
        int unplaced = 0;
        LocalDate first = null;
        LocalDate last = null;

        // the term of a lesson is the term the KTP gives it (the previous lesson's term when the cell is empty),
        // limited to the terms of the calendar year
        int firstTerm = terms.get(0).termNo;
        int lastTermNo = terms.get(terms.size() - 1).termNo;
        boolean hasTerms = lessons.stream().anyMatch(l -> l.termNo != null);
        java.util.Map<Long, Integer> termOf = new java.util.HashMap<>();
        int current = firstTerm;
        for (KtpLesson l : lessons) {
            if (l.termNo != null) current = l.termNo;
            termOf.put(l.id, Math.max(firstTerm, Math.min(lastTermNo, current)));
        }
        // without any term in the KTP there are no boundaries to respect: the lessons simply fill the year
        List<KtpLesson> carry = new ArrayList<>();
        int qi = 0;

        for (int ti = 0; ti < terms.size(); ti++) {
            SchoolTerm t = terms.get(ti);
            List<KtpLesson> mine = new ArrayList<>(carry);
            carry.clear();
            if (hasTerms) {
                for (KtpLesson l : queue) {
                    if (termOf.get(l.id) == t.termNo) mine.add(l);
                }
            } else {
                while (qi < queue.size()) mine.add(queue.get(qi++));
            }
            List<LocalDate> slotDay = new ArrayList<>();
            List<Integer> slotNo = new ArrayList<>();
            for (LocalDate d = t.startsOn.isBefore(from) ? from : t.startsOn; !d.isAfter(t.endsOn); d = d.plusDays(1)) {
                LocalDate version = null;
                for (LocalDate v : versions) {
                    if (!v.isAfter(d)) version = v;
                }
                TreeMap<Integer, List<Integer>> perDay = version == null ? null : byVersion.get(version);
                List<Integer> nos = perDay == null ? null : perDay.get(d.getDayOfWeek().getValue());
                if (nos == null || isOff(d, off)) continue;
                for (int no : nos) {
                    if (!taken.contains(key(d, no))) {
                        slotDay.add(d);
                        slotNo.add(no);
                    }
                }
            }
            int need = mine.stream().mapToInt(l -> Math.max(1, l.hours)).sum();
            int si = 0;
            int left = 0;
            List<Integer> leftSeqs = new ArrayList<>();
            for (KtpLesson l : mine) {
                if (si >= slotDay.size()) {
                    left += Math.max(1, l.hours);
                    leftSeqs.add(l.seq);
                    if (!hasTerms) carry.add(l);
                    else unplaced++;
                    continue;
                }
                KtpSlot s = new KtpSlot();
                s.ktpId = k.id;
                s.classId = c.id;
                s.lessonId = l.id;
                s.slotDate = slotDay.get(si);
                s.lessonNo = slotNo.get(si);
                s.persist();
                if (first == null) first = s.slotDate;
                last = s.slotDate;
                placed++;
                si += Math.max(1, l.hours);
            }
            // on a recalculation a term that is over is not reported again (the lessons it lost stay without date)
            if (existing.isEmpty() || !t.endsOn.isBefore(today)) {
                report.add(new TermReport(t.termNo, need, slotDay.size(), hasTerms ? left : 0,
                        hasTerms ? leftSeqs : List.of()));
            }
        }
        unplaced += carry.size();
        LOG.infof("[slots] KTP #%d class %s: %d lessons placed, %d without a slot", k.id, c.label(), placed, unplaced);
        return new Result(report, placed, unplaced, entries.size(), first, last, false, false, false);
    }

    private Result fromFileDates(Ktp k, SchoolClass c, List<KtpLesson> queue, List<KtpLesson> all,
                                 List<ScheduleEntry> entries, java.util.function.Predicate<LocalDate> teaching,
                                 LocalDate lastDay) {
        List<LocalDate> fileDates = all.stream().map(l -> l.sourceDate).toList();
        List<Boolean> fixedDates = all.stream().map(l -> "checkpoint".equals(l.kind) || "interim".equals(l.kind)).toList();
        LocalDate[] planned = planFileDates(fileDates, fixedDates, teaching, lastDay);
        java.util.Map<Long, LocalDate> dateOf = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) dateOf.put(all.get(i).id, planned[i]);

        int placed = 0;
        LocalDate first = null;
        LocalDate last = null;
        for (KtpLesson l : queue) {
            LocalDate date = dateOf.get(l.id);
            if (date == null) continue; // no free day left before the end of the year
            // the lesson number comes from the timetable that is in force that day. Two lessons on one day (a lesson
            // moved from a holiday) get the class's periods one after the other, and when there are no more the
            // first period that the teacher does not teach that day.
            int wd = date.getDayOfWeek().getValue();
            List<ScheduleEntry> day = ScheduleEntry.forDay(c.teacherId, wd, date);
            int no = day.stream().filter(e -> e.classId == c.id).map(e -> e.lessonNo).sorted()
                    .filter(n -> !periodTaken(c.id, date, n)).findFirst().orElse(-1);
            if (no < 0) {
                Set<Integer> busy = day.stream().map(e -> e.lessonNo).collect(Collectors.toSet());
                no = 1;
                while (busy.contains(no) || periodTaken(c.id, date, no)) no++;
            }
            KtpSlot s = new KtpSlot();
            s.ktpId = k.id;
            s.classId = c.id;
            s.lessonId = l.id;
            s.slotDate = date;
            s.lessonNo = no;
            s.persist();
            placed++;
            if (first == null || date.isBefore(first)) first = date;
            if (last == null || date.isAfter(last)) last = date;
        }
        return new Result(List.of(), placed, queue.size() - placed, entries.size(), first, last, false, false, true);
    }

    /**
     * Dates for lessons that have the date of the KTP file. A lesson on a teaching day keeps its date, also when
     * two lessons share the day (the teacher's own decision). A lesson on a day that is not a teaching day (a day
     * off, the vacation, a weekday without a lesson of the class) moves to the next teaching day that has no lesson
     * yet; with no such day left before lastDay it has no date. Fixed lessons (the summative works) never move.
     */
    public static LocalDate[] planFileDates(List<LocalDate> dates, List<Boolean> fixed,
                                            java.util.function.Predicate<LocalDate> teaching, LocalDate lastDay) {
        int n = dates.size();
        LocalDate[] out = new LocalDate[n];
        Set<LocalDate> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = dates.get(i);
            if (d != null && (fixed.get(i) || teaching.test(d))) {
                out[i] = d;
                used.add(d);
            }
        }
        for (int i = 0; i < n; i++) {
            if (out[i] != null || dates.get(i) == null) continue;
            for (LocalDate d = dates.get(i).plusDays(1); !d.isAfter(lastDay); d = d.plusDays(1)) {
                if (teaching.test(d) && !used.contains(d)) {
                    out[i] = d;
                    used.add(d);
                    break;
                }
            }
        }
        return out;
    }

    /** The lesson numbers of the class on a day in the timetable that is in force then (empty when none). */
    private static List<Integer> periodsOn(List<LocalDate> versions,
                                           java.util.Map<LocalDate, TreeMap<Integer, List<Integer>>> byVersion,
                                           LocalDate d) {
        LocalDate version = null;
        for (LocalDate v : versions) {
            if (!v.isAfter(d)) version = v;
        }
        TreeMap<Integer, List<Integer>> perDay = version == null ? null : byVersion.get(version);
        List<Integer> nos = perDay == null ? null : perDay.get(d.getDayOfWeek().getValue());
        return nos == null ? List.of() : nos;
    }

    private static boolean periodTaken(long classId, LocalDate date, int no) {
        return KtpSlot.count("classId = ?1 and slotDate = ?2 and lessonNo = ?3", classId, date, no) > 0;
    }

    /** Terms of the school year that is going on (or the next one), without the terms that are over. */
    private static List<SchoolTerm> yearTerms(LocalDate today) {
        List<SchoolTerm> out = new ArrayList<>();
        String year = null;
        for (SchoolTerm t : SchoolTerm.all()) {
            if (year == null && t.endsOn.isBefore(today)) {
                // a term that is over belongs to the year that is going on only when a later term of the same year exists
                boolean sameYearLater = SchoolTerm.all().stream()
                        .anyMatch(x -> x.yearName.equals(t.yearName) && !x.endsOn.isBefore(today));
                if (!sameYearLater) continue;
            }
            if (year == null) year = t.yearName;
            if (year.equals(t.yearName)) out.add(t);
        }
        return out;
    }

    private static boolean isOff(LocalDate d, List<NonTeachingDay> off) {
        for (NonTeachingDay n : off) {
            if (n.covers(d)) return true;
        }
        return false;
    }

    private static String key(LocalDate d, int no) {
        return d + "#" + no;
    }

    // ------------------------------------------------------------------ whole teacher

    /** First lesson that has a slot: where the dates of this class started. */
    private int startSeqOf(Ktp k, SchoolClass c) {
        List<KtpSlot> rows = KtpSlot.list("ktpId = ?1 and classId = ?2", k.id, c.id);
        if (rows.isEmpty()) return 1;
        Set<Long> ids = rows.stream().map(s -> s.lessonId).collect(Collectors.toSet());
        return KtpLesson.<KtpLesson>list("ktpId", k.id).stream().filter(l -> ids.contains(l.id))
                .mapToInt(l -> l.seq).min().orElse(1);
    }

    /** Places the dates again for every KTP class of the teacher (after a change of the timetable or calendar). */
    public List<Built> rebuildAll(long teacherId) {
        List<Built> out = new ArrayList<>();
        for (Ktp k : Ktp.forTeacher(teacherId)) {
            for (KtpClass link : KtpClass.ofKtp(k.id)) {
                SchoolClass c = SchoolClass.findById(link.classId);
                if (c == null || KtpSlot.count("ktpId = ?1 and classId = ?2", k.id, c.id) == 0) continue;
                out.add(new Built(k, c, build(k, c, startSeqOf(k, c))));
            }
        }
        return out;
    }

    /** The lessons were not held: their slots are dropped and the rest moves forward. */
    public List<Built> skip(long teacherId, List<Long> slotIds) {
        Set<String> done = new HashSet<>();
        List<Built> out = new ArrayList<>();
        for (Long id : slotIds) {
            KtpSlot s = KtpSlot.findById(id);
            if (s == null) continue;
            s.status = KtpSlot.SKIPPED;
            KtpSlot.getEntityManager().flush();
            Ktp k = Ktp.findById(s.ktpId);
            SchoolClass c = SchoolClass.findById(s.classId);
            if (k == null || c == null || k.teacherId != teacherId || !done.add(k.id + "/" + c.id)) continue;
            out.add(new Built(k, c, build(k, c, startSeqOf(k, c))));
        }
        return out;
    }

    /** The first day after the given one on which the teacher has KTP lessons, or empty within three weeks. */
    public Optional<LocalDate> nextLessonDay(long teacherId, LocalDate after) {
        List<KtpSlot> rows = KtpSlot.live(teacherId, after.plusDays(1), after.plusDays(21));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).slotDate);
    }

    // ------------------------------------------------------------------ texts

    public static String describe(Built b) {
        Result r = b.result();
        String label = b.cls().label();
        if (r.noSchedule()) {
            return label + ": нет уроков в расписании, даты не расставлены. Заполните /schedule.";
        }
        if (r.noCalendar()) {
            return label + ": в календаре нет текущего учебного года, даты не расставлены.";
        }
        StringBuilder sb = new StringBuilder("📅 ").append(label).append(": ");
        if (r.placed() == 0) {
            if (r.fromFile()) {
                sb.append("даты взяты из файла КТП и не меняются.");
            } else if (r.unplaced() > 0) {
                sb.append("свободных уроков до конца года не нашлось, без даты осталось ").append(r.unplaced())
                        .append(" уроков. Проверьте /schedule и /calendar.");
            } else {
                sb.append("все уроки уже проведены или подготовлены, расставлять нечего.");
            }
            return sb.toString();
        }
        sb.append(r.placed()).append(" уроков по датам, с ").append(r.first().format(SHORT)).append(" до ")
                .append(r.last().format(SHORT)).append(r.fromFile() ? " (даты из файла КТП)" : "").append(".");
        for (TermReport t : r.terms()) {
            if (t.left() > 0) {
                sb.append("\n⚠️ ").append(t.termNo()).append(" четверть: по КТП ").append(t.need()).append(" ч., слотов ")
                        .append(t.slots()).append(". Не поместилось ").append(t.left()).append(" ч. (уроки КТП № ")
                        .append(ranges(t.leftSeqs())).append("): они остаются без даты и в следующую четверть не "
                        + "переносятся.");
            } else if (t.need() > 0 && t.slots() - t.need() > 0) {
                sb.append("\nℹ️ ").append(t.termNo()).append(" четверть: резерв ").append(t.slots() - t.need())
                        .append(" (повторение).");
            }
        }
        if (r.terms().stream().anyMatch(t -> t.left() > 0)) {
            sb.append("\nЧто можно сделать: добавить урок в другой день в /schedule (бот пересчитает даты и уроки "
                    + "встанут), объединить два соседних урока в КТП или оставить как есть.");
        }
        if (r.unplaced() > 0) {
            sb.append("\nВсего без даты: ").append(r.unplaced()).append(" уроков.");
        }
        return sb.toString();
    }

    /** 14, 15, 16, 20 becomes "14–16, 20". */
    static String ranges(List<Integer> seqs) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < seqs.size()) {
            int a = seqs.get(i);
            int b = a;
            while (i + 1 < seqs.size() && seqs.get(i + 1) == b + 1) {
                b = seqs.get(++i);
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(a == b ? String.valueOf(a) : a + "–" + b);
            i++;
        }
        return sb.toString();
    }

    public static String describeAll(List<Built> built) {
        return built.stream().map(SlotService::describe).collect(Collectors.joining("\n\n"));
    }

    /** Parses a date typed as dd.MM or dd.MM.yyyy; used by the calendar. */
    public static Optional<LocalDate> date(String s, LocalDate today) {
        try {
            String[] p = s.strip().split("\\.");
            int year = p.length > 2 ? Integer.parseInt(p[2]) : today.getYear();
            if (year < 100) year += 2000;
            LocalDate d = LocalDate.of(year, Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            if (p.length <= 2 && d.isBefore(today.minusMonths(6))) d = d.plusYears(1);
            return Optional.of(d);
        } catch (DateTimeException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }
}
