package rpc;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RpcServer {
    private final ServerSocket socket;
    private final Map<String, Service> services = new HashMap<>();
    private boolean started;

    private record MethodKey(String name, List<String> parameterTypes) {}

    private record MethodInfo(Method method, Type[] parameterTypes) {}

    private record Service(Object implementation, Map<MethodKey, MethodInfo> methods) {}

    public RpcServer(int port) {
        try {
            socket = new ServerSocket(port);
        } catch (IOException e) {
            throw new RpcConnectionException("Cannot open port: " + port, e);
        }
    }

    public synchronized <T> void register(Class<T> serviceInterface, T implementation) {
        Objects.requireNonNull(serviceInterface);
        Objects.requireNonNull(implementation);
        checkRegistration();
        if (!serviceInterface.isInterface() || !serviceInterface.isInstance(implementation)) {
            throw new IllegalArgumentException("Implementation must implement the service interface");
        }
        addService(serviceInterface, implementation);
    }

    public synchronized void register(Object implementation) {
        Objects.requireNonNull(implementation);
        checkRegistration();
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(implementation.getClass(), interfaces);
        boolean registered = false;
        for (Class<?> service : interfaces) {
            if (service.isAnnotationPresent(RpcService.class)) {
                addService(service, implementation);
                registered = true;
            }
        }
        if (!registered) {
            throw new IllegalArgumentException("No interfaces annotated with @RpcService");
        }
    }

    private void checkRegistration() {
        if (started || socket.isClosed()) {
            throw new IllegalStateException("Register services before start");
        }
    }

    private void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
        if (type == null) {
            return;
        }
        for (Class<?> service : type.getInterfaces()) {
            if (interfaces.add(service)) {
                collectInterfaces(service, interfaces);
            }
        }
        collectInterfaces(type.getSuperclass(), interfaces);
    }

    private void addService(Class<?> service, Object implementation) {
        Map<MethodKey, MethodInfo> methods = new HashMap<>();
        for (Method method : service.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                method.trySetAccessible();
                MethodKey key = new MethodKey(method.getName(),
                        Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
                methods.put(key, new MethodInfo(method, method.getGenericParameterTypes()));
            }
        }
        services.put(service.getName(), new Service(implementation, methods));
    }

    public void start() {
        synchronized (this) {
            if (started || socket.isClosed()) {
                throw new IllegalStateException("Server is already started or stopped");
            }
            started = true;
        }
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
                throw new RpcConnectionException("Cannot accept a connection", e);
            }
            try (client) {
                Protocol.Response response;
                try {
                    response = invoke(Protocol.read(client.getInputStream(), Protocol.Request.class));
                } catch (IOException | RuntimeException e) {
                    response = protocolError("Invalid request: " + e.getMessage());
                }
                Protocol.write(client.getOutputStream(), response);
            } catch (IOException ignored) {
            }
        }
    }

    public void stop() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new RpcConnectionException("Cannot close the server", e);
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    private Protocol.Response invoke(Protocol.Request request) {
        if (request == null) {
            return protocolError("Missing request");
        }
        if (request.version() == null || request.version() != Protocol.VERSION) {
            return protocolError("Unsupported protocol version: " + request.version()
                    + "; expected " + Protocol.VERSION);
        }
        try {
            if (request.service() == null || request.method() == null
                    || request.parameterTypes() == null || request.arguments() == null
                    || !request.arguments().isArray()) {
                throw new IllegalArgumentException("Invalid request");
            }
            Service service = services.get(request.service());
            if (service == null) {
                throw new IllegalArgumentException("Service is not registered: " + request.service());
            }
            MethodInfo method = service.methods().get(new MethodKey(request.method(), request.parameterTypes()));
            if (method == null) {
                throw new NoSuchMethodException(request.method());
            }
            Type[] types = method.parameterTypes();
            if (types.length != request.arguments().size()) {
                throw new IllegalArgumentException("Wrong number of arguments");
            }
            Object[] arguments = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                arguments[i] = JsonTypes.read(request.arguments().get(i), types[i]);
            }
            Object result = method.method().invoke(service.implementation(), arguments);
            return new Protocol.Response(true, Protocol.MAPPER.valueToTree(result), null, null);
        } catch (InvocationTargetException e) {
            return error(e.getCause());
        } catch (Exception e) {
            return error(e);
        }
    }

    private Protocol.Response error(Throwable cause) {
        return new Protocol.Response(false, Protocol.MAPPER.nullNode(),
                cause.getClass().getName() + ": " + cause.getMessage(), "REMOTE");
    }

    private Protocol.Response protocolError(String message) {
        return new Protocol.Response(false, Protocol.MAPPER.nullNode(), message, "PROTOCOL");
    }
}
