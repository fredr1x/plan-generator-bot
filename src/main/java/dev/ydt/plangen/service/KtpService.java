package dev.ydt.plangen.service;

import dev.ydt.plangen.domain.Ktp;
import dev.ydt.plangen.domain.KtpClass;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpTerm;
import dev.ydt.plangen.ktp.KtpParser;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class KtpService {

    /** A unit with the range of its lessons, e.g. "Unit 1: All about me", lessons 1-8. */
    public record UnitInfo(String title, int firstSeq, int lastSeq, int count) {}

    /** Saves a parsed KTP for the teacher; a plan of the same grade that was saved before is replaced. */
    public Ktp save(long teacherId, String subject, KtpParser.Parsed p, int grade, String sourceName,
                    List<Long> classIds) {
        Ktp.delete("teacherId = ?1 and grade = ?2", teacherId, grade);
        Ktp.getEntityManager().flush();

        Ktp k = new Ktp();
        k.teacherId = teacherId;
        k.subject = subject;
        k.grade = grade;
        k.schoolYear = p.schoolYear;
        k.title = p.title;
        k.sourceName = sourceName;
        k.totalHours = p.totalHours();
        k.persist();

        for (KtpParser.Term t : p.terms) {
            KtpTerm term = new KtpTerm();
            term.ktpId = k.id;
            term.termNo = t.no;
            term.hours = t.hours;
            term.persist();
        }
        for (KtpParser.Lesson l : p.lessons) {
            KtpLesson e = new KtpLesson();
            e.ktpId = k.id;
            e.seq = l.seq;
            e.termNo = l.term;
            e.unitTitle = l.unit;
            e.topic = l.topic;
            e.objectives = l.objectives;
            e.codes = l.codes;
            e.hours = l.hours;
            e.kind = l.kind;
            e.sourceDate = l.date == null ? null : LocalDate.parse(l.date);
            e.persist();
        }
        for (Long classId : classIds) {
            KtpClass link = new KtpClass();
            link.ktpId = k.id;
            link.classId = classId;
            link.persist();
        }
        return k;
    }

    /** Consecutive lessons of the same unit become one entry. */
    public static List<UnitInfo> units(List<String> unitPerLesson) {
        List<UnitInfo> out = new ArrayList<>();
        String current = null;
        int first = 0;
        int count = 0;
        for (int i = 0; i < unitPerLesson.size(); i++) {
            String u = unitPerLesson.get(i) == null || unitPerLesson.get(i).isBlank() ? "(без юнита)"
                    : unitPerLesson.get(i);
            if (!u.equals(current)) {
                if (current != null) out.add(new UnitInfo(current, first, i, count));
                current = u;
                first = i + 1;
                count = 0;
            }
            count++;
        }
        if (current != null) out.add(new UnitInfo(current, first, unitPerLesson.size(), count));
        return out;
    }
}
