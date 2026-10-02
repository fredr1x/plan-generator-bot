package dev.ydt.plangen.llm;

import dev.ydt.plangen.llm.LessonContent.Stage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlanService {

    private static final Logger LOG = Logger.getLogger(PlanService.class);

    /** previous: JSON of an earlier plan to be edited instead of writing a new one (null for a fresh plan). */
    public record Request(String subject, String lang, int minutes, String classes, int students,
                          String section, String topic, String objectives, String notes, String previous) {}

    @Inject
    LessonPlanner planner;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String model;

    public String model() {
        return model;
    }

    /** Calls the model, retries once if the structure or the timing is off, then repairs what is left. */
    public LessonContent generate(Request r) {
        LessonContent content = call(r, "none");
        String problem = validate(content, r.minutes());
        if (problem != null) {
            LOG.infof("first attempt rejected: %s", problem);
            content = call(r, problem);
            problem = validate(content, r.minutes());
            if (problem != null) {
                LOG.warnf("second attempt still off (%s), repairing locally", problem);
            }
        }
        return normalize(content, r.minutes());
    }

    private LessonContent call(Request r, String feedback) {
        return planner.plan(r.subject(), languageName(r.lang()), r.minutes(), r.classes(), r.students(),
                r.section(), r.topic(), r.objectives(), blankToNo(r.notes()), blankToNo(r.previous()), feedback);
    }

    static String languageName(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "kk" -> "Kazakh";
            case "ru" -> "Russian";
            default -> "English";
        };
    }

    private static String blankToNo(String s) {
        return s == null || s.isBlank() ? "none" : s.strip();
    }

    /** Returns a human-readable complaint for the model, or null when the content is acceptable. */
    static String validate(LessonContent c, int minutes) {
        if (c == null || c.stages() == null || c.stages().isEmpty()) {
            return "The list of stages is empty. Return the stages of the lesson procedure.";
        }
        if (c.stages().size() < 3 || c.stages().size() > 8) {
            return "There must be from 4 to 6 stages, but there are " + c.stages().size() + ".";
        }
        int sum = c.stages().stream().mapToInt(Stage::minutes).sum();
        if (sum != minutes) {
            return "The minutes of the stages add up to " + sum + " but must be exactly " + minutes
                    + ". Redistribute the time.";
        }
        if (isEmpty(c.allLearners()) || isEmpty(c.mostLearners()) || isEmpty(c.someLearners())) {
            return "Fill in the lesson objectives in all three groups: all, most, some.";
        }
        for (Stage s : c.stages()) {
            if (blank(s.teacherActions()) || blank(s.studentActions()) || blank(s.assessment())
                    || blank(s.resources()) || s.minutes() < 1) {
                return "Stage \"" + s.name() + "\" has empty fields or zero minutes. Fill in every field.";
            }
        }
        return null;
    }

    /** Cleans markdown leftovers and forces the minutes to add up exactly. */
    static LessonContent normalize(LessonContent c, int minutes) {
        List<Stage> stages = new ArrayList<>();
        for (Stage s : c.stages()) {
            stages.add(new Stage(clean(s.name()), Math.max(1, s.minutes()), clean(s.teacherActions()),
                    clean(s.studentActions()), clean(s.assessment()), clean(s.resources())));
        }
        int delta = minutes - stages.stream().mapToInt(Stage::minutes).sum();
        if (delta != 0 && !stages.isEmpty()) {
            int idx = 0;
            for (int i = 1; i < stages.size(); i++) {
                if (stages.get(i).minutes() > stages.get(idx).minutes()) idx = i;
            }
            Stage big = stages.get(idx);
            stages.set(idx, new Stage(big.name(), Math.max(1, big.minutes() + delta), big.teacherActions(),
                    big.studentActions(), big.assessment(), big.resources()));
        }
        return new LessonContent(cleanAll(c.allLearners()), cleanAll(c.mostLearners()),
                cleanAll(c.someLearners()), stages);
    }

    private static List<String> cleanAll(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                String t = clean(s);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("**", "").replace("__", "").replace("`", "")
                .replaceAll("(?m)^#+\\s*", "").strip();
    }

    private static boolean isEmpty(List<String> l) {
        return l == null || l.isEmpty() || l.stream().allMatch(PlanService::blank);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
