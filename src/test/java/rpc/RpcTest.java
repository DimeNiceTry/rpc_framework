package rpc;

import rpc.TestServices.CalculatorService;
import rpc.TestServices.CalculatorServiceImpl;
import rpc.TestServices.User;
import rpc.TestServices.UserService;
import rpc.TestServices.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RpcTest {
    private RpcServer server;
    private ExecutorService executor;
    private Future<?> serverTask;
    private TestServiceImpl implementation;
    private TestService client;
    private UserService users;

    @BeforeEach
    void setUp() {
        server = new RpcServer(0);
        implementation = new TestServiceImpl();
        server.register(TestService.class, implementation);
        server.register(CalculatorService.class, new CalculatorServiceImpl());
        server.register(UserService.class, new UserServiceImpl());
        server.register(OtherService.class, () -> "other");
        executor = Executors.newSingleThreadExecutor();
        serverTask = executor.submit(server::start);
        client = RpcClient.create(TestService.class, "localhost", server.port());
        users = RpcClient.create(UserService.class, "localhost", server.port());
        assertEquals("pong", client.ping());
    }

    @AfterEach
    void tearDown() throws Exception {
        implementation.release.countDown();
        server.stop();
        try {
            serverTask.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callsMethodsWithAndWithoutArguments() {
        assertEquals(30, client.add(10, 20));
        assertEquals(42, client.multiply(6, 7));
        assertEquals("Привет\nJava", client.echo("Привет\nJava"));
        assertEquals("pong", client.ping());
    }

    @Test
    void transfersRecordsListsAndMaps() {
        User anna = new User(1, "Anna", 20);
        User ivan = new User(2, "Ivan", 21);
        users.saveAll(List.of(anna, ivan));

        assertEquals(anna, users.findById(1));
        assertEquals(List.of(anna, ivan), users.findAll());
        assertInstanceOf(User.class, users.findAll().getFirst());
        assertEquals(List.of(anna), users.findByAge(20));
        assertEquals(Map.of("IT", anna), users.findByDepartment());
        assertInstanceOf(User.class, users.findByDepartment().get("IT"));
    }

    @Test
    void supportsVoidMethodsAndEmptyCollections() {
        assertEquals(List.of(), users.findAll());
        assertEquals(Map.of(), users.findByDepartment());
        users.saveAll(List.of());
        users.saveAll(List.of(new User(1, "Anna", 20)));
        users.delete(1);
        assertNull(users.findById(1));
        assertEquals(List.of(), users.findAll());
    }

    @Test
    void transfersNullArgumentsAndResults() {
        assertNull(client.echo(null));
        assertNull(client.acceptUser(null));
        assertNull(client.acceptUsers(null));
        assertNull(users.findById(99));
    }

    @Test
    void selectsOverloadsByDeclaredParameterTypes() {
        assertEquals("string", client.describe("hello"));
        assertEquals("integer", client.describe(7));
        assertEquals("string", client.describe((String) null));
        assertEquals("integer", client.describe((Integer) null));
    }

    @Test
    void rejectsUnregisteredServices() {
        MissingService missing = RpcClient.create(MissingService.class, "localhost", server.port());
        assertThrows(RpcRemoteException.class, missing::ping);
        assertEquals("pong", client.ping());
    }

    @Test
    void returnsServerExceptionsAndContinuesWorking() {
        RpcRemoteException error = assertThrows(RpcRemoteException.class, client::fail);
        assertTrue(error.getMessage().contains("java.lang.IllegalStateException"));
        assertTrue(error.getMessage().contains("test failure"));
        assertEquals("pong", client.ping());
    }

    @Test
    void treatsProtocolExceptionInsideServiceAsRemoteError() {
        RpcRemoteException error = assertThrows(RpcRemoteException.class, client::failProtocol);
        assertTrue(error.getMessage().contains("rpc.RpcProtocolException"));
        assertTrue(error.getMessage().contains("service failure"));
        assertEquals("pong", client.ping());
    }

    @Test
    void distinguishesServicesWithTheSameMethodName() {
        OtherService other = RpcClient.create(OtherService.class, "localhost", server.port());
        assertEquals("pong", client.ping());
        assertEquals("other", other.ping());
    }

    @Test
    void reportsUnavailableServer() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0)) {
            port = unused.getLocalPort();
        }
        TestService unavailable = RpcClient.create(TestService.class, "localhost", port);
        assertThrows(RpcConnectionException.class, unavailable::ping);
    }

    @Test
    void timeoutDoesNotInterruptTheServerMethod() throws Exception {
        TestService impatient = RpcClient.create(TestService.class, "localhost", server.port(),
                Duration.ofMillis(100));
        assertThrows(RpcConnectionException.class, impatient::block);
        assertTrue(implementation.entered.await(2, TimeUnit.SECONDS));
        assertEquals(1, implementation.finished.getCount());
        implementation.release.countDown();
        assertTrue(implementation.finished.await(2, TimeUnit.SECONDS));
        assertEquals("pong", client.ping());
    }

    @Test
    void createAndObjectMethodsDoNotOpenConnections() throws Exception {
        try (ServerSocket unused = new ServerSocket(0)) {
            unused.setSoTimeout(100);
            TestService first = RpcClient.create(TestService.class, "localhost", unused.getLocalPort());
            TestService second = RpcClient.create(TestService.class, "localhost", unused.getLocalPort());
            assertTrue(Proxy.isProxyClass(first.getClass()));
            assertTrue(first.equals(first));
            assertFalse(first.equals(second));
            assertFalse(first.equals(null));
            assertFalse(first.equals(new Object()));
            assertEquals(System.identityHashCode(first), first.hashCode());
            assertEquals(first.getClass().getName() + "@" + Integer.toHexString(first.hashCode()),
                    first.toString());
            assertThrows(SocketTimeoutException.class, unused::accept);
        }
    }

    @Test
    void constructorBindsPortAndStopReleasesIt() throws Exception {
        int port = server.port();
        assertTrue(port > 0);
        assertThrows(RpcConnectionException.class, () -> new RpcServer(port));
        assertEquals("pong", client.ping());
        assertFalse(serverTask.isDone());
        server.stop();
        serverTask.get(2, TimeUnit.SECONDS);
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
        server.stop();
    }

    @Test
    void rejectsRegistrationAfterStart() {
        assertEquals("pong", client.ping());
        assertThrows(IllegalStateException.class,
                () -> server.register(OtherService.class, () -> "late"));
    }

    @Test
    void acceptsOnlyInterfaces() {
        assertThrows(IllegalArgumentException.class,
                () -> RpcClient.create(String.class, "localhost", server.port()));
        RpcServer other = new RpcServer(0);
        try {
            assertThrows(IllegalArgumentException.class, () -> other.register(String.class, "value"));
        } finally {
            other.stop();
        }
    }

    @Test
    void supportsNestedGenericCollections() {
        User user = new User(1, "Anna", 20);
        assertEquals(Map.of("IT", List.of(user)), client.report(Map.of("IT", List.of(user))));
        assertInstanceOf(User.class, client.report(Map.of("IT", List.of(user))).get("IT").getFirst());
        assertEquals(List.of(Map.of("Anna", user)), client.groups(List.of(Map.of("Anna", user))));
        assertInstanceOf(User.class, client.groups(List.of(Map.of("Anna", user))).getFirst().get("Anna"));
    }

    interface TestService extends CalculatorService {
        String ping();
        String describe(String value);
        String describe(Integer value);
        User acceptUser(User user);
        List<User> acceptUsers(List<User> users);
        void fail();
        void failProtocol();
        void block();
        Map<String, List<User>> report(Map<String, List<User>> users);
        List<Map<String, User>> groups(List<Map<String, User>> users);
    }

    interface OtherService {
        String ping();
    }

    interface MissingService {
        String ping();
    }

    static class TestServiceImpl extends CalculatorServiceImpl implements TestService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        public String ping() { return "pong"; }
        public String describe(String value) { return "string"; }
        public String describe(Integer value) { return "integer"; }
        public User acceptUser(User user) { return user; }
        public List<User> acceptUsers(List<User> users) { return users; }
        public Map<String, List<User>> report(Map<String, List<User>> users) { return users; }
        public List<Map<String, User>> groups(List<Map<String, User>> users) { return users; }

        public void fail() {
            throw new IllegalStateException("test failure");
        }

        public void failProtocol() {
            throw new RpcProtocolException("service failure");
        }

        public void block() {
            entered.countDown();
            try {
                release.await();
                finished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
