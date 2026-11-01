package rpc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

final class Protocol {
    static final int VERSION = 1;

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    record Request(Integer version, String service, String method,
                   List<String> parameterTypes, JsonNode arguments) {}

    record Response(Boolean success, JsonNode result, String error, String errorType) {}

    static <T> T read(InputStream input, Class<T> type) throws IOException {
        DataInputStream stream = new DataInputStream(input);
        int length = stream.readInt();
        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid message length: " + length);
        }
        byte[] data = new byte[length];
        stream.readFully(data);
        return MAPPER.readValue(data, type);
    }

    static void write(OutputStream output, Object message) throws IOException {
        byte[] data = MAPPER.writeValueAsBytes(message);
        if (data.length > MAX_MESSAGE_SIZE) {
            throw new IOException("Message is too large");
        }
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(data.length);
        stream.write(data);
        stream.flush();
    }
}
