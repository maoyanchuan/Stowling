package dev.stowling.config;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.time.Duration;

public final class DurationJsonAdapter
        implements JsonSerializer<Duration>, JsonDeserializer<Duration> {
    @Override
    public JsonElement serialize(
            Duration duration,
            Type type,
            JsonSerializationContext context
    ) {
        return new JsonPrimitive(duration.getSeconds());
    }

    @Override
    public Duration deserialize(
            JsonElement json,
            Type type,
            JsonDeserializationContext context
    ) {
        long seconds = json.getAsLong();
        if (seconds < 0) {
            throw new IllegalArgumentException("duration seconds must not be negative");
        }
        return Duration.ofSeconds(seconds);
    }
}
