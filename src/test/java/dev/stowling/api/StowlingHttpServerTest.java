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

        assertEquals(401, missing.statusCode());
        assertEquals(401, wrong.statusCode());
        assertTrue(missing.body().contains("\"code\":\"unauthorized\""));
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
    void servesEveryDocumentedRoute() throws Exception {
        assertStatus(200, "GET", "/v1/status", null);
        assertStatus(200, "GET", "/v1/tasks", null);
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
}
