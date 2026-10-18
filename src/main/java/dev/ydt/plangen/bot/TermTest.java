package dev.ydt.plangen.bot;

import dev.ydt.plangen.llm.LessonContent;
import dev.ydt.plangen.llm.LessonContent.Stage;
import java.util.List;

/** The plan of the term summative assessment (TZhB): 3 + 40 + 2 minutes, the tasks are the teacher's own text. */
final class TermTest {
    private TermTest() {}

    private static final String[] NAMES = {"Beginning of the class", "Middle of the class", "End of the class"};

    static LessonContent content(String lang, String tasks) {
        String[][] t = switch (lang == null ? "en" : lang) {
            case "kk" -> new String[][] {
                {"Амандасу, оқушыларды орналастыру, ТЖБ ережелері, уақыты және тапсырмалар реті туралы айту.",
                        "Орындарына отырады, нұсқаулық пен критерийлерді тыңдайды.", "-",
                        "Жұмыс парақтары, қаламдар"},
                {null, "Тапсырмаларды орындайды.", "ТЖБ бағалау критерийлері бойынша.",
                        "ТЖБ тапсырмалары, тыңдалым аудиожазбасы"},
                {"Жұмыстарды жинау, оқушыларға алғыс айту, нәтиже қашан белгілі болатынын хабарлау.",
                        "Жұмыстарын тапсырады.", "-", "-"},
                {"Тоқсандық жиынтық бағалау тапсырмаларын орындау: тыңдалым, айтылым, оқылым, жазылым"}};
            case "ru" -> new String[][] {
                {"Приветствие, рассадка, правила СОЧ, время и порядок выполнения заданий.",
                        "Занимают места, слушают инструкцию и критерии.", "-", "Листы с заданиями, ручки"},
                {null, "Выполняют задания.", "По критериям оценивания СОЧ.",
                        "Задания СОЧ, аудиозапись для аудирования"},
                {"Сбор работ, благодарность классу, сообщение о сроках результатов.", "Сдают работы.", "-", "-"},
                {"Выполнить задания суммативного оценивания за четверть: аудирование, говорение, чтение, письмо"}};
            default -> new String[][] {
                {"Greeting, seating, rules of the assessment, time and the order of the tasks.",
                        "Take their places, listen to the instructions and the criteria.", "-",
                        "Assessment papers, pens"},
                {null, "Complete the tasks.", "According to the marking criteria of the term assessment.",
                        "Term assessment tasks, audio for the listening"},
                {"Collect the papers, thank the class, say when the results will be known.",
                        "Hand in the papers.", "-", "-"},
                {"Complete the term summative assessment tasks: listening, speaking, reading, writing"}};
        };
        int[] minutes = {3, 40, 2};
        List<Stage> stages = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stages.add(new Stage(NAMES[i], minutes[i], t[i][0] == null ? tasks : t[i][0], t[i][1], t[i][2], t[i][3]));
        }
        return new LessonContent(List.of(t[3][0]), stages, null);
    }
}
