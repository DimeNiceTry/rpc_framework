package example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlanServiceImpl implements PlanService {
    private final Map<Long, TrainingPlan> plans = new LinkedHashMap<>();

    public PlanServiceImpl() {
        saveAll(DemoPlans.create());
    }

    @Override
    public TrainingPlan findById(long id) {
        return plans.get(id);
    }

    @Override
    public List<TrainingPlan> findAll() {
        return List.copyOf(plans.values());
    }

    @Override
    public Map<String, List<Exercise>> getSchedule(long id) {
        TrainingPlan plan = findById(id);
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: " + id);
        }
        return plan.schedule();
    }

    @Override
    public void saveAll(List<TrainingPlan> plans) {
        for (TrainingPlan plan : plans) {
            this.plans.put(plan.id(), plan);
        }
    }

    @Override
    public void delete(long id) {
        plans.remove(id);
    }
}
