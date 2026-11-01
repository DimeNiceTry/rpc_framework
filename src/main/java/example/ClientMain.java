package example;

import rpc.RpcClient;

import java.util.List;
import java.util.Map;

public class ClientMain {
    public static void main(String[] args) {
        int port = args.length == 0 ? 9090 : Integer.parseInt(args[0]);
        long planId = args.length < 2 ? 1 : Long.parseLong(args[1]);
        PlanService service = RpcClient.create(PlanService.class, "localhost", port);

        List<TrainingPlan> plans = service.findAll();
        System.out.println("Каталог планов:");
        for (TrainingPlan plan : plans) {
            System.out.println(plan.id() + ": " + plan.name());
        }

        TrainingPlan plan = service.findById(planId);
        if (plan == null) {
            System.out.println("План не найден: " + planId);
            return;
        }
        System.out.println("\n" + plan.name());
        Map<String, List<Exercise>> schedule = service.getSchedule(planId);
        for (Map.Entry<String, List<Exercise>> day : schedule.entrySet()) {
            System.out.println(day.getKey() + ":");
            for (Exercise exercise : day.getValue()) {
                String weight = exercise.weightKg() == null ? "вес не указан" : exercise.weightKg() + " кг";
                System.out.printf("  %s: подходы %d, повторения %d, %s%n",
                        exercise.name(), exercise.sets(), exercise.reps(), weight);
            }
        }

        long newId = plans.stream().mapToLong(TrainingPlan::id).max().orElse(0) + 1;
        TrainingPlan copy = new TrainingPlan(newId, "Копия плана", schedule);
        service.saveAll(List.of(copy));
        System.out.println("\nДобавлен план: " + service.findById(newId).name());
        service.delete(newId);
        System.out.println("После удаления: " + service.findById(newId));
    }
}
