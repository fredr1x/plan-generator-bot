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
        String valuesLabel,
        String valuesPrefix,
        String lessonObjectives,
        String learnersWillBeAble,
        String progress,
        String stage,
        String teacherActions,
        String studentActions,
        String assessment,
        String resources,
        String minutes,
        String scale) {

    public static Labels of(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "kk" -> KK;
            case "ru" -> RU;
            default -> EN;
        };
    }

    private static final Labels EN = new Labels(
            "Short term plan",
            "Unit / Section", "School", "Teacher's name", "Date", "Class",
            "Number present", "Absent",
            "Lesson topic",
            "Learning objectives",
            "Instilling values of the program \"Birtutas tarbiye\"",
            "Values of the program \"Birtutas tarbiye\"",
            "Lesson objectives (assessment criteria)",
            "Learners will be able to:",
            "Lesson procedure",
            "Stages / Time", "Teacher's actions", "Students' actions", "Assessment", "Resources",
            "min",
            "Total: 10 points\n9-10 points \u2013 \u201c5\u201d\n7-8 points \u2013 \u201c4\u201d\n5-6 points \u2013 \u201c3\u201d");

    private static final Labels RU = new Labels(
            "Краткосрочный (поурочный) план",
            "Раздел", "Школа", "Ф.И.О. педагога", "Дата", "Класс",
            "Количество присутствующих", "Количество отсутствующих",
            "Тема урока",
            "Цели обучения",
            "Привитие ценностей программы «Біртұтас тәрбие»",
            "Ценности программы «Біртұтас тәрбие»",
            "Цели урока (критерии оценивания)",
            "Учащиеся смогут:",
            "Ход урока",
            "Этап урока / Время", "Действия педагога", "Действия ученика", "Оценивание", "Ресурсы",
            "мин",
            "Всего: 10 баллов\n9-10 баллов \u2013 \u00ab5\u00bb\n7-8 баллов \u2013 \u00ab4\u00bb\n5-6 баллов \u2013 \u00ab3\u00bb");

    private static final Labels KK = new Labels(
            "Қысқа мерзімді (сабақ) жоспар",
            "Бөлім", "Мектеп", "Педагогтің Т.А.Ә.", "Күні", "Сынып",
            "Қатысқандар саны", "Қатыспағандар саны",
            "Сабақтың тақырыбы",
            "Оқыту мақсаттары",
            "«Біртұтас тәрбие» бағдарламасының құндылықтарын дарыту",
            "«Біртұтас тәрбие» бағдарламасының құндылықтары",
            "Сабақтың мақсаттары (бағалау критерийлері)",
            "Оқушылар орындай алады:",
            "Сабақтың барысы",
            "Сабақ кезеңі / Уақыты", "Педагогтің әрекеті", "Оқушының әрекеті", "Бағалау", "Ресурстар",
            "мин",
            "Барлығы: 10 балл\n9-10 балл \u2013 \u00ab5\u00bb\n7-8 балл \u2013 \u00ab4\u00bb\n5-6 балл \u2013 \u00ab3\u00bb");
}
