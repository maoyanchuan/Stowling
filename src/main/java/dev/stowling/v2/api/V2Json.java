package dev.stowling.v2.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import java.time.Instant;

final class V2Json {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(
                    Instant.class,
                    (com.google.gson.JsonSerializer<Instant>) (value, type, context) ->
                            new JsonPrimitive(value.toString())
            )
            .disableHtmlEscaping()
            .create();

    private V2Json() {
    }

    static Gson gson() {
        return GSON;
    }
}
