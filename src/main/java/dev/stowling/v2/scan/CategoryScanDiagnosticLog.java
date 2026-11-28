package dev.stowling.v2.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryZoneOptions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Complete read-only observations are separate from the task event log's short status messages. */
public final class CategoryScanDiagnosticLog {
    public static final int CAPACITY = 3;
    private static final int MAX_STAGES = 1024;
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(Instant.class,
            (com.google.gson.JsonSerializer<Instant>) (value, type, context) ->
                    new JsonPrimitive(value.toString())).serializeNulls().create();
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "(?i)Bearer\\s+[^\\s;,]+|(?:token|password|authorization|api[_-]?key)"
                    + "\\s*[:=]\\s*[^\\s;,&]+"
    );
    private final String session = UUID.randomUUID().toString();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public record Summary(UUID id, Instant startedAt, Instant finishedAt, String operation,
            String state, String message, AreaBounds area, int validSlices, int totalSlices,
            long elapsedMillis) {
    }

    public record Stage(Instant time, String stage, JsonElement data) {
    }

    public record Attachment(BlockPosition position, BlockPosition anchor) {
    }

    public record World(AreaBounds area, String dimension, List<CategoryScanBlock> blocks,
            List<dev.stowling.v2.category.CategoryMarker> markers, List<Attachment> attachments) {
    }

    public record Entry(int formatVersion, Summary summary, Map<String, String> versions,
            JsonElement request, World world, List<Stage> stages, int droppedStages,
            JsonElement result) {
    }

    public record History(String session, int capacity, List<Summary> scans) {
    }

    public synchronized History history() {
        return new History(session, CAPACITY, entries.reversed().stream().map(Entry::summary).toList());
    }

    public synchronized Entry get(UUID id) {
        return entries.stream().filter(entry -> entry.summary().id().equals(id)).findFirst()
                .orElseThrow(() -> new dev.stowling.v2.control.V2NotFoundException(
                        "扫描日志不存在或已轮换，请重新扫描后导出"));
    }

    private synchronized void retain(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > CAPACITY) entries.removeFirst();
    }

    public Capture begin(String operation, AreaBounds area, CategoryScanDefinition scan,
            CategoryZoneOptions options, Map<String, String> versions) {
        return new Capture(operation, area, scan, options, versions);
    }

    public final class Capture {
        private final UUID id = UUID.randomUUID();
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private final String operation;
        private final AreaBounds area;
        private final Map<String, String> versions;
        private final JsonElement request;
        private final Set<String> names = new LinkedHashSet<>();
        private final List<Stage> stages = new ArrayList<>();
        private World world;
        private int droppedStages;

        private Capture(String operation, AreaBounds area, CategoryScanDefinition scan,
                CategoryZoneOptions options, Map<String, String> versions) {
            this.operation = operation;
            this.area = area;
            this.versions = Map.copyOf(versions);
            JsonObject raw = new JsonObject();
            raw.add("area", GSON.toJsonTree(area));
            raw.add("scan", GSON.toJsonTree(scan));
            raw.add("categoryOptions", GSON.toJsonTree(options));
            collectNames(raw);
            request = clean(raw);
        }

        public UUID id() {
            return id;
        }

        public void observe(CategoryScanSnapshot snapshot) {
            world = new World(snapshot.area(), snapshot.dimension(), snapshot.blocks(),
                    snapshot.markers(), snapshot.markerAnchors().entrySet().stream()
                            .map(entry -> new Attachment(entry.getKey(), entry.getValue())).toList());
            stage("WORLD", Map.of("blocks", world.blocks().size(), "markers", world.markers().size(),
                    "attachments", world.attachments().size()));
        }

        public void stage(String stage, Object data) {
            if (stages.size() >= MAX_STAGES) { droppedStages++; return; }
            stages.add(new Stage(Instant.now(), stage, clean(GSON.toJsonTree(data))));
        }

        public Summary finish(CategoryScanResult result, RuntimeException failure) {
            int total = result == null ? 0 : result.scan().sliceCount();
            int valid = result == null ? 0 : (int) result.scan().slices().stream()
                    .filter(slice -> slice.reason().isEmpty()).count();
            String state = failure != null || valid == 0 ? "FAILED"
                    : valid == total ? "SUCCEEDED" : "PARTIAL";
            String message = failure != null ? safe(failure.getMessage())
                    : valid + " / " + total + " 片有效";
            if (failure == null) stage("RESULT", Map.of("message", message));
            else stage("ERROR", Map.of("message", message,
                    "exception", failure.getClass().getName()));
            var summary = new Summary(id, startedAt, Instant.now(), operation, state, message, area,
                    valid, total, Math.max(0, (System.nanoTime() - startedNanos) / 1000000));
            retain(new Entry(1, summary, versions, request, world, List.copyOf(stages), droppedStages,
                    clean(GSON.toJsonTree(result))));
            return summary;
        }

        private void collectNames(JsonElement element) {
            if (element == null || element.isJsonNull()) return;
            if (element.isJsonArray()) {
                element.getAsJsonArray().forEach(this::collectNames);
            } else if (element.isJsonObject()) {
                for (var field : element.getAsJsonObject().entrySet()) {
                    if (field.getKey().equals("name") && field.getValue().isJsonPrimitive()) {
                        String name = field.getValue().getAsString();
                        if (!name.isBlank()) names.add(name);
                    }
                    collectNames(field.getValue());
                }
            }
        }

        private JsonElement clean(JsonElement value) {
            if (value == null || value.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
            if (value.isJsonArray()) {
                var result = new com.google.gson.JsonArray();
                value.getAsJsonArray().forEach(entry -> result.add(clean(entry)));
                return result;
            }
            if (value.isJsonObject()) {
                var result = new JsonObject();
                for (var field : value.getAsJsonObject().entrySet()) {
                    String key = field.getKey();
                    if (Set.of("name", "token", "authorization", "password", "player", "server")
                            .contains(key.toLowerCase(java.util.Locale.ROOT))) continue;
                    result.add(key, clean(field.getValue()));
                }
                return result;
            }
            return value.getAsJsonPrimitive().isString()
                    ? new JsonPrimitive(safe(value.getAsString())) : value.deepCopy();
        }

        private String safe(String value) {
            String result = SECRET.matcher(value == null ? "扫描失败" : value)
                    .replaceAll("[已省略凭据]");
            for (String name : names) result = result.replace(name, "[名称已省略]");
            return result;
        }
    }
}
