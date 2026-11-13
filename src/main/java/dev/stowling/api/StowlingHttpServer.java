package dev.stowling.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stowling.control.AuditRequest;
import dev.stowling.control.PickupRequest;
import dev.stowling.control.StowlingControlService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class StowlingHttpServer implements AutoCloseable {
    public static final int DEFAULT_MAX_REQUEST_BYTES = 64 * 1024;

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final int HASH_BYTES = 32;

    private final InetSocketAddress bindAddress;
    private final StowlingControlService service;
    private final byte[] expectedTokenHash;
    private final int maxRequestBytes;
    private final StrictRequestParser requestParser = new StrictRequestParser();

    private HttpServer server;
    private ExecutorService executor;

    public StowlingHttpServer(
            InetSocketAddress bindAddress,
            StowlingControlService service,
            String bearerToken,
            int maxRequestBytes
    ) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(bearerToken, "bearerToken");
        if (bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken must not be blank");
        }
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes must be positive");
        }
        this.expectedTokenHash = hashToken(bearerToken);
        this.maxRequestBytes = maxRequestBytes;
    }

    public static StowlingHttpServer loopback(
            StowlingControlService service,
            String bearerToken,
            int port
    ) {
        return new StowlingHttpServer(
                loopbackAddress(port),
                service,
                bearerToken,
                DEFAULT_MAX_REQUEST_BYTES
        );
    }

    public static StowlingHttpServer loopbackWithTokenFile(
            StowlingControlService service,
            Path tokenFile,
            int port
    ) {
        String bearerToken = new BearerTokenStore(tokenFile).loadOrCreate();
        return loopback(service, bearerToken, port);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Control listener is already running");
        }
        HttpServer createdServer = HttpServer.create(bindAddress, 0);
        ExecutorService createdExecutor = Executors.newFixedThreadPool(
                Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())),
                new ControlThreadFactory()
        );
        createdServer.createContext("/", this::handle);
        createdServer.setExecutor(createdExecutor);
        try {
            createdServer.start();
            server = createdServer;
            executor = createdExecutor;
        } catch (RuntimeException exception) {
            createdServer.stop(0);
            createdExecutor.shutdownNow();
            throw exception;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized InetSocketAddress localAddress() {
        if (server == null) {
            throw new IllegalStateException("Control listener is not running");
        }
        return server.getAddress();
    }

    public synchronized void stop() {
        HttpServer activeServer = server;
        ExecutorService activeExecutor = executor;
        server = null;
        executor = null;
        if (activeServer != null) {
            activeServer.stop(0);
        }
        if (activeExecutor != null) {
            activeExecutor.shutdownNow();
            try {
                activeExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) {
        try {
            if (!isAuthorized(exchange.getRequestHeaders())) {
                exchange.getResponseHeaders().set(
                        "WWW-Authenticate",
                        "Bearer realm=\"stowling-control\""
                );
                sendError(exchange, 401, "unauthorized", "A valid bearer token is required");
                return;
            }

            validateRequestTarget(exchange);
            validateDeclaredBodySize(exchange.getRequestHeaders());
            RouteMatch route = matchRoute(exchange);
            if (route == null) {
                sendError(exchange, 404, "not_found", "Route not found");
                return;
            }
            if (!route.route().method().equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", route.route().method());
                sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                return;
            }

            String body = readRequestBody(exchange.getRequestBody());
            dispatch(exchange, route, body);
        } catch (RequestTooLargeException exception) {
            sendErrorQuietly(exchange, 413, "request_too_large", exception.getMessage());
        } catch (InvalidRequestException exception) {
            sendErrorQuietly(exchange, 400, "invalid_request", exception.getMessage());
        } catch (NoSuchElementException exception) {
            sendErrorQuietly(exchange, 404, "task_not_found", "Task not found");
        } catch (Exception exception) {
            sendErrorQuietly(
                    exchange,
                    500,
                    "internal_error",
                    "The request could not be completed"
            );
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, RouteMatch match, String body)
            throws IOException, InvalidRequestException {
        switch (match.route()) {
            case STATUS -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.status());
            }
            case TASKS -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, List.copyOf(service.tasks()));
            }
            case PICKUP -> {
                PickupRequest request = requestParser.parsePickup(body);
                sendJson(exchange, 202, service.requestPickup(request));
            }
            case CANCEL -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.cancelTask(parseTaskId(match.taskId())));
            }
            case AUDITS -> {
                AuditRequest request = requestParser.parseAudit(body);
                sendJson(exchange, 202, service.requestAudit(request));
            }
            case INVENTORY -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, List.copyOf(service.inventory()));
            }
            case CONTAINERS -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, List.copyOf(service.containers()));
            }
            case PAUSE -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.pause());
            }
            case RESUME -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.resume());
            }
            case CONFIG_RELOAD -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.reloadConfig());
            }
        }
    }

    private boolean isAuthorized(Headers headers) {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) {
            return false;
        }
        String value = values.getFirst();
        int separator = value.indexOf(' ');
        if (separator <= 0 || separator != value.lastIndexOf(' ')) {
            return false;
        }
        String scheme = value.substring(0, separator);
        String token = value.substring(separator + 1);
        if (!"Bearer".equalsIgnoreCase(scheme) || token.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedTokenHash, hashToken(token));
    }

    private RouteMatch matchRoute(HttpExchange exchange) throws InvalidRequestException {
        String path = exchange.getRequestURI().getRawPath();
        Route route = Route.forPath(path);
        if (route != null) {
            return new RouteMatch(route, null);
        }
        String prefix = "/v1/tasks/";
        String suffix = "/cancel";
        if (path.startsWith(prefix) && path.endsWith(suffix)) {
            String taskId = path.substring(prefix.length(), path.length() - suffix.length());
            if (!taskId.isEmpty() && taskId.indexOf('/') < 0) {
                return new RouteMatch(Route.CANCEL, taskId);
            }
        }
        return null;
    }

    private static void validateRequestTarget(HttpExchange exchange)
            throws InvalidRequestException {
        if (exchange.getRequestURI().getRawQuery() != null) {
            throw new InvalidRequestException("Query parameters are not supported");
        }
        String path = exchange.getRequestURI().getRawPath();
        if (path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) {
            throw new InvalidRequestException("Encoded or ambiguous paths are not supported");
        }
    }

    private void validateDeclaredBodySize(Headers headers)
            throws InvalidRequestException, RequestTooLargeException {
        List<String> values = headers.get("Content-Length");
        if (values == null || values.isEmpty()) {
            return;
        }
        if (values.size() != 1) {
            throw new InvalidRequestException("Ambiguous Content-Length header");
        }
        try {
            long length = Long.parseLong(values.getFirst());
            if (length < 0) {
                throw new InvalidRequestException("Invalid Content-Length header");
            }
            if (length > maxRequestBytes) {
                throw new RequestTooLargeException();
            }
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("Invalid Content-Length header");
        }
    }

    private String readRequestBody(InputStream input)
            throws IOException, RequestTooLargeException, InvalidRequestException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxRequestBytes) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidRequestException("Request body must be valid UTF-8");
        }
    }

    private static UUID parseTaskId(String value) throws InvalidRequestException {
        if (value == null || value.length() != 36 || value.indexOf('%') >= 0) {
            throw new InvalidRequestException("Task id must be a canonical UUID");
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase(Locale.ROOT))) {
                throw new InvalidRequestException("Task id must be a canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("Task id must be a canonical UUID");
        }
    }

    private static void sendError(
            HttpExchange exchange,
            int status,
            String code,
            String message
    ) throws IOException {
        sendJson(exchange, status, new ApiErrorResponse(new ApiError(code, message)));
    }

    private static void sendErrorQuietly(
            HttpExchange exchange,
            int status,
            String code,
            String message
    ) {
        try {
            sendError(exchange, status, code, message);
        } catch (IOException | RuntimeException ignored) {
            // The client disconnected or a response was already committed.
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        byte[] bytes = ApiJson.encode(value).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", JSON_CONTENT_TYPE);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            if (result.length != HASH_BYTES) {
                throw new IllegalStateException("Unexpected token digest length");
            }
            return result;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static InetSocketAddress loopbackAddress(int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        } catch (IOException exception) {
            throw new IllegalStateException("IPv4 loopback is unavailable", exception);
        }
    }

    private enum Route {
        STATUS("GET", "/v1/status"),
        TASKS("GET", "/v1/tasks"),
        PICKUP("POST", "/v1/tasks/pickup"),
        CANCEL("POST", null),
        AUDITS("POST", "/v1/audits"),
        INVENTORY("GET", "/v1/inventory"),
        CONTAINERS("GET", "/v1/containers"),
        PAUSE("POST", "/v1/pause"),
        RESUME("POST", "/v1/resume"),
        CONFIG_RELOAD("POST", "/v1/config/reload");

        private final String method;
        private final String path;

        Route(String method, String path) {
            this.method = method;
            this.path = path;
        }

        private String method() {
            return method;
        }

        private static Route forPath(String path) {
            for (Route route : values()) {
                if (route.path != null && route.path.equals(path)) {
                    return route;
                }
            }
            return null;
        }
    }

    private record RouteMatch(Route route, String taskId) {
    }

    private static final class ControlThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    task,
                    "stowling-control-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
