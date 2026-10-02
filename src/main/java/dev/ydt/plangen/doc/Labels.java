package dev.ydt.plangen.doc;

/** Captions of the plan template per language (ru | kk | en). */
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
        String allLearners,
        String mostLearners,
        String someLearners,
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

    private static final Labels RU = new Labels(
            "Краткосрочный (поурочный) план",
            "Раздел", "Школа", "Ф.И.О. педагога", "Дата", "Класс",
            "Количество присутствующих", "Количество отсутствующих",
            "Тема урока",
            "Цели обучения в соответствии с учебной программой",
            "Цели урока",
            "Все учащиеся смогут:", "Большинство учащихся смогут:", "Некоторые учащиеся смогут:",
            "Ход урока",
            "Этап урока / Время", "Действия педагога", "Действия ученика", "Оценивание", "Ресурсы",
            "мин");

    private static final Labels KK = new Labels(
            "Қысқа мерзімді (сабақ) жоспар",
            "Бөлім", "Мектеп", "Педагогтің Т.А.Ә.", "Күні", "Сынып",
            "Қатысқандар саны", "Қатыспағандар саны",
            "Сабақтың тақырыбы",
            "Оқу бағдарламасына сәйкес оқыту мақсаттары",
            "Сабақтың мақсаттары",
            "Барлық оқушылар орындай алады:", "Оқушылардың басым бөлігі орындай алады:",
            "Кейбір оқушылар орындай алады:",
            "Сабақтың барысы",
            "Сабақ кезеңі / Уақыты", "Педагогтің әрекеті", "Оқушының әрекеті", "Бағалау", "Ресурстар",
            "мин");

    private static final Labels EN = new Labels(
            "Short-term (lesson) plan",
            "Unit / Section", "School", "Teacher's name", "Date", "Class",
            "Number present", "Absent",
            "Lesson topic",
            "Learning objectives that this lesson is contributing to",
            "Lesson objectives",
            "All learners will be able to:", "Most learners will be able to:", "Some learners will be able to:",
            "Lesson procedure",
            "Lesson stage / Time", "Teacher's actions", "Students' actions", "Assessment", "Resources",
            "min");
}
