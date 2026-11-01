package example;

import rpc.RpcServer;

public class ServerMain {
    public static void main(String[] args) {
        int port = args.length == 0 ? 9090 : Integer.parseInt(args[0]);
        RpcServer server = new RpcServer(port);
        server.register(new PlanServiceImpl());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Server started on port " + server.port());
        server.start();
    }
}
