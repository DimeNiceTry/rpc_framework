package example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rpc.RpcClient;
import rpc.RpcRemoteException;
import rpc.RpcServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PlanServiceTest {
    private RpcServer server;
    private ExecutorService executor;
    private Future<?> serverTask;
    private PlanService client;

    @BeforeEach
    void setUp() {
        server = new RpcServer(0);
        server.register(PlanService.class, new PlanServiceImpl());
        executor = Executors.newSingleThreadExecutor();
        serverTask = executor.submit(server::start);
        client = RpcClient.create(PlanService.class, "localhost", server.port());
        assertEquals(2, client.findAll().size());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        try {
            serverTask.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readsInheritedCatalogMethodsAndNestedSchedule() {
        List<TrainingPlan> plans = client.findAll();
        assertEquals(plans.getFirst(), client.findById(1));
        assertInstanceOf(TrainingPlan.class, plans.getFirst());

        for (TrainingPlan plan : plans) {
            Map<String, List<Exercise>> schedule = client.getSchedule(plan.id());
            assertEquals(List.of("День 1", "День 2", "День 3"), List.copyOf(schedule.keySet()));
            assertEquals(plan.schedule(), schedule);
            assertInstanceOf(Exercise.class, schedule.get("День 1").getFirst());
        }
        assertEquals(new Exercise("Присед", 3, 5, 60.0), client.getSchedule(1).get("День 1").getFirst());
        assertNull(client.getSchedule(1).get("День 1").getLast().weightKg());
    }

    @Test
    void savesAndDeletesPlansThroughRpc() {
        Exercise exercise = new Exercise("Жим лёжа", 4, 6, 42.5);
        TrainingPlan plan = new TrainingPlan(10, "Новый план", Map.of("День 1", List.of(exercise)));
        TrainingPlan empty = new TrainingPlan(11, "Пустой план", Map.of());

        client.saveAll(List.of(plan, empty));
        assertEquals(4, client.findAll().size());
        assertEquals(plan, client.findById(10));
        assertEquals(exercise, client.getSchedule(10).get("День 1").getFirst());
        assertEquals(Map.of(), client.getSchedule(11));

        client.delete(10);
        client.delete(10);
        assertNull(client.findById(10));
        assertEquals(3, client.findAll().size());
    }

    @Test
    void reportsMissingScheduleAndContinuesWorking() {
        assertNull(client.findById(999));
        RpcRemoteException error = assertThrows(RpcRemoteException.class, () -> client.getSchedule(999));
        assertTrue(error.getMessage().contains("java.lang.IllegalArgumentException"));
        assertTrue(error.getMessage().contains("Plan not found: 999"));
        assertEquals(3, client.getSchedule(1).size());
    }
}
