package example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DemoPlans {
    static List<TrainingPlan> create() {
        Map<String, List<Exercise>> first = new LinkedHashMap<>();
        first.put("День 1", List.of(
                new Exercise("Присед", 3, 5, 60.0),
                new Exercise("Жим лёжа", 3, 5, 40.0),
                new Exercise("Тяга горизонтального блока", 3, 10, null)));
        first.put("День 2", List.of(
                new Exercise("Становая тяга", 3, 5, 80.0),
                new Exercise("Жим стоя", 3, 8, null),
                new Exercise("Сгибание ног", 3, 10, null)));
        first.put("День 3", List.of(
                new Exercise("Присед с паузой", 3, 4, 50.0),
                new Exercise("Жим узким хватом", 3, 6, 35.0),
                new Exercise("Гиперэкстензия", 3, 12, null)));

        Map<String, List<Exercise>> second = new LinkedHashMap<>();
        second.put("День 1", List.of(
                new Exercise("Присед", 4, 4, 65.0),
                new Exercise("Жим лёжа", 4, 4, 45.0),
                new Exercise("Тяга верхнего блока", 3, 10, null)));
        second.put("День 2", List.of(
                new Exercise("Становая тяга", 4, 4, 85.0),
                new Exercise("Жим гантелей сидя", 3, 8, null),
                new Exercise("Сгибание ног", 3, 12, null)));
        second.put("День 3", List.of(
                new Exercise("Присед с паузой", 4, 3, 55.0),
                new Exercise("Жим узким хватом", 4, 5, 40.0),
                new Exercise("Гиперэкстензия", 3, 15, null)));

        return List.of(new TrainingPlan(1, "Тренировочный план A", first),
                new TrainingPlan(2, "Тренировочный план B", second));
    }
}
