package dev.ydt.plangen.doc;

/** Captions of the plan template per language (en | ru | kk). */
public record Labels(
        String title,
        String section,
        String school,
        String teacher,
        String date,
        String cls,
        String present,
        String absent,
        String topic,
        String learningObjectives,
        String lessonObjectives,
        String learnersWillBeAble,
        String progress,
        String stage,
        String teacherActions,
        String studentActions,
        String assessment,
        String resources,
        String minutes) {

    public static Labels of(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "kk" -> KK;
            case "ru" -> RU;
            default -> EN;
        };
    }

    private static final Labels EN = new Labels(
            "Short-term (lesson) plan",
            "Unit / Section", "School", "Teacher's name", "Date", "Class",
            "Number present", "Absent",
            "Lesson topic",
            "Learning objectives",
            "Lesson objectives",
            "Learners will be able to:",
            "Lesson procedure",
            "Lesson stage / Time", "Teacher's actions", "Students' actions", "Assessment", "Resources",
            "min");

    private static final Labels RU = new Labels(
            "Краткосрочный (поурочный) план",
            "Раздел", "Школа", "Ф.И.О. педагога", "Дата", "Класс",
            "Количество присутствующих", "Количество отсутствующих",
            "Тема урока",
            "Цели обучения",
            "Цели урока",
            "Учащиеся смогут:",
            "Ход урока",
            "Этап урока / Время", "Действия педагога", "Действия ученика", "Оценивание", "Ресурсы",
            "мин");

    private static final Labels KK = new Labels(
            "Қысқа мерзімді (сабақ) жоспар",
            "Бөлім", "Мектеп", "Педагогтің Т.А.Ә.", "Күні", "Сынып",
            "Қатысқандар саны", "Қатыспағандар саны",
            "Сабақтың тақырыбы",
            "Оқыту мақсаттары",
            "Сабақтың мақсаттары",
            "Оқушылар орындай алады:",
            "Сабақтың барысы",
            "Сабақ кезеңі / Уақыты", "Педагогтің әрекеті", "Оқушының әрекеті", "Бағалау", "Ресурстар",
            "мин");
}
