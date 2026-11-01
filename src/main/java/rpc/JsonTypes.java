package rpc;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonTypes {
    static Object read(JsonNode value, Type type) {
        if (value == null) {
            throw new IllegalArgumentException("Missing value");
        }
        if (type == void.class) {
            if (!value.isNull()) {
                throw new IllegalArgumentException("Expected null for void");
            }
            return null;
        }
        if (value.isNull()) {
            if (type instanceof Class<?> clazz && clazz.isPrimitive()) {
                throw new IllegalArgumentException("Null for primitive type: " + type);
            }
            return null;
        }
        if (type instanceof ParameterizedType generic) {
            Type[] arguments = generic.getActualTypeArguments();
            if (generic.getRawType() == List.class) {
                if (!value.isArray()) {
                    throw new IllegalArgumentException("Expected a JSON array");
                }
                List<Object> result = new ArrayList<>();
                for (JsonNode element : value) {
                    result.add(read(element, arguments[0]));
                }
                return result;
            }
            if (generic.getRawType() == Map.class) {
                if (!value.isObject() || arguments[0] != String.class) {
                    throw new IllegalArgumentException("Expected a JSON object with String keys");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                value.properties().forEach(entry ->
                        result.put(entry.getKey(), read(entry.getValue(), arguments[1])));
                return result;
            }
        }
        return Protocol.MAPPER.convertValue(value, Protocol.MAPPER.constructType(type));
    }
}
