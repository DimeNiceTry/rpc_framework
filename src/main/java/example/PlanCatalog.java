package example;

import java.util.List;

public interface PlanCatalog {
    TrainingPlan findById(long id);
    List<TrainingPlan> findAll();
}
