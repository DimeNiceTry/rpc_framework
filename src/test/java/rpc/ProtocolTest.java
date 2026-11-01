package rpc;

import com.fasterxml.jackson.databind.JsonNode;
import rpc.TestServices.CalculatorService;
import rpc.TestServices.CalculatorServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProtocolTest {
    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidResponses(byte[] response) throws Exception {
        withResponse(response, client -> assertThrows(RpcProtocolException.class, () -> client.add(1, 2)));
    }

    static Stream<byte[]> invalidResponses() throws IOException {
        byte[] valid = frame("{\"success\":true,\"result\":3,\"error\":null}");
        return Stream.of(
                new byte[0],
                new byte[]{0, 0},
                new byte[]{0, 0, 0, 0},
                new byte[]{-1, -1, -1, -1},
                new byte[]{127, -1, -1, -1},
                Arrays.copyOf(valid, valid.length - 2),
                frame("not json"),
                frame("null"),
                frame("{}"),
                frame("{\"success\":1,\"result\":3}"),
                frame("{\"success\":true}"),
                frame("{\"success\":false,\"result\":null}"),
                frame("{\"success\":false,\"result\":null,\"error\":42}"),
                frame("{\"success\":false,\"result\":null,\"error\":\"failure\"}"),
                frame("{\"success\":false,\"error\":\"failure\",\"errorType\":\"UNKNOWN\"}"),
                frame("{\"success\":true,\"result\":3,\"error\":\"unexpected\"}"),
                frame("{\"success\":true,\"result\":3,\"errorType\":\"PROTOCOL\"}"),
                frame("{\"success\":true,\"result\":null}"),
                frame("{\"success\":true,\"result\":\"3\"}"),
                frame("{\"success\":true,\"result\":3.5}"),
                frame("{\"success\":true,\"result\":true}"),
                frame("{\"success\":true,\"result\":{}}"),
                frame("{\"success\":true,\"result\":[]}"),
                frame("{\"success\":true,\"result\":2147483648}"),
                frame("{\"success\":true,\"result\":3} {}"));
    }

    @Test
    void rejectsNumberInsteadOfString() throws Exception {
        withResponse(frame("{\"success\":true,\"result\":42}"),
                client -> assertThrows(RpcProtocolException.class, () -> client.echo("hello")));
    }

    @Test
    void rejectsMissingStringResult() throws Exception {
        withResponse(frame("{\"success\":true}"),
                client -> assertThrows(RpcProtocolException.class, () -> client.echo("hello")));
    }

    @Test
    void readsResponseDeliveredInParts() throws Exception {
        withResponse(frame("{\"success\":true,\"result\":30,\"error\":null}"),
                client -> assertEquals(30, client.add(10, 20)));
    }

    @Test
    void readsRemoteErrors() throws Exception {
        withResponse(frame("{\"success\":false,\"result\":null,\"error\":\"unknown method\",\"errorType\":\"REMOTE\"}"), client -> {
            RpcRemoteException error = assertThrows(RpcRemoteException.class, () -> client.add(1, 2));
            assertEquals("unknown method", error.getMessage());
        });
    }

    @Test
    void rejectsBadRequestsAndKeepsServing() throws Exception {
        RpcServer server = new RpcServer(0);
        server.register(CalculatorService.class, new ExtraCalculator());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> task = executor.submit(server::start);
        try {
            assertFalse(exchange(server.port(), "not json").get("success").asBoolean());
            assertFalse(exchange(server.port(), request("hidden", "[]", "[]")).get("success").asBoolean());
            assertFalse(exchange(server.port(), request("getClass", "[]", "[]")).get("success").asBoolean());
            assertFalse(exchange(server.port(), request("add", "[\"int\",\"int\"]", "[1]"))
                    .get("success").asBoolean());
            assertFalse(exchange(server.port(), request("add", "[\"int\",\"int\"]", "[null,2]"))
                    .get("success").asBoolean());
            assertFalse(exchange(server.port(), request("add", "[\"int\",\"int\"]", "[\"wrong\",2]"))
                    .get("success").asBoolean());
            JsonNode response = exchange(server.port(), request("add", "[\"int\",\"int\"]", "[10,20]"));
            assertTrue(response.get("success").asBoolean());
            assertEquals(30, response.get("result").asInt());
        } finally {
            server.stop();
            try {
                task.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void reportsUnsupportedVersionWithoutCallingService() throws Exception {
        RpcServer server = new RpcServer(0);
        ExtraCalculator implementation = new ExtraCalculator();
        server.register(CalculatorService.class, implementation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> task = executor.submit(server::start);
        try {
            String valid = request("add", "[\"int\",\"int\"]", "[10,20]");
            String unsupported = valid.replace("\"version\":1", "\"version\":2");
            JsonNode response = exchange(server.port(), unsupported);
            assertFalse(response.get("success").asBoolean());
            assertEquals("PROTOCOL", response.get("errorType").asText());
            assertEquals("Unsupported protocol version: 2; expected 1", response.get("error").asText());
            withResponse(frame(response.toString()), client -> {
                RpcProtocolException error = assertThrows(RpcProtocolException.class, () -> client.add(10, 20));
                assertEquals(response.get("error").asText(), error.getMessage());
            });

            JsonNode missing = exchange(server.port(), valid.replace("\"version\":1,", ""));
            assertEquals("PROTOCOL", missing.get("errorType").asText());
            assertEquals("Unsupported protocol version: null; expected 1", missing.get("error").asText());
            assertEquals(0, implementation.calls);
            assertEquals(30, exchange(server.port(), valid).get("result").asInt());
            assertEquals(1, implementation.calls);
        } finally {
            server.stop();
            try {
                task.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void withResponse(byte[] response, Consumer<CalculatorService> check) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> task = executor.submit(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(2000);
                    JsonNode request = readJson(client);
                    assertEquals(1, request.get("version").asInt());
                    writeInParts(client, response);
                }
                return null;
            });
            try {
                CalculatorService client = RpcClient.create(CalculatorService.class, "localhost", server.getLocalPort());
                check.accept(client);
                task.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static JsonNode exchange(int port, String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(2000);
            writeInParts(socket, frame(request));
            return readJson(socket);
        }
    }

    private static JsonNode readJson(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        byte[] data = input.readNBytes(length);
        assertEquals(length, data.length);
        return Protocol.MAPPER.readTree(data);
    }

    private static void writeInParts(Socket socket, byte[] data) throws IOException {
        for (int i = 0; i < data.length; i += 3) {
            socket.getOutputStream().write(data, i, Math.min(3, data.length - i));
            socket.getOutputStream().flush();
        }
    }

    private static byte[] frame(String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(data.length);
        output.write(data);
        return bytes.toByteArray();
    }

    private static String request(String method, String types, String arguments) {
        return "{\"version\":1,\"service\":\"" + CalculatorService.class.getName() + "\",\"method\":\"" + method
                + "\",\"parameterTypes\":" + types + ",\"arguments\":" + arguments + "}";
    }

    static class ExtraCalculator extends CalculatorServiceImpl {
        volatile int calls;

        @Override
        public int add(int a, int b) {
            calls++;
            return super.add(a, b);
        }

        public String hidden() { return "not a service method"; }
    }
}
