package example;

import rpc.RpcService;

import java.util.List;
import java.util.Map;

@RpcService
public interface PlanService extends PlanCatalog {
    Map<String, List<Exercise>> getSchedule(long id);
    void saveAll(List<TrainingPlan> plans);
    void delete(long id);
}
