package dev.ydt.plangen.llm;

/**
 * The six values of the "Birtutas tarbiye" programme. The model picks one by name (the JSON schema turns
 * this enum into a closed list), the document prints the title in the plan language.
 */
public enum ProgramValue {
    INDEPENDENCE_AND_PATRIOTISM("Independence and Patriotism", "Независимость и патриотизм",
            "Тәуелсіздік және патриотизм"),
    UNITY_AND_SOLIDARITY("Unity and Solidarity", "Единство и солидарность", "Бірлік және ынтымақ"),
    JUSTICE_AND_RESPONSIBILITY("Justice and Responsibility", "Справедливость и ответственность",
            "Әділдік және жауапкершілік"),
    LAW_AND_ORDER("Law and Order", "Закон и порядок", "Заң және тәртіп"),
    HARD_WORK_AND_PROFESSIONALISM("Hard Work and Professionalism", "Трудолюбие и профессионализм",
            "Еңбекқорлық және кәсібилік"),
    CREATIVITY_AND_INNOVATION("Creativity and Innovation", "Креативность и инновации",
            "Креативтілік және инновация");

    private final String en;
    private final String ru;
    private final String kk;

    ProgramValue(String en, String ru, String kk) {
        this.en = en;
        this.ru = ru;
        this.kk = kk;
    }

    public String title(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "kk" -> kk;
            case "ru" -> ru;
            default -> en;
        };
    }
}
