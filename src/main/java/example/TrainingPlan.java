package example;

import java.util.List;
import java.util.Map;

public record TrainingPlan(long id, String name, Map<String, List<Exercise>> schedule) {}
