package dev.stowling.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import java.time.Instant;

final class ApiJson {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(
            Instant.class,
            (com.google.gson.JsonSerializer<Instant>) (value, type, context) ->
                    new JsonPrimitive(value.toString())
    )
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private ApiJson() {
    }

    static String encode(Object value) {
        return GSON.toJson(value);
    }
}
