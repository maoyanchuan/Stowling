package dev.stowling.v2.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.control.V2ControlService;
import dev.stowling.v2.control.V2ControlServiceImpl;
import dev.stowling.v2.runtime.TaskRunManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V2ControlServerTest {
    private static final String TOKEN = "test-token-that-is-long-enough";

    @TempDir
    Path directory;

    @Test
    void enforcesAuthenticationSecurityHeadersAndAllowedMethods() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> page = fixture.send("/", "GET", null, false);
            assertEquals(200, page.statusCode());
            assertTrue(page.headers()
                    .firstValue("Content-Security-Policy")
                    .orElse("")
                    .contains("default-src 'self'"));
            assertEquals(
                    "nosniff",
                    page.headers()
                            .firstValue("X-Content-Type-Options")
                            .orElse("")
            );

            HttpResponse<String> unauthorized = fixture.send(
                    "/v1/status",
                    "GET",
                    null,
                    false
            );
            assertEquals(401, unauthorized.statusCode());
            assertEquals(
                    "Bearer",
                    unauthorized.headers()
                            .firstValue("WWW-Authenticate")
                            .orElse("")
            );

            HttpResponse<String> wrongMethod = fixture.send(
                    "/v1/coordinates",
                    "PATCH",
                    "",
                    true
            );
            assertEquals(405, wrongMethod.statusCode());
            assertEquals(
                    "GET, POST",
                    wrongMethod.headers().firstValue("Allow").orElse("")
            );
        }
    }

    @Test
    void removesEveryLegacyRoute() throws Exception {
        try (Fixture fixture = fixture()) {
            for (String path : List.of(
                    "/v1/config/coordinates",
                    "/v1/task-definitions",
                    "/v1/task-definitions/example",
                    "/v1/containers",
                    "/v1/pickup"
            )) {
                HttpResponse<String> response = fixture.send(
                        path,
                        "GET",
                        null,
                        true
                );
                assertEquals(404, response.statusCode(), path);
            }
        }
    }

    @Test
    void supportsCoordinateAndTaskCrudWithRevisions() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject first = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Home", 1, 64, 2),
                    true
            ));
            JsonObject second = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Mine", 40, 70, -8),
                    true
            ));
            String firstId = first.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String secondId = second.getAsJsonArray("coordinates")
                    .get(1)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String revision = second.get("revision").getAsString();
            String taskBody = task(
                    "Travel",
                    List.of(firstId, secondId)
            );

            HttpResponse<String> taskCreated = fixture.send(
                    "/v1/tasks",
                    "POST",
                    taskBody,
                    true
            );
            assertEquals(201, taskCreated.statusCode());
            JsonObject taskList = object(taskCreated);
            JsonObject task = taskList.getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject();
            String taskId = task.get("id").getAsString();
            assertEquals(2, task.getAsJsonArray("steps").size());
            assertEquals(2, object(fixture.send(
                    "/v1/tasks/" + taskId,
                    "GET",
                    null,
                    true
            )).getAsJsonArray("steps").size());

            HttpResponse<String> stale = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "PUT",
                    """
                            {"revision":"stale","name":"Changed","x":2,"y":65,"z":3}
                            """,
                    true
            );
            assertEquals(409, stale.statusCode());

            revision = taskList.get("revision").getAsString();
            HttpResponse<String> inUse = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "DELETE",
                    "{\"revision\":\"" + revision + "\"}",
                    true
            );
            assertEquals(409, inUse.statusCode());
            JsonArray references = object(inUse)
                    .getAsJsonObject("error")
                    .getAsJsonArray("taskNames");
            assertEquals("Travel", references.get(0).getAsString());

            HttpResponse<String> renamed = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "PUT",
                    """
                            {"revision":"%s","name":"Base","x":3,"y":66,"z":4}
                            """.formatted(revision),
                    true
            );
            assertEquals(200, renamed.statusCode());
            revision = object(renamed).get("revision").getAsString();
            assertEquals(
                    firstId,
                    object(fixture.send(
                            "/v1/tasks/" + taskId,
                            "GET",
                            null,
                            true
                    )).getAsJsonArray("steps")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("parameters")
                            .get("coordinateId")
                            .getAsString()
            );

            HttpResponse<String> taskDeleted = fixture.send(
                    "/v1/tasks/" + taskId,
                    "DELETE",
                    "{\"revision\":\"" + revision + "\"}",
                    true
            );
            assertEquals(200, taskDeleted.statusCode());
            revision = object(taskDeleted).get("revision").getAsString();
            assertEquals(
                    200,
                    fixture.send(
                            "/v1/coordinates/" + firstId,
                            "DELETE",
                            "{\"revision\":\"" + revision + "\"}",
                            true
                    ).statusCode()
            );
        }
    }

    @Test
    void supportsOptionalCoordinateDimensionAndRadius() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> created = fixture.send(
                    "/v1/coordinates",
                    "POST",
                    """
                            {"name":"Home","x":1,"y":64,"z":2,
                            "dimension":"minecraft:overworld","radius":4}
                            """,
                    true
            );
            assertEquals(201, created.statusCode());
            JsonObject coordinate = object(created)
                    .getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(
                    "minecraft:overworld",
                    coordinate.get("dimension").getAsString()
            );
            assertEquals(4, coordinate.get("radius").getAsInt());

            String id = coordinate.get("id").getAsString();
            HttpResponse<String> disabled = fixture.send(
                    "/v1/coordinates/" + id,
                    "PUT",
                    """
                            {"revision":"%s","name":"Home","x":1,"y":64,"z":2,
                            "dimension":null,"radius":null}
                            """.formatted(object(created).get("revision").getAsString()),
                    true
            );
            JsonObject updated = object(disabled)
                    .getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject();
            assertTrue(updated.get("dimension").isJsonNull());
            assertTrue(updated.get("radius").isJsonNull());
        }
    }

    @Test
    void rejectsUnknownModulesParametersAndCoordinates() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject created = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Home", 0, 64, 0),
                    true
            ));
            String id = created.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String unknownModule = """
                    {"name":"Unknown","steps":[{"moduleId":"UNKNOWN",
                    "parameters":{"coordinateId":"%s"}}]}
                    """.formatted(id);
            String extraParameter = """
                    {"name":"Extra","steps":[{
                    "moduleId":"MOVE_TO_COORDINATE",
                    "parameters":{"coordinateId":"%s","radius":"3"}}]}
                    """.formatted(id);
            String missingCoordinate = """
                    {"name":"Missing","steps":[{
                    "moduleId":"MOVE_TO_COORDINATE",
                    "parameters":{"coordinateId":"%s"}}]}
                    """.formatted("00000000-0000-0000-0000-000000000000");

            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", unknownModule, true)
                            .statusCode()
            );
            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", extraParameter, true)
                            .statusCode()
            );
            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", missingCoordinate, true)
                            .statusCode()
            );
        }
    }

    @Test
    void rejectsDuplicateAndTrailingJsonFields() throws Exception {
        try (Fixture fixture = fixture()) {
            String duplicate = """
                    {"name":"Home","name":"Other","x":1,"y":64,"z":2}
                    """;
            String trailing = """
                    {"name":"Home","x":1,"y":64,"z":2} {}
                    """;

            assertEquals(
                    400,
                    fixture.send(
                            "/v1/coordinates",
                            "POST",
                            duplicate,
                            true
                    ).statusCode()
            );
            assertEquals(
                    400,
                    fixture.send(
                            "/v1/coordinates",
                            "POST",
                            trailing,
                            true
                    ).statusCode()
            );
        }
    }

    private Fixture fixture() throws IOException {
        ConfigStore store = new ConfigStore(directory.resolve("stowling.json"));
        store.loadOrReset();
        V2ControlService service = new V2ControlServiceImpl(
                store,
                new TaskRunManager(new FailedNavigation()),
                List::of
        );
        return new Fixture(service);
    }

    private static String coordinate(
            String name,
            int x,
            int y,
            int z
    ) {
        return """
                {"name":"%s","x":%d,"y":%d,"z":%d}
                """.formatted(name, x, y, z);
    }

    private static String task(String name, List<String> coordinateIds) {
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < coordinateIds.size(); index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("""
                    {"moduleId":"MOVE_TO_COORDINATE","parameters":{
                    "coordinateId":"%s"}}
                    """.formatted(coordinateIds.get(index)));
        }
        return """
                {"name":"%s","steps":[%s]}
                """.formatted(name, steps);
    }

    private static JsonObject object(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpClient client = HttpClient.newHttpClient();
        private final V2ControlServer server;
        private final String base;

        private Fixture(V2ControlService service) throws IOException {
            server = new V2ControlServer(
                    new InetSocketAddress("127.0.0.1", 0),
                    service,
                    TOKEN
            );
            server.start();
            base = "http://127.0.0.1:" + server.localAddress().getPort();
        }

        private HttpResponse<String> send(
                String path,
                String method,
                String body,
                boolean authenticated
        ) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create(base + path))
                    .header("Accept", "application/json");
            if (authenticated) {
                request.header("Authorization", "Bearer " + TOKEN);
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            switch (method) {
                case "GET" -> request.GET();
                case "POST" -> request.POST(bodyPublisher(body));
                case "PUT" -> request.PUT(bodyPublisher(body));
                case "DELETE" -> request.method(
                        "DELETE",
                        bodyPublisher(body)
                );
                case "PATCH" -> request.method(
                        "PATCH",
                        bodyPublisher(body)
                );
                default -> throw new IllegalArgumentException(
                        "unsupported test method: " + method
                );
            }
            return client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        private static HttpRequest.BodyPublisher bodyPublisher(String body) {
            return body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static final class FailedNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.FAILED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.FAILED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }
}
