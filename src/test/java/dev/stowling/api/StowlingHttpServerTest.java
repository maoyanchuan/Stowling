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
        server = new StowlingHttpServer(address, service, TOKEN, 128);
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
        List<ApiCall> calls = List.of(
                new ApiCall("GET", "/v1/status", null),
                new ApiCall("GET", "/v1/tasks", null),
                new ApiCall("GET", "/v1/pickup-sites", null),
                new ApiCall("POST", "/v1/tasks/pickup", "{\"siteId\":\"north\"}"),
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
    void acceptsCorrectToken() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/status", null, TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"state\":\"RUNNING\""));
        assertEquals(1, service.calls());
    }

    @Test
    void servesPackagedWebConsoleWithoutAuthentication() throws Exception {
        HttpResponse<String> page = send("GET", "/", null, null);
        HttpResponse<String> script = send("GET", "/app.js", null, null);
        HttpResponse<String> styles = send("GET", "/styles.css", null, null);

        assertEquals(200, page.statusCode());
        assertEquals(200, script.statusCode());
        assertEquals(200, styles.statusCode());
        assertTrue(page.body().contains("Stowling 控制台"));
        assertTrue(page.body().contains("href=\"/styles.css\""));
        assertTrue(page.body().contains("src=\"/app.js\""));
        assertTrue(script.body().contains("Authorization"));
        assertTrue(script.body().contains("sessionStorage"));
        assertTrue(script.body().contains("/v1/pickup-sites"));
        assertTrue(script.body().contains("pickup_site_not_found"));
        assertTrue(script.body().contains("配置中找不到这个拾取点"));
        assertTrue(page.body().contains("<select id=\"site-select\""));
        assertFalse(page.body().contains("id=\"site-input\""));
        assertFalse(script.body().contains("localStorage"));
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
        HttpResponse<String> response = send("POST", "/app.js", null, null);

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
        assertStatus(202, "POST", "/v1/tasks/pickup", "{\"siteId\":\"north\"}");
        assertStatus(
                200,
                "POST",
                "/v1/tasks/" + FakeControlService.TASK_ID + "/cancel",
                null
        );
        assertStatus(202, "POST", "/v1/audits", "{\"containerIds\":[\"overflow\"]}");
        assertStatus(200, "GET", "/v1/inventory", null);
        assertStatus(200, "GET", "/v1/containers", null);
        assertStatus(200, "POST", "/v1/pause", null);
        assertStatus(200, "POST", "/v1/resume", null);
        assertStatus(200, "POST", "/v1/config/reload", null);
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
    void reportsMissingResourcesWithSpecificErrors() throws Exception {
        HttpResponse<String> pickupSite = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"siteId\":\"missing\"}",
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

        assertApiError(pickupSite, "pickup_site_not_found", "Pickup site not found");
        assertApiError(task, "task_not_found", "Task not found");
        assertApiError(container, "container_not_found", "Container not found");
    }

    @Test
    void rejectsMalformedAndAmbiguousJson() throws Exception {
        HttpResponse<String> malformed = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"siteId\":",
                TOKEN
        );
        HttpResponse<String> duplicate = send(
                "POST",
                "/v1/tasks/pickup",
                "{\"siteId\":\"a\",\"siteId\":\"b\"}",
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
        String body = "{\"siteId\":\"" + "a".repeat(200) + "\"}";

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

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"method_not_allowed\""));
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

    private record ApiCall(String method, String path, String body) {
    }
}
