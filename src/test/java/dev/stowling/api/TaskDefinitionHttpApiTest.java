package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.TaskDefinitionNotFoundException;
import dev.stowling.control.PickupConfigSnapshot;
import dev.stowling.control.StowlingControlService;
import dev.stowling.control.TaskInfo;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskScheduleInfo;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDefinitionHttpApiTest {
    private static final String TOKEN = "task-definition-test-token";
    private static final String DEFINITION_PATH = "/v1/task-definitions/AUTOMATION/daily";
    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    private static final TaskDefinition DEFINITION = definition();
    private static final VersionedTaskDefinition VERSIONED =
            new VersionedTaskDefinition("revision-1", DEFINITION);
    private static final TaskScheduleInfo SCHEDULE = new TaskScheduleInfo(
            DEFINITION.key(),
            true,
            "IDLE",
            TaskPriority.MEDIUM,
            NOW.plusSeconds(60),
            null,
            "",
            null
    );

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String failure;
    private StowlingHttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        StowlingControlService service = (StowlingControlService) Proxy.newProxyInstance(
                StowlingControlService.class.getClassLoader(),
                new Class<?>[] {StowlingControlService.class},
                (proxy, method, arguments) -> {
                    if ("missing".equals(failure)
                            && "taskDefinition".equals(method.getName())) {
                        throw new TaskDefinitionNotFoundException(DEFINITION.key());
                    }
                    if ("conflict".equals(failure)
                            && "updateTaskDefinition".equals(method.getName())) {
                        throw new ConfigRevisionConflictException();
                    }
                    return switch (method.getName()) {
                        case "taskResources" -> {
                            calls.add("task-resources");
                            yield taskResources();
                        }
                        case "updateTaskResources" -> {
                            calls.add("update-task-resources");
                            yield taskResources();
                        }
                        case "taskDefinitions" -> {
                            calls.add("list");
                            yield List.of(DEFINITION);
                        }
                        case "taskScheduling" -> {
                            calls.add("scheduling");
                            yield List.of(SCHEDULE);
                        }
                        case "taskDefinition" -> {
                            calls.add("get");
                            yield VERSIONED;
                        }
                        case "createTaskDefinition" -> {
                            calls.add("create");
                            yield VERSIONED;
                        }
                        case "updateTaskDefinition" -> {
                            calls.add("update");
                            yield VERSIONED;
                        }
                        case "deleteTaskDefinition" -> {
                            calls.add("delete");
                            yield null;
                        }
                        case "enableTaskDefinition" -> {
                            calls.add("enable");
                            yield null;
                        }
                        case "disableTaskDefinition" -> {
                            calls.add("disable");
                            yield null;
                        }
                        case "runTaskDefinition" -> {
                            calls.add("run");
                            yield taskInfo();
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                }
        );
        server = new StowlingHttpServer(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                service,
                TOKEN,
                16 * 1024
        );
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void requiresAuthenticationBeforeTaskDefinitionDispatch() throws Exception {
        HttpResponse<String> response = send("GET", DEFINITION_PATH, null, null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"unauthorized\""));
        assertTrue(calls.isEmpty());
    }

    @Test
    void servesTaskDefinitionCollectionAndMutationRoutes() throws Exception {
        HttpResponse<String> list = send(
                "GET",
                "/v1/task-definitions",
                null,
                TOKEN
        );
        HttpResponse<String> create = send(
                "POST",
                "/v1/task-definitions",
                ApiJson.encode(DEFINITION),
                TOKEN
        );
        HttpResponse<String> get = send("GET", DEFINITION_PATH, null, TOKEN);
        HttpResponse<String> update = send(
                "PUT",
                DEFINITION_PATH,
                versionedBody(),
                TOKEN
        );
        HttpResponse<String> delete = send(
                "DELETE",
                DEFINITION_PATH,
                "{\"revision\":\"revision-1\"}",
                TOKEN
        );

        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"tasks\":["));
        assertTrue(list.body().contains("\"scheduling\":["));
        assertEquals(201, create.statusCode());
        assertTrue(create.body().contains("\"revision\":\"revision-1\""));
        assertEquals(200, get.statusCode());
        assertEquals(200, update.statusCode());
        assertEquals(204, delete.statusCode());
        assertEquals(
                List.of("list", "scheduling", "create", "get", "update", "delete"),
                calls
        );
    }

    @Test
    void servesSchedulingActionsAndRun() throws Exception {
        HttpResponse<String> enable = send(
                "POST",
                DEFINITION_PATH + "/enable",
                null,
                TOKEN
        );
        HttpResponse<String> disable = send(
                "POST",
                DEFINITION_PATH + "/disable",
                null,
                TOKEN
        );
        HttpResponse<String> run = send(
                "POST",
                DEFINITION_PATH + "/run",
                null,
                TOKEN
        );

        assertEquals(200, enable.statusCode());
        assertTrue(enable.body().contains("\"scheduling\":["));
        assertEquals(200, disable.statusCode());
        assertTrue(disable.body().contains("\"scheduling\":["));
        assertEquals(202, run.statusCode());
        assertTrue(run.body().contains("\"kind\":\"AUTOMATION\""));
        assertEquals(List.of("enable", "scheduling", "disable", "scheduling", "run"), calls);
    }

    @Test
    void rejectsUnknownAndDuplicateTaskDefinitionFieldsBeforeServiceCall()
            throws Exception {
        String encoded = ApiJson.encode(DEFINITION).stripTrailing();
        String unknown = encoded.substring(0, encoded.length() - 1)
                + ",\"unexpected\":true}";
        String duplicate = encoded.replace(
                "\"kind\":\"AUTOMATION\"",
                "\"kind\":\"AUTOMATION\",\"kind\":\"AUTOMATION\""
        );

        HttpResponse<String> unknownResponse = send(
                "POST",
                "/v1/task-definitions",
                unknown,
                TOKEN
        );
        HttpResponse<String> duplicateResponse = send(
                "POST",
                "/v1/task-definitions",
                duplicate,
                TOKEN
        );

        assertEquals(400, unknownResponse.statusCode());
        assertEquals(400, duplicateResponse.statusCode());
        assertTrue(calls.isEmpty());
        assertFalse(unknownResponse.body().isBlank());
    }

    @Test
    void pauseAndResumeAreNoLongerOperableRoutes() throws Exception {
        assertEquals(404, send("POST", "/v1/pause", null, TOKEN).statusCode());
        assertEquals(404, send("POST", "/v1/resume", null, TOKEN).statusCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void mapsMissingDefinitionsAndRevisionConflicts() throws Exception {
        failure = "missing";
        HttpResponse<String> missing = send("GET", DEFINITION_PATH, null, TOKEN);

        failure = "conflict";
        HttpResponse<String> conflict = send(
                "PUT",
                DEFINITION_PATH,
                versionedBody(),
                TOKEN
        );

        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("\"code\":\"task_definition_not_found\""));
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("\"code\":\"config_revision_conflict\""));
    }

    @Test
    void servesIndependentTaskResourceReadAndUpdate() throws Exception {
        String body = """
                {
                  "revision":"revision-1",
                  "pickupRegions":[],
                  "cacheContainers":[]
                }
                """;

        HttpResponse<String> unauthorized = send(
                "GET",
                "/v1/config/task-resources",
                null,
                null
        );
        HttpResponse<String> get = send(
                "GET",
                "/v1/config/task-resources",
                null,
                TOKEN
        );
        HttpResponse<String> put = send(
                "PUT",
                "/v1/config/task-resources",
                body,
                TOKEN
        );
        HttpResponse<String> rejected = send(
                "PUT",
                "/v1/config/task-resources",
                body.stripTrailing().replace(
                        "}",
                        ",\"pickupWorkflows\":[]}"
                ),
                TOKEN
        );

        assertEquals(401, unauthorized.statusCode());
        assertEquals(200, get.statusCode());
        assertTrue(get.body().contains("\"pickupRegions\":[]"));
        assertTrue(get.body().contains("\"cacheContainers\":[]"));
        assertTrue(get.body().contains("\"pickupWorkflows\":[]"));
        assertEquals(200, put.statusCode());
        assertEquals(400, rejected.statusCode());
        assertEquals(List.of("task-resources", "update-task-resources"), calls);
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String token
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String versionedBody() {
        return "{\"revision\":\"revision-1\",\"definition\":"
                + ApiJson.encode(DEFINITION) + "}";
    }

    private static TaskDefinition definition() {
        TaskSettings settings = new TaskSettings(
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
        return new TaskDefinition(
                TaskKey.AUTOMATION,
                "daily",
                "Daily",
                settings,
                null,
                List.of(new AutomationStep(
                        "wait",
                        AutomationStepType.WAIT,
                        Map.of("seconds", "1")
                ))
        );
    }

    private static TaskInfo taskInfo() {
        return new TaskInfo(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                TaskKey.AUTOMATION,
                "PENDING",
                "IDLE_WAITING",
                NOW,
                NOW,
                null
        );
    }

    private static PickupConfigSnapshot taskResources() {
        return new PickupConfigSnapshot(
                "revision-1",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
