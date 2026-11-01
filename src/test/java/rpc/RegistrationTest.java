package rpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RegistrationTest {
    private RpcServer server;
    private ExecutorService executor;
    private Future<?> serverTask;

    @BeforeEach
    void setUp() {
        server = new RpcServer(0);
        server.register(new Services());
        executor = Executors.newSingleThreadExecutor();
        serverTask = executor.submit(server::start);
        assertEquals("Hello, Anna", client(GreetingService.class).hello("Anna"));
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
    void registersAllAnnotatedInterfacesIncludingInheritedOnes() {
        assertEquals("Hello, Ivan", client(GreetingService.class).hello("Ivan"));
        assertEquals("Bye, Ivan", client(FarewellService.class).goodbye("Ivan"));
    }

    @Test
    void doesNotExposeUnannotatedInterfaces() {
        assertThrows(RpcRemoteException.class, () -> client(ChildGreeting.class).hello("Anna"));
        assertThrows(RpcRemoteException.class, () -> client(InternalService.class).secret());
        assertEquals("Hello, Anna", client(GreetingService.class).hello("Anna"));
    }

    @Test
    void rejectsObjectsWithoutAnnotatedInterfaces() {
        RpcServer other = new RpcServer(0);
        try {
            assertThrows(IllegalArgumentException.class, () -> other.register(new Object()));
        } finally {
            other.stop();
        }
    }

    @Test
    void rejectsAutomaticRegistrationAfterStart() {
        assertThrows(IllegalStateException.class, () -> server.register(new Services()));
    }

    private <T> T client(Class<T> service) {
        return RpcClient.create(service, "localhost", server.port());
    }

    @RpcService
    interface GreetingService {
        String hello(String name);
    }

    interface ChildGreeting extends GreetingService {}

    @RpcService
    interface FarewellService {
        String goodbye(String name);
    }

    interface InternalService {
        String secret();
    }

    static class GreetingImpl implements ChildGreeting {
        public String hello(String name) {
            return "Hello, " + name;
        }
    }

    static class Services extends GreetingImpl implements FarewellService, InternalService {
        public String goodbye(String name) {
            return "Bye, " + name;
        }

        public String secret() {
            return "internal";
        }
    }
}
