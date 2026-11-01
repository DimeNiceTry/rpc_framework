package rpc;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

public final class RpcClient {
    private RpcClient() {}

    public static <T> T create(Class<T> serviceInterface, String host, int port) {
        return create(serviceInterface, host, port, Duration.ofSeconds(5));
    }

    public static <T> T create(Class<T> serviceInterface, String host, int port, Duration timeout) {
        Objects.requireNonNull(serviceInterface);
        Objects.requireNonNull(host);
        Objects.requireNonNull(timeout);
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException("Service must be an interface");
        }
        if (host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid host or port");
        }
        if (timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Timeout must be positive and fit in milliseconds");
        }
        int timeoutMillis = (int) Math.max(1, timeout.toMillis());
        Object proxy = Proxy.newProxyInstance(serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface}, (object, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> object == arguments[0];
                            case "hashCode" -> System.identityHashCode(object);
                            case "toString" -> object.getClass().getName() + "@"
                                    + Integer.toHexString(System.identityHashCode(object));
                            default -> throw new IllegalStateException(method.getName());
                        };
                    }
                    return call(serviceInterface, method, arguments, host, port, timeoutMillis);
                });
        return serviceInterface.cast(proxy);
    }

    private static Object call(Class<?> service, Method method, Object[] arguments,
                               String host, int port, int timeout) {
        Protocol.Request request = new Protocol.Request(Protocol.VERSION, service.getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),
                Protocol.MAPPER.valueToTree(arguments == null ? new Object[0] : arguments));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            Protocol.write(socket.getOutputStream(), request);
            try {
                Protocol.Response response = Protocol.read(socket.getInputStream(), Protocol.Response.class);
                if (response == null || response.success() == null) {
                    throw new RpcProtocolException("Missing response status");
                }
                if (!response.success()) {
                    if (response.error() == null || response.error().isBlank()) {
                        throw new RpcProtocolException("Missing remote error");
                    }
                    if ("PROTOCOL".equals(response.errorType())) {
                        throw new RpcProtocolException(response.error());
                    }
                    if (!"REMOTE".equals(response.errorType())) {
                        throw new RpcProtocolException("Invalid error type: " + response.errorType());
                    }
                    throw new RpcRemoteException(response.error());
                }
                if (response.error() != null || response.errorType() != null) {
                    throw new RpcProtocolException("Unexpected error in a successful response");
                }
                return JsonTypes.read(response.result(), method.getGenericReturnType());
            } catch (SocketTimeoutException e) {
                throw new RpcConnectionException("Response timeout", e);
            } catch (IOException | IllegalArgumentException e) {
                throw new RpcProtocolException("Invalid response: " + e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new RpcConnectionException("Connection failed: " + host + ":" + port, e);
        }
    }
}
