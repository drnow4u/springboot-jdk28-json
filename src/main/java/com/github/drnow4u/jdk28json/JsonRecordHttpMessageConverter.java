package com.github.drnow4u.jdk28json;

import jdk.incubator.json.Json;
import jdk.incubator.json.JsonBoolean;
import jdk.incubator.json.JsonNull;
import jdk.incubator.json.JsonNumber;
import jdk.incubator.json.JsonObject;
import jdk.incubator.json.JsonString;
import jdk.incubator.json.JsonValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.PayloadTooLargeException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Reads and writes Java records as JSON using the JDK 28 incubating JSON API
 * ({@code jdk.incubator.json}), so controllers can use records for
 * {@code @RequestBody} and return values without Jackson.
 * <p>
 * Supported component types: {@code String}, {@code int}/{@code Integer},
 * {@code long}/{@code Long}, {@code double}/{@code Double},
 * {@code boolean}/{@code Boolean} and nested records.
 */
@Component
public class JsonRecordHttpMessageConverter extends AbstractHttpMessageConverter<Record> {

    static final DataSize DEFAULT_MAX_BODY_SIZE = DataSize.ofMegabytes(1);

    // The JDK parser has no size limits and its error messages repeat the offending input
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;

    private final long maxBodySize;

    public JsonRecordHttpMessageConverter() {
        this(DEFAULT_MAX_BODY_SIZE);
    }

    @Autowired
    public JsonRecordHttpMessageConverter(@Value("${jdk-json.max-body-size:1MB}") DataSize maxBodySize) {
        super(MediaType.APPLICATION_JSON);
        this.maxBodySize = maxBodySize.toBytes();
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return clazz.isRecord();
    }

    @Override
    protected Record readInternal(Class<? extends Record> clazz, HttpInputMessage inputMessage) throws IOException {
        if (inputMessage.getHeaders().getContentLength() > maxBodySize) {
            throw new PayloadTooLargeException(null);
        }
        byte[] body = inputMessage.getBody().readNBytes(Math.toIntExact(Math.min(maxBodySize + 1, Integer.MAX_VALUE - 8)));
        if (body.length > maxBodySize) {
            throw new PayloadTooLargeException(null);
        }
        try {
            return toRecord(clazz, Json.parse(new String(body, charset(inputMessage.getHeaders().getContentType()))));
        } catch (RuntimeException e) {
            throw new HttpMessageNotReadableException("Cannot read JSON as " + clazz.getSimpleName() + ": " + abbreviate(e.getMessage()), e, inputMessage);
        }
    }

    private static String abbreviate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "... (" + message.length() + " chars)";
    }

    @Override
    protected void writeInternal(Record record, HttpOutputMessage outputMessage) throws IOException {
        String json;
        try {
            json = toJson(record).toString();
        } catch (RuntimeException e) {
            throw new HttpMessageNotWritableException("Cannot write " + record.getClass().getSimpleName() + " as JSON", e);
        }
        StreamUtils.copy(json, charset(outputMessage.getHeaders().getContentType()), outputMessage.getBody());
    }

    private Charset charset(MediaType contentType) {
        // JSON is UTF-8 by default (RFC 8259), so no charset is added to the Content-Type header
        return contentType != null && contentType.getCharset() != null ? contentType.getCharset() : StandardCharsets.UTF_8;
    }

    private static <T extends Record> T toRecord(Class<T> type, JsonValue json) {
        if (!(json instanceof JsonObject object)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            JsonValue value = object.tryGet(component.getName())
                    .flatMap(JsonValue::tryValue)
                    .orElse(null);
            args[i] = fromJson(component.getType(), value, component.getName());
        }
        Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(args);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e.getCause().getMessage(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object fromJson(Class<?> type, JsonValue value, String name) {
        if (value == null) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("missing value for '" + name + "'");
            }
            return null;
        }
        if (type == String.class) {
            return value.asString();
        }
        if (type == int.class || type == Integer.class) {
            return value.asInt();
        }
        if (type == long.class || type == Long.class) {
            return value.asLong();
        }
        if (type == double.class || type == Double.class) {
            return value.asDouble();
        }
        if (type == boolean.class || type == Boolean.class) {
            return value.asBoolean();
        }
        if (type.isRecord()) {
            return toRecord((Class<? extends Record>) type, value);
        }
        throw new IllegalArgumentException("unsupported type " + type.getName() + " for '" + name + "'");
    }

    private static JsonObject toJson(Record record) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                members.put(component.getName(), toJsonValue(component.getAccessor().invoke(record)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + component.getName(), e);
            }
        }
        return JsonObject.of(members);
    }

    private static JsonValue toJsonValue(Object value) {
        return switch (value) {
            case null -> JsonNull.of();
            case String s -> JsonString.of(s);
            case Integer i -> JsonNumber.of(i);
            case Long l -> JsonNumber.of(l);
            case Double d -> JsonNumber.of(d);
            case Boolean b -> JsonBoolean.of(b);
            case Record r -> toJson(r);
            default -> throw new IllegalArgumentException("unsupported type " + value.getClass().getName());
        };
    }
}
