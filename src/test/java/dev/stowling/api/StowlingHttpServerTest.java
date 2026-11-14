package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.control.FakeControlService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StowlingHttpServerTest {
    private static final String TOKEN = "test-token-with-at-least-thirty-two-characters";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private FakeControlService service;
    private StowlingHttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        service = new FakeControlService();
        InetSocketAddress address = new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                0
        );
        server = new StowlingHttpServer(address, service, TOKEN, 4096);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void rejectsMissingAndWrongTokensBeforeCallingService() throws Exception {
        HttpResponse<String> missing = send("GET", "/v1/status", null, null);
        HttpResponse<String> wrong = send("GET", "/v1/status", null, "wrong-token");
        HttpResponse<String> unknownApi = send("GET", "/v1/not-real", null, null);

        assertEquals(401, missing.statusCode());
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknownApi.statusCode());
        assertTrue(missing.body().contains("\"code\":\"unauthorized\""));
        assertEquals(0, service.calls());
    }

    @Test
    void requiresAuthenticationForEveryApiRoute() throws Exception {
        String cancelPath = "/v1/tasks/" + FakeControlService.TASK_ID + "/cancel";
        String coordinatePointsPath = "/v1/config/coordinate-points";
        String coordinatePointsBody = coordinatePointsUpdate("revision-1");
        String automationPath = "/v1/config/automation-workflows";
        String automationBody = automationUpdate("automation-revision-1");
        ApiCall automationUpdateCall = new ApiCall("PUT", automationPath, automationBody);
        List<ApiCall> calls = List.of(
                new ApiCall("GET", "/v1/status", null),
                new ApiCall("GET", "/v1/tasks", null),
                new ApiCall("GET", "/v1/pickup-sites", null),
                new ApiCall("GET", "/v1/pickup-workflows", null),
                new ApiCall("GET", coordinatePointsPath, null),
                new ApiCall("PUT", coordinatePointsPath, coordinatePointsBody),
                new ApiCall("GET", "/v1/config/coordinates", null),
                new ApiCall("PUT", "/v1/config/coordinates", coordinateUpdate("revision-1")),
                new ApiCall("GET", "/v1/config/pickup", null),
                new ApiCall("PUT", "/v1/config/pickup", pickupUpdate("revision-1")),
                new ApiCall("GET", automationPath, null),
                automationUpdateCall,
                new ApiCall("GET", "/v1/automation-modules", null),
                new ApiCall("POST", "/v1/tasks/pickup", "{\"workflowId\":\"north\"}"),
                new ApiCall("POST", "/v1/tasks/automation", "{\"workflowId\":\"daily\"}"),
                new ApiCall("POST", cancelPath, null),
                new ApiCall("POST", "/v1/audits", "{\"containerIds\":[]}"),
                new ApiCall("GET", "/v1/inventory", null),
                new ApiCall("GET", "/v1/containers", null),
                new ApiCall("POST", "/v1/pause", null),
                new ApiCall("POST", "/v1/resume", null),
                new ApiCall("POST", "/v1/config/reload", null)
        );

        for (ApiCall call : calls) {
            assertEquals(401, send(call.method(), call.path(), call.body(), null).statusCode());
        }
        assertEquals(0, service.calls());
    }

    @Test
    void removedPauseAndResumeRoutesDoNotCallControlService() throws Exception {
        for (String path : List.of("/v1/pause", "/v1/resume")) {
            HttpResponse<String> authenticated = send("POST", path, null, TOKEN);
            HttpResponse<String> wrongToken = send("POST", path, null, "wrong-token");

            assertEquals(404, authenticated.statusCode());
            assertEquals(401, wrongToken.statusCode());
        }
        assertEquals(0, service.calls());
    }

    @Test
    void acceptsCorrectToken() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/status", null, TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"state\":\"RUNNING\""));
        assertEquals(1, service.calls());
    }

    @Test
    void servesPackagedWebConsoleWithoutAuthentication() throws Exception {
        HttpResponse<String> page = send("GET", "/", null, null);
        HttpResponse<String> alternatePage = send("GET", "/index.html", null, null);
        HttpResponse<String> script = send("GET", "/common.js", null, null);
        HttpResponse<String> tasksScript = send("GET", "/tasks.js", null, null);
        HttpResponse<String> icon = send("GET", "/icon.svg", null, null);
        HttpResponse<String> rasterIcon = send("GET", "/icon.png", null, null);
        HttpResponse<String> styles = send("GET", "/styles.css", null, null);

        assertEquals(200, page.statusCode());
        assertEquals(200, alternatePage.statusCode());
        assertEquals(200, script.statusCode());
        assertEquals(200, tasksScript.statusCode());
        assertEquals(200, icon.statusCode());
        assertEquals(200, rasterIcon.statusCode());
        assertEquals(200, styles.statusCode());
        assertTrue(page.body().contains("Stowling 控制台"));
        assertTrue(page.body().contains("href=\"/icon.svg\""));
        assertTrue(page.body().contains("href=\"/icon.png\""));
        assertTrue(page.body().contains("href=\"/styles.css\""));
        assertTrue(page.body().contains("src=\"/common.js\""));
        assertTrue(page.body().contains("src=\"/dashboard.js\""));
        assertFalse(page.body().contains("/app.js"));
        assertTrue(script.body().contains("Authorization"));
        assertTrue(script.body().contains("sessionStorage"));
        assertTrue(tasksScript.body().contains("/v1/config/automation-workflows"));
        assertTrue(tasksScript.body().contains("/v1/automation-modules"));
        assertTrue(tasksScript.body().contains("/v1/tasks/automation"));
        assertFalse(tasksScript.body().contains("innerHTML"));
        assertFalse(tasksScript.body().contains("localStorage"));
        assertTrue(styles.body().contains("@media (max-width: 760px)"));
        assertEquals(
                "text/html; charset=utf-8",
                page.headers().firstValue("Content-Type").orElseThrow()
        );
        assertEquals(
                "text/javascript; charset=utf-8",
                script.headers().firstValue("Content-Type").orElseThrow()
        );
        assertEquals(
                "image/svg+xml",
                icon.headers().firstValue("Content-Type").orElseThrow()
        );
        assertEquals(
                "image/png",
                rasterIcon.headers().firstValue("Content-Type").orElseThrow()
        );
        assertEquals(
                "text/css; charset=utf-8",
                styles.headers().firstValue("Content-Type").orElseThrow()
        );
        assertEquals(0, service.calls());
    }

    @Test
    void staticResourcesSetSecurityHeadersAndSupportHead() throws Exception {
        HttpResponse<String> page = send("GET", "/", null, null);
        HttpResponse<String> head = send("HEAD", "/styles.css", null, null);

        assertEquals(200, page.statusCode());
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(
                "nosniff",
                page.headers().firstValue("X-Content-Type-Options").orElseThrow()
        );
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals(
                "no-referrer",
                page.headers().firstValue("Referrer-Policy").orElseThrow()
        );
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("connect-src 'self'"));
        assertTrue(csp.contains("object-src 'none'"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("base-uri 'none'"));
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length());
    }

    @Test
    void staticResourcesRejectUnsupportedMethods() throws Exception {
        HttpResponse<String> response = send("POST", "/common.js", null, null);

        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"method_not_allowed\""));
        assertEquals(0, service.calls());
    }

    @Test
    void unknownAndTraversalLikeResourcesDoNotReadArbitraryFiles() throws Exception {
        HttpResponse<String> unknown = send("GET", "/missing.css", null, null);
        HttpResponse<String> unknownApi = send("GET", "/v1/not-real", null, TOKEN);
        HttpResponse<String> packagedPath = send(
                "GET",
                "/stowling-web/index.html",
                null,
                TOKEN
        );

        assertEquals(404, unknown.statusCode());
        assertEquals(404, unknownApi.statusCode());
        assertEquals(404, packagedPath.statusCode());
        assertEquals(0, service.calls());
    }

    @Test
    void servesEveryDocumentedRoute() throws Exception {
        assertStatus(200, "GET", "/v1/status", null);
        assertStatus(200, "GET", "/v1/tasks", null);
        assertStatus(200, "GET", "/v1/pickup-sites", null);
        assertStatus(200, "GET", "/v1/config/coordinate-points", null);
        assertStatus(200, "GET", "/v1/config/coordinates", null);
        assertStatus(200, "PUT", "/v1/config/coordinates", coordinateUpdate("revision-1"));
        assertStatus(
                200,
                "PUT",
                "/v1/config/coordinate-points",
                coordinatePointsUpdate("revision-2")
        );
        assertStatus(202, "POST", "/v1/tasks/pickup", "{\"workflowId\":\"north\"}");
        assertStatus(
                200,
                "POST",
                "/v1/tasks/" + FakeControlService.TASK_ID + "/cancel",
                null
        );
        assertStatus(202, "POST", "/v1/audits", "{\"containerIds\":[\"overflow\"]}");
        assertStatus(200, "GET", "/v1/inventory", null);
        assertStatus(200, "GET", "/v1/containers", null);
        assertStatus(404, "POST", "/v1/pause", null);
        assertStatus(404, "POST", "/v1/resume", null);
        assertStatus(200, "POST", "/v1/config/reload", null);
        assertStatus(200, "GET", "/v1/automation-modules", null);
        assertStatus(200, "GET", "/v1/config/automation-workflows", null);
        assertStatus(
                200,
                "PUT",
                "/v1/config/automation-workflows",
                automationUpdate("automation-revision-1")
        );
        assertStatus(
                202,
                "POST",
                "/v1/tasks/automation",
                "{\"workflowId\":\"daily\"}"
        );
    }

    @Test
    void readsAndSavesCoordinateConfiguration() throws Exception {
        HttpResponse<String> read = send(
                "GET",
                "/v1/config/coordinates",
                null,
                TOKEN
        );
        HttpResponse<String> saved = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("revision-1"),
                TOKEN
        );

        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"revision\":\"revision-1\""));
        assertTrue(read.body().contains("\"key\":\"container/overflow\""));
        assertTrue(read.body().contains("\"position\":{\"x\":1,\"y\":-44,\"z\":4}"));
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("\"revision\":\"revision-2\""));
        assertTrue(saved.body().contains("\"position\":{\"x\":2,\"y\":-43,\"z\":5}"));
    }

    @Test
    void readsAndSavesNamedCoordinatePoints() throws Exception {
        HttpResponse<String> read = send(
                "GET",
                "/v1/config/coordinate-points",
                null,
                TOKEN
        );
        HttpResponse<String> saved = send(
                "PUT",
                "/v1/config/coordinate-points",
                coordinatePointsUpdate("revision-1"),
                TOKEN
        );

        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"revision\":\"revision-1\""));
        assertTrue(read.body().contains("\"id\":\"north-position\""));
        assertTrue(read.body().contains("\"name\":\"North position\""));
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("\"revision\":\"revision-2\""));
        assertTrue(saved.body().contains("\"position\":{\"x\":2,\"y\":-44,\"z\":4}"));
    }

    @Test
    void rejectsStrictCoordinatePointJsonViolations() throws Exception {
        String valid = coordinatePointsUpdate("revision-1");
        String duplicate = valid.replace(
                "\"id\":\"north-cache-position\"",
                "\"id\":\"north-position\""
        );
        String invalidId = valid.replace(
                "\"id\":\"north-position\"",
                "\"id\":\"North Position\""
        );
        String longId = valid.replace(
                "\"id\":\"north-position\"",
                "\"id\":\"" + "a".repeat(65) + "\""
        );
        String emptyName = valid.replace(
                "\"name\":\"North position\"",
                "\"name\":\" \""
        );
        String unknown = valid.replace(
                "\"position\":{\"x\":2,\"y\":-44,\"z\":4}",
                "\"position\":{\"x\":2,\"y\":-44,\"z\":4},\"query\":\"x\""
        );
        String malformed = "{\"revision\":\"revision-1\",\"coordinatePoints\":[";

        List<HttpResponse<String>> responses = List.of(
                send("PUT", "/v1/config/coordinate-points", duplicate, TOKEN),
                send("PUT", "/v1/config/coordinate-points", invalidId, TOKEN),
                send("PUT", "/v1/config/coordinate-points", emptyName, TOKEN),
                send("PUT", "/v1/config/coordinate-points", unknown, TOKEN),
                send("PUT", "/v1/config/coordinate-points", malformed, TOKEN),
                send("PUT", "/v1/config/coordinate-points", longId, TOKEN)
        );

        assertTrue(responses.stream().allMatch(
                response -> response.statusCode() == 400
        ));
        assertTrue(responses.get(0).body().contains("Duplicate coordinate point id"));
        assertTrue(responses.get(1).body().contains("must match"));
        assertTrue(responses.get(2).body().contains("name must not be blank"));
        assertTrue(responses.get(3).body().contains("Unknown JSON field"));
        assertTrue(responses.get(4).body().contains("invalid_request"));
        assertTrue(responses.get(5).body().contains("id is too long"));
    }

    @Test
    void rejectsStrictCoordinateJsonViolations() throws Exception {
        String unknownBody = coordinateUpdate("revision-1").replace(
                "container/overflow",
                "unknown"
        );
        String duplicateBody = "{\"revision\":\"revision-1\",\"coordinates\":["
                + "{\"key\":\"container/overflow\",\"x\":2,\"y\":-43,\"z\":5},"
                + "{\"key\":\"container/overflow\",\"x\":2,\"y\":-43,\"z\":5}]}";
        HttpResponse<String> extra = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("revision-1").replace("\"z\":5", "\"z\":5,\"type\":\"x\""),
                TOKEN
        );
        HttpResponse<String> missing = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("revision-1").replace(",\"z\":5", ""),
                TOKEN
        );
        HttpResponse<String> decimal = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("revision-1").replace("\"x\":2", "\"x\":2.5"),
                TOKEN
        );
        HttpResponse<String> unknown = send(
                "PUT",
                "/v1/config/coordinates",
                unknownBody,
                TOKEN
        );
        HttpResponse<String> duplicateKey = send(
                "PUT",
                "/v1/config/coordinates",
                duplicateBody,
                TOKEN
        );
        HttpResponse<String> missingKey = send(
                "PUT",
                "/v1/config/coordinates",
                "{\"revision\":\"revision-1\",\"coordinates\":[]}",
                TOKEN
        );

        assertEquals(400, extra.statusCode());
        assertEquals(400, missing.statusCode());
        assertEquals(400, decimal.statusCode());
        assertEquals(400, unknown.statusCode());
        assertEquals(400, duplicateKey.statusCode());
        assertEquals(400, missingKey.statusCode());
        assertTrue(extra.body().contains("Unknown JSON field"));
        assertTrue(missing.body().contains("Missing JSON field: z"));
        assertTrue(decimal.body().contains("x must be an integer"));
        assertTrue(unknown.body().contains("invalid_config_coordinates"));
        assertTrue(duplicateKey.body().contains("Duplicate coordinate key"));
        assertTrue(missingKey.body().contains("Coordinate keys are missing"));
    }

    @Test
    void reportsCoordinateRevisionAndActiveTaskConflicts() throws Exception {
        HttpResponse<String> pointRevision = send(
                "PUT",
                "/v1/config/coordinate-points",
                coordinatePointsUpdate("stale"),
                TOKEN
        );
        HttpResponse<String> revision = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("stale"),
                TOKEN
        );
        assertStatus(202, "POST", "/v1/tasks/pickup", "{\"workflowId\":\"north\"}");
        HttpResponse<String> active = send(
                "PUT",
                "/v1/config/coordinates",
                coordinateUpdate("revision-1"),
                TOKEN
        );
        HttpResponse<String> activePickupConfig = send(
                "PUT",
                "/v1/config/pickup",
                pickupUpdate("revision-1"),
                TOKEN
        );
        HttpResponse<String> activeCoordinatePoints = send(
                "PUT",
                "/v1/config/coordinate-points",
                coordinatePointsUpdate("revision-1"),
                TOKEN
        );

        assertEquals(409, pointRevision.statusCode());
        assertTrue(pointRevision.body().contains("\"code\":\"config_revision_conflict\""));
        assertEquals(409, revision.statusCode());
        assertTrue(revision.body().contains("\"code\":\"config_revision_conflict\""));
        assertEquals(409, active.statusCode());
        assertTrue(active.body().contains("\"code\":\"active_task_conflict\""));
        assertEquals(409, activePickupConfig.statusCode());
        assertTrue(activePickupConfig.body().contains("active_task_conflict"));
        assertEquals(409, activeCoordinatePoints.statusCode());
        assertTrue(activeCoordinatePoints.body().contains("active_task_conflict"));
    }

    @Test
    void exposesNineAutomationModulesAndWorkflowConfiguration() throws Exception {
        HttpResponse<String> modules = send(
                "GET",
                "/v1/automation-modules",
                null,
                TOKEN
        );
        HttpResponse<String> workflows = send(
                "GET",
                "/v1/config/automation-workflows",
                null,
                TOKEN
        );
        HttpResponse<String> saved = send(
                "PUT",
                "/v1/config/automation-workflows",
                automationUpdate("automation-revision-1"),
                TOKEN
        );
        HttpResponse<String> stale = send(
                "PUT",
                "/v1/config/automation-workflows",
                automationUpdate("automation-revision-1"),
                TOKEN
        );

        assertEquals(200, modules.statusCode());
        assertEquals(9, occurrences(modules.body(), "\"type\":"));
        assertTrue(modules.body().contains("\"name\":\"NAVIGATE\""));
        assertTrue(modules.body().contains("\"parameters\":[]"));
        assertEquals(200, workflows.statusCode());
        assertTrue(workflows.body().contains("\"revision\":\"automation-revision-1\""));
        assertTrue(workflows.body().contains("\"id\":\"daily\""));
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("\"revision\":\"automation-revision-2\""));
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("config_revision_conflict"));
    }

    @Test
    void startsAutomationAndRejectsConfigurationWhileItIsActive() throws Exception {
        HttpResponse<String> started = send(
                "POST",
                "/v1/tasks/automation",
                "{\"workflowId\":\"daily\"}",
                TOKEN
        );
        HttpResponse<String> conflict = send(
                "PUT",
                "/v1/config/automation-workflows",
                automationUpdate("automation-revision-1"),
                TOKEN
        );

        assertEquals(202, started.statusCode());
        assertTrue(started.body().contains("\"kind\":\"AUTOMATION\""));
        assertTrue(started.body().contains("\"workflowId\":\"daily\""));
        assertTrue(started.body().contains("\"currentStepId\":\"wait\""));
        assertTrue(started.body().contains("\"stepIndex\":0"));
        assertTrue(started.body().contains("\"stepCount\":1"));
        assertTrue(started.body().contains("\"moduleState\":\"{}\""));
        assertTrue(started.body().contains("\"stage\":\"READY\""));
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("active_task_conflict"));
    }

    @Test
    void rejectsStrictAutomationJsonAndUnknownWorkflow() throws Exception {
        String valid = automationUpdate("automation-revision-1");
        String duplicate = valid.replace(
                "\"name\":\"Daily automation\"",
                "\"name\":\"Daily automation\",\"name\":\"Duplicate\""
        );
        String unknown = valid.replace(
                "\"steps\":",
                "\"extra\":true,\"steps\":"
        );
        String wrongType = "{\"revision\":\"automation-revision-1\","
                + "\"automationWorkflows\":[{\"id\":\"daily\","
                + "\"name\":\"Daily automation\",\"steps\":true}]}";
        String invalidParameter = valid.replace(
                "\"seconds\":\"1\"",
                "\"seconds\":\"0\""
        );
        String unknownParameter = valid.replace(
                "\"seconds\":\"1\"",
                "\"seconds\":\"1\",\"query\":\"x\""
        );
        String longParameter = valid.replace(
                "\"seconds\":\"1\"",
                "\"seconds\":\"" + "1".repeat(257) + "\""
        );

        List<HttpResponse<String>> invalid = List.of(
                send("PUT", "/v1/config/automation-workflows", duplicate, TOKEN),
                send("PUT", "/v1/config/automation-workflows", unknown, TOKEN),
                send("PUT", "/v1/config/automation-workflows", wrongType, TOKEN),
                send("PUT", "/v1/config/automation-workflows", invalidParameter, TOKEN),
                send("PUT", "/v1/config/automation-workflows", unknownParameter, TOKEN),
                send("PUT", "/v1/config/automation-workflows", longParameter, TOKEN),
                send("POST", "/v1/tasks/automation", "{\"workflowId\":[]}", TOKEN),
                send(
                "POST",
                "/v1/tasks/automation",
                "{\"workflowId\":\"missing\"}",
                TOKEN
        )
        );

        assertTrue(invalid.subList(0, 7).stream().allMatch(
                response -> response.statusCode() == 400
        ));
        assertTrue(invalid.get(0).body().contains("Duplicate JSON field"));
        assertTrue(invalid.get(1).body().contains("Unknown JSON field"));
        assertTrue(invalid.get(2).body().contains("must be an array"));
        assertTrue(invalid.get(3).body().contains("positive integer"));
        assertTrue(invalid.get(4).body().contains("Unknown JSON field"));
        assertTrue(invalid.get(5).body().contains("too long"));
        assertTrue(invalid.get(6).body().contains("must be a string"));
        assertEquals(404, invalid.get(7).statusCode());
        assertTrue(invalid.get(7).body().contains("automation_workflow_not_found"));
    }

    @Test
    void reportsRunningPickupConflictAsHttp409() throws Exception {
        assertStatus(202, "POST", "/v1/tasks/pickup", "{\"workflowId\":\"north\"}");

        HttpResponse<String> response = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"north\"}",
                TOKEN
        );

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"active_task_conflict\""));
        assertTrue(response.body().contains("Another pickup task is active"));
    }

    @Test
    void returnsConfiguredPickupSites() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/pickup-sites", null, TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\":\"north\""));
        assertTrue(response.body().contains("\"dimension\":\"minecraft:overworld\""));
        assertTrue(response.body().contains("\"center\":{\"x\":1,\"y\":-44,\"z\":4}"));
        assertTrue(response.body().contains("\"radius\":16"));
        assertTrue(response.body().contains("\"idleDelaySeconds\":60"));
    }

    @Test
    void exposesPickupWorkflowsAndPickupConfiguration() throws Exception {
        HttpResponse<String> workflows = send(
                "GET",
                "/v1/pickup-workflows",
                null,
                TOKEN
        );
        HttpResponse<String> config = send("GET", "/v1/config/pickup", null, TOKEN);

        assertEquals(200, workflows.statusCode());
        assertTrue(workflows.body().contains("\"id\":\"north\""));
        assertTrue(workflows.body().contains("\"priority\":100"));
        assertTrue(workflows.body().contains("\"idleDelay\":60"));
        assertEquals(200, config.statusCode());
        assertTrue(config.body().contains("\"pickupRegions\""));
        assertTrue(config.body().contains("\"cacheContainers\""));
        assertTrue(config.body().contains("\"pickupWorkflows\""));
        assertTrue(config.body().contains("\"type\":\"SPHERE\""));
        assertTrue(config.body().contains("\"standbyCoordinateId\":\"north-position\""));
        assertTrue(config.body().contains("\"centerCoordinateId\":\"north-position\""));
        assertTrue(config.body().contains("\"coordinateId\":\"north-cache-position\""));
        assertFalse(config.body().contains("\"coordinatePoints\""));
    }

    @Test
    void acceptsWorkflowAndSingleRegionPickupRequests() throws Exception {
        HttpResponse<String> workflow = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"north\"}",
                TOKEN
        );
        send(
                "POST",
                "/v1/tasks/22222222-2222-2222-2222-222222222222/cancel",
                null,
                TOKEN
        );
        HttpResponse<String> region = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"north\",\"regionId\":\"north\"}",
                TOKEN
        );

        assertEquals(202, workflow.statusCode());
        assertEquals(202, region.statusCode());
        assertTrue(region.body().contains("\"workflowId\":\"north\""));
        assertTrue(region.body().contains("\"currentRegionId\":\"north\""));
        assertTrue(region.body().contains("\"round\":0"));
        assertTrue(region.body().contains("\"stage\":\"NAVIGATE_PICKUP\""));
    }

    @Test
    void rejectsUnknownWorkflowAndForeignOrUnsafeRegion() throws Exception {
        HttpResponse<String> workflow = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"missing\"}",
                TOKEN
        );
        HttpResponse<String> foreignRegion = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"north\",\"regionId\":\"south\"}",
                TOKEN
        );
        HttpResponse<String> unsafe = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"north OR 1=1\"}",
                TOKEN
        );

        assertEquals(404, workflow.statusCode());
        assertEquals(400, foreignRegion.statusCode());
        assertEquals(400, unsafe.statusCode());
        assertTrue(foreignRegion.body().contains("invalid_pickup_request"));
        assertTrue(unsafe.body().contains("must match"));
    }

    @Test
    void atomicallyUpdatesPickupConfigurationAndRejectsStaleRevision()
            throws Exception {
        HttpResponse<String> saved = send(
                "PUT",
                "/v1/config/pickup",
                pickupUpdate("revision-1"),
                TOKEN
        );
        HttpResponse<String> stale = send(
                "PUT",
                "/v1/config/pickup",
                pickupUpdate("revision-1"),
                TOKEN
        );

        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("\"revision\":\"revision-2\""));
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("config_revision_conflict"));
    }

    @Test
    void rejectsDuplicateAndUnknownPickupConfigurationFields() throws Exception {
        String duplicate = pickupUpdate("revision-1").replace(
                "\"name\":\"North\"",
                "\"name\":\"North\",\"name\":\"Duplicate\""
        );
        String unknown = pickupUpdate("revision-1").replace(
                "\"radius\":16",
                "\"radius\":16,\"query\":\"x\""
        );

        HttpResponse<String> duplicateResponse = send(
                "PUT",
                "/v1/config/pickup",
                duplicate,
                TOKEN
        );
        HttpResponse<String> unknownResponse = send(
                "PUT",
                "/v1/config/pickup",
                unknown,
                TOKEN
        );

        assertEquals(400, duplicateResponse.statusCode());
        assertEquals(400, unknownResponse.statusCode());
        assertTrue(duplicateResponse.body().contains("Duplicate JSON field"));
        assertTrue(unknownResponse.body().contains("Unknown JSON field"));
    }

    @Test
    void reportsMissingResourcesWithSpecificErrors() throws Exception {
        HttpResponse<String> pickupSite = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"missing\"}",
                TOKEN
        );
        HttpResponse<String> task = send(
                "POST",
                "/v1/tasks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/cancel",
                null,
                TOKEN
        );
        HttpResponse<String> container = send(
                "POST",
                "/v1/audits",
                "{\"containerIds\":[\"missing\"]}",
                TOKEN
        );

        assertApiError(
                pickupSite,
                "pickup_workflow_not_found",
                "Pickup workflow not found"
        );
        assertApiError(task, "task_not_found", "Task not found");
        assertApiError(container, "container_not_found", "Container not found");
    }

    @Test
    void rejectsMalformedAndAmbiguousJson() throws Exception {
        HttpResponse<String> malformed = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":",
                TOKEN
        );
        HttpResponse<String> duplicate = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"workflowId\":\"a\",\"workflowId\":\"b\"}",
                TOKEN
        );
        HttpResponse<String> wrongType = send(
                "POST",
                "/v1/audits",
                "{\"containerIds\":true}",
                TOKEN
        );

        assertEquals(400, malformed.statusCode());
        assertEquals(400, duplicate.statusCode());
        assertEquals(400, wrongType.statusCode());
        assertTrue(duplicate.body().contains("Duplicate JSON field"));
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        String body = "{\"workflowId\":\"" + "a".repeat(5000) + "\"}";

        HttpResponse<String> response = send(
                "POST",
                "/v1/tasks/pickup",
                body,
                TOKEN
        );

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"request_too_large\""));
        assertEquals(0, service.calls());
    }

    @Test
    void reportsMethodErrorsWithAllowHeader() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/status", null, TOKEN);
        HttpResponse<String> coordinates = send(
                "POST",
                "/v1/config/coordinates",
                null,
                TOKEN
        );
        HttpResponse<String> coordinatePoints = send(
                "POST",
                "/v1/config/coordinate-points",
                null,
                TOKEN
        );

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"method_not_allowed\""));
        assertEquals(405, coordinates.statusCode());
        assertEquals(
                "GET, PUT",
                coordinates.headers().firstValue("Allow").orElseThrow()
        );
        assertEquals(405, coordinatePoints.statusCode());
        assertEquals(
                "GET, PUT",
                coordinatePoints.headers().firstValue("Allow").orElseThrow()
        );
    }

    @Test
    void rejectsInvalidAndUnknownTaskIds() throws Exception {
        HttpResponse<String> malformed = send(
                "POST",
                "/v1/tasks/1-1-1-1-1/cancel",
                null,
                TOKEN
        );
        HttpResponse<String> encoded = send(
                "POST",
                "/v1/tasks/%311111111-1111-1111-1111-11111111111/cancel",
                null,
                TOKEN
        );
        HttpResponse<String> unknown = send(
                "POST",
                "/v1/tasks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/cancel",
                null,
                TOKEN
        );

        assertEquals(400, malformed.statusCode());
        assertEquals(400, encoded.statusCode());
        assertEquals(404, unknown.statusCode());
        assertTrue(malformed.body().contains("canonical UUID"));
        assertTrue(unknown.body().contains("\"code\":\"task_not_found\""));
    }

    @Test
    void handlesConcurrentRequests() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            HttpRequest request = request("GET", "/v1/status", null, TOKEN);
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);

        for (CompletableFuture<HttpResponse<String>> future : futures) {
            assertEquals(200, future.join().statusCode());
        }
        assertEquals(32, service.calls());
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        service.setFailStatus(true);

        HttpResponse<String> response = send("GET", "/v1/status", null, TOKEN);

        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"internal_error\""));
        assertFalse(response.body().contains("sensitive-account-data"));
    }

    @Test
    void supportsCleanStopAndRestart() throws Exception {
        int stoppedPort = server.localAddress().getPort();
        assertTrue(server.isRunning());
        assertThrows(IllegalStateException.class, server::start);

        server.stop();

        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::localAddress);
        HttpRequest stoppedRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + stoppedPort + "/v1/status"))
                .timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build();
        assertThrows(
                IOException.class,
                () -> client.send(stoppedRequest, HttpResponse.BodyHandlers.ofString())
        );

        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
        assertStatus(200, "GET", "/v1/status", null);
    }

    @Test
    void defaultFactoryBindsToIpv4Loopback() throws Exception {
        try (StowlingHttpServer loopback = StowlingHttpServer.loopback(
                new FakeControlService(),
                TOKEN,
                0
        )) {
            loopback.start();

            assertEquals(
                    "127.0.0.1",
                    loopback.localAddress().getAddress().getHostAddress()
            );
        }
    }

    private void assertStatus(int status, String method, String path, String body)
            throws Exception {
        assertEquals(status, send(method, path, body, TOKEN).statusCode());
    }

    private static void assertApiError(
            HttpResponse<String> response,
            String code,
            String message
    ) {
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""));
        assertTrue(response.body().contains("\"message\":\"" + message + "\""));
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String token
    ) throws Exception {
        return client.send(
                request(method, path, body, token),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest request(String method, String path, String body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String coordinateUpdate(String revision) {
        return "{\"revision\":\"" + revision + "\",\"coordinates\":[{"
                + "\"key\":\"container/overflow\","
                + "\"x\":2,\"y\":-43,\"z\":5}]}";
    }

    private static String coordinatePointsUpdate(String revision) {
        return "{\"revision\":\"" + revision + "\",\"coordinatePoints\":["
                + "{\"id\":\"north-position\",\"name\":\"North position\","
                + "\"dimension\":\"minecraft:overworld\","
                + "\"position\":{\"x\":2,\"y\":-44,\"z\":4}},"
                + "{\"id\":\"north-cache-position\","
                + "\"name\":\"North cache position\","
                + "\"dimension\":\"minecraft:overworld\","
                + "\"position\":{\"x\":2,\"y\":-44,\"z\":4}}]}";
    }

    private static String pickupUpdate(String revision) {
        return "{\"revision\":\"" + revision + "\","
                + "\"pickupRegions\":[{\"id\":\"north\",\"name\":\"North\","
                + "\"dimension\":\"minecraft:overworld\","
                + "\"standbyCoordinateId\":\"north-position\","
                + "\"standbyPosition\":{\"x\":1,\"y\":-44,\"z\":4},"
                + "\"shape\":{\"type\":\"SPHERE\","
                + "\"centerCoordinateId\":\"north-position\","
                + "\"center\":{\"x\":1,\"y\":-44,\"z\":4},\"radius\":16}}],"
                + "\"cacheContainers\":[{\"id\":\"north-cache\","
                + "\"name\":\"North cache\",\"dimension\":\"minecraft:overworld\","
                + "\"coordinateId\":\"north-cache-position\","
                + "\"position\":{\"x\":2,\"y\":-44,\"z\":4},\"type\":\"CHEST\"}],"
                + "\"pickupWorkflows\":[{\"id\":\"north\",\"name\":\"North\","
                + "\"dimension\":\"minecraft:overworld\","
                + "\"regions\":[{\"regionId\":\"north\","
                + "\"priority\":100,\"idleDelay\":60}],"
                + "\"cacheIds\":[\"north-cache\"],"
                + "\"cachePolicy\":\"NEAREST_AVAILABLE\","
                + "\"classificationTiming\":\"AFTER_ROUND\","
                + "\"completionMode\":\"MANUAL\",\"completionIdleDelay\":300}]}";
    }

    private static String automationUpdate(String revision) {
        return "{\"revision\":\"" + revision + "\","
                + "\"automationWorkflows\":[{\"id\":\"daily\","
                + "\"name\":\"Daily automation\",\"steps\":[{\"id\":\"wait\","
                + "\"type\":\"WAIT\",\"parameters\":{\"seconds\":\"1\"}}]}]}";
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private record ApiCall(String method, String path, String body) {
    }
}
