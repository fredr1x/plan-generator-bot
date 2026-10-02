package dev.ydt.plangen.llm;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
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

    private static final int MAX_ATTEMPTS = 2;
    private static final int STAGES = 3;

    /** previous: JSON of an earlier plan to be edited instead of writing a new one (null for a fresh plan). */
    public record Request(String subject, String lang, int minutes, String classes, int students,
                          String section, String topic, String objectives, String notes, String previous) {}

    /** The plan plus what it cost: tokens over all attempts and wall-clock time. */
    public record Generated(LessonContent content, int tokensIn, int tokensOut, int attempts, long millis) {}

    @Inject
    LessonPlanner planner;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String model;

    @ConfigProperty(name = "app.pricing.input-per-million")
    double priceIn;

    @ConfigProperty(name = "app.pricing.output-per-million")
    double priceOut;

    public String model() {
        return model;
    }

    /** Rough price in USD from the configured per-million rates (an estimate, not a bill). */
    public double estimateCost(long tokensIn, long tokensOut) {
        return tokensIn * priceIn / 1_000_000.0 + tokensOut * priceOut / 1_000_000.0;
    }

    /**
     * Calls the model and retries once with a remark if the structure or the timing is off. A plan that
     * is still unusable after that (empty fields) is an error, never a half-empty document.
     */
    public Generated generate(Request r) {
        long started = System.nanoTime();
        int tokensIn = 0;
        int tokensOut = 0;
        int attempts = 0;
        LessonContent content = null;
        String feedback = "none";

        LOG.infof("LLM request: model=%s lang=%s minutes=%d classes=\"%s\" students=%d topic=\"%s\" revision=%s notes=%s",
                model, r.lang(), r.minutes(), r.classes(), r.students(), clip(r.topic(), 80),
                r.previous() != null, r.notes() == null || r.notes().isBlank() ? "-" : "yes");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts = attempt;
            long t0 = System.nanoTime();
            Result<LessonContent> result = planner.plan(r.subject(), languageName(r.lang()), r.minutes(),
                    r.classes(), r.students(), r.section(), r.topic(), r.objectives(), blankToNo(r.notes()),
                    blankToNo(r.previous()), feedback);
            TokenUsage usage = result.tokenUsage();
            int in = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            int out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            tokensIn += in;
            tokensOut += out;
            content = result.content();
            String problem = validate(content, r.minutes());
            LOG.infof("LLM attempt %d/%d: %d ms, tokens in=%d out=%d, finish=%s, check=%s",
                    attempt, MAX_ATTEMPTS, (System.nanoTime() - t0) / 1_000_000, in, out, result.finishReason(),
                    problem == null ? "ok" : "REJECTED (" + problem + ")");
            if (problem == null) {
                break;
            }
            feedback = problem;
        }

        String unusable = unusable(content);
        if (unusable != null) {
            throw new IllegalStateException("The model returned an unusable plan after " + attempts
                    + " attempts: " + unusable);
        }
        LessonContent fixed = normalize(content, r.minutes());
        long millis = (System.nanoTime() - started) / 1_000_000;
        LOG.infof("LLM done: attempts=%d, total tokens in=%d out=%d, est. cost=$%.4f, %d ms",
                attempts, tokensIn, tokensOut, estimateCost(tokensIn, tokensOut), millis);
        return new Generated(fixed, tokensIn, tokensOut, attempts, millis);
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

    private static String clip(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    /** A complaint for the model (used as the remark of the next attempt), or null when the plan is fine. */
    static String validate(LessonContent c, int minutes) {
        String empty = unusable(c);
        if (empty != null) {
            return empty;
        }
        if (c.stages().size() != STAGES) {
            return "There must be exactly " + STAGES + " stages (beginning, middle, end of the class), but there are "
                    + c.stages().size() + ".";
        }
        int sum = c.stages().stream().mapToInt(Stage::minutes).sum();
        if (sum != minutes) {
            return "The minutes of the stages add up to " + sum + " but must be exactly " + minutes
                    + ". Redistribute the time.";
        }
        return null;
    }

    /** Hard problems that no local repair can fix: missing parts or empty fields. */
    static String unusable(LessonContent c) {
        if (c == null || c.stages() == null || c.stages().isEmpty()) {
            return "The list of stages is empty. Return the stages of the lesson procedure.";
        }
        if (isEmpty(c.lessonObjectives())) {
            return "lessonObjectives is empty. Write 2-3 lesson objectives.";
        }
        if (isEmpty(c.assessmentCriteria())) {
            return "assessmentCriteria is empty. Write 2-4 assessment criteria.";
        }
        for (Stage s : c.stages()) {
            if (blank(s.name()) || blank(s.teacherActions()) || blank(s.studentActions()) || blank(s.assessment())
                    || blank(s.resources()) || s.minutes() < 1) {
                return "Stage \"" + s.name() + "\" has empty fields or zero minutes. Fill in every field.";
            }
        }
        return null;
    }

    /** Cleans markdown and bullet leftovers and forces the minutes to add up exactly. */
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
        return new LessonContent(cleanItems(c.assessmentCriteria()), cleanItems(c.lessonObjectives()), stages);
    }

    private static List<String> cleanItems(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                String t = clean(s).replaceFirst("^[-•*–]+\\s*", "");
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
