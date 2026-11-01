package rpc;

public class RpcProtocolException extends RpcException {
    public RpcProtocolException(String message) {
        super(message);
    }

    public RpcProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
