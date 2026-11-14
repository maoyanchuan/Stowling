package dev.stowling.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.ConfigValidationException;
import dev.stowling.control.ActiveTaskConflictException;
import dev.stowling.control.AuditRequest;
import dev.stowling.control.ConfigCoordinatePointsUpdateRequest;
import dev.stowling.control.ConfigCoordinatesUpdateRequest;
import dev.stowling.control.ContainerNotFoundException;
import dev.stowling.control.InvalidConfigCoordinatesException;
import dev.stowling.control.InvalidCoordinatePointsException;
import dev.stowling.control.InvalidPickupConfigException;
import dev.stowling.control.InvalidPickupRequestException;
import dev.stowling.control.PickupConfigUpdateRequest;
import dev.stowling.control.PickupRequest;
import dev.stowling.control.PickupSiteNotFoundException;
import dev.stowling.control.PickupWorkflowNotFoundException;
import dev.stowling.control.StowlingControlService;
import dev.stowling.control.TaskNotFoundException;
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
import java.util.Map;
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
    private static final String STATIC_CSP = String.join(
            "; ",
            "default-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "frame-src 'none'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "form-action 'self'"
    );
    private static final int HASH_BYTES = 32;

    private final InetSocketAddress bindAddress;
    private final StowlingControlService service;
    private final byte[] expectedTokenHash;
    private final int maxRequestBytes;
    private final StrictRequestParser requestParser = new StrictRequestParser();
    private final Map<String, StaticContent> staticContent;

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
        this.staticContent = loadStaticContent();
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
            String path = exchange.getRequestURI().getRawPath();
            if (staticContent.containsKey(path)) {
                serveStatic(exchange, staticContent.get(path));
                return;
            }
            if (!isVersionedApiPath(path)) {
                sendError(exchange, 404, "not_found", "Route not found");
                return;
            }
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
            String body = readRequestBody(exchange.getRequestBody());
            dispatch(exchange, route, body);
        } catch (RequestTooLargeException exception) {
            sendErrorQuietly(exchange, 413, "request_too_large", exception.getMessage());
        } catch (InvalidRequestException exception) {
            sendErrorQuietly(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InvalidCoordinatePointsException exception) {
            sendErrorQuietly(exchange, 400, "invalid_coordinate_points", exception.getMessage());
        } catch (InvalidConfigCoordinatesException exception) {
            sendErrorQuietly(exchange, 400, "invalid_config_coordinates", exception.getMessage());
        } catch (InvalidPickupConfigException | ConfigValidationException exception) {
            sendErrorQuietly(exchange, 400, "invalid_pickup_config", exception.getMessage());
        } catch (InvalidPickupRequestException exception) {
            sendErrorQuietly(exchange, 400, "invalid_pickup_request", exception.getMessage());
        } catch (MethodNotAllowedException exception) {
            exchange.getResponseHeaders().set("Allow", exception.allowedMethods());
            sendErrorQuietly(exchange, 405, "method_not_allowed", "Method not allowed");
        } catch (ConfigRevisionConflictException exception) {
            sendErrorQuietly(
                    exchange,
                    409,
                    "config_revision_conflict",
                    "Configuration was modified outside this request"
            );
        } catch (ActiveTaskConflictException exception) {
            sendErrorQuietly(
                    exchange,
                    409,
                    "active_task_conflict",
                    "Another pickup task is active"
            );
        } catch (PickupSiteNotFoundException exception) {
            sendErrorQuietly(
                    exchange,
                    404,
                    "pickup_site_not_found",
                    "Pickup site not found"
            );
        } catch (PickupWorkflowNotFoundException exception) {
            sendErrorQuietly(
                    exchange,
                    404,
                    "pickup_workflow_not_found",
                    "Pickup workflow not found"
            );
        } catch (TaskNotFoundException exception) {
            sendErrorQuietly(exchange, 404, "task_not_found", "Task not found");
        } catch (ContainerNotFoundException exception) {
            sendErrorQuietly(exchange, 404, "container_not_found", "Container not found");
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

    private static boolean isVersionedApiPath(String path) {
        return "/v1".equals(path) || path.startsWith("/v1/");
    }

    private static void serveStatic(HttpExchange exchange, StaticContent content)
            throws IOException {
        setStaticSecurityHeaders(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendError(exchange, 405, "method_not_allowed", "Method not allowed");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", content.contentType());
        if ("HEAD".equals(method)) {
            exchange.getResponseHeaders().set(
                    "Content-Length",
                    Integer.toString(content.bytes().length)
            );
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, content.bytes().length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content.bytes());
        }
    }

    private static void setStaticSecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", STATIC_CSP);
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private static Map<String, StaticContent> loadStaticContent() {
        return Map.of(
                "/",
                readStaticContent(
                        "stowling-web/index.html",
                        "text/html; charset=utf-8"
                ),
                "/app.js",
                readStaticContent(
                        "stowling-web/app.js",
                        "text/javascript; charset=utf-8"
                ),
                "/styles.css",
                readStaticContent(
                        "stowling-web/styles.css",
                        "text/css; charset=utf-8"
                )
        );
    }

    private static StaticContent readStaticContent(String resourceName, String contentType) {
        try (InputStream input = StowlingHttpServer.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing static resource: " + resourceName);
            }
            return new StaticContent(input.readAllBytes(), contentType);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read static resource: " + resourceName,
                    exception);
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
            case PICKUP_SITES -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, List.copyOf(service.pickupSites()));
            }
            case PICKUP_WORKFLOWS -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, List.copyOf(service.pickupWorkflows()));
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
            case CONFIG_COORDINATE_POINTS_GET -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.coordinatePoints());
            }
            case CONFIG_COORDINATE_POINTS_PUT -> {
                ConfigCoordinatePointsUpdateRequest request =
                        requestParser.parseCoordinatePointsUpdate(body);
                sendJson(exchange, 200, service.updateCoordinatePoints(request));
            }
            case CONFIG_COORDINATES_GET -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.configCoordinates());
            }
            case CONFIG_COORDINATES_PUT -> {
                ConfigCoordinatesUpdateRequest request =
                        requestParser.parseCoordinateUpdate(body);
                sendJson(exchange, 200, service.updateConfigCoordinates(request));
            }
            case CONFIG_PICKUP_GET -> {
                requestParser.requireEmpty(body);
                sendJson(exchange, 200, service.pickupConfig());
            }
            case CONFIG_PICKUP_PUT -> {
                PickupConfigUpdateRequest request =
                        requestParser.parsePickupConfigUpdate(body);
                sendJson(exchange, 200, service.updatePickupConfig(request));
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

    private RouteMatch matchRoute(HttpExchange exchange)
            throws InvalidRequestException, MethodNotAllowedException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        List<Route> routes = Route.forPath(path);
        if (!routes.isEmpty()) {
            for (Route route : routes) {
                if (route.method().equals(method)) {
                    return new RouteMatch(route, null);
                }
            }
            throw new MethodNotAllowedException(Route.allowedMethods(routes));
        }
        String prefix = "/v1/tasks/";
        String suffix = "/cancel";
        if (path.startsWith(prefix) && path.endsWith(suffix)) {
            String taskId = path.substring(prefix.length(), path.length() - suffix.length());
            if (!taskId.isEmpty() && taskId.indexOf('/') < 0) {
                if (Route.CANCEL.method().equals(method)) {
                    return new RouteMatch(Route.CANCEL, taskId);
                }
                throw new MethodNotAllowedException(Route.CANCEL.method());
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
        PICKUP_SITES("GET", "/v1/pickup-sites"),
        PICKUP_WORKFLOWS("GET", "/v1/pickup-workflows"),
        PICKUP("POST", "/v1/tasks/pickup"),
        CANCEL("POST", null),
        AUDITS("POST", "/v1/audits"),
        INVENTORY("GET", "/v1/inventory"),
        CONTAINERS("GET", "/v1/containers"),
        PAUSE("POST", "/v1/pause"),
        RESUME("POST", "/v1/resume"),
        CONFIG_RELOAD("POST", "/v1/config/reload"),
        CONFIG_COORDINATE_POINTS_GET("GET", "/v1/config/coordinate-points"),
        CONFIG_COORDINATE_POINTS_PUT("PUT", "/v1/config/coordinate-points"),
        CONFIG_COORDINATES_GET("GET", "/v1/config/coordinates"),
        CONFIG_COORDINATES_PUT("PUT", "/v1/config/coordinates"),
        CONFIG_PICKUP_GET("GET", "/v1/config/pickup"),
        CONFIG_PICKUP_PUT("PUT", "/v1/config/pickup");

        private final String method;
        private final String path;

        Route(String method, String path) {
            this.method = method;
            this.path = path;
        }

        private String method() {
            return method;
        }

        private static List<Route> forPath(String path) {
            java.util.ArrayList<Route> result = new java.util.ArrayList<>();
            for (Route route : values()) {
                if (route.path != null && route.path.equals(path)) {
                    result.add(route);
                }
            }
            return List.copyOf(result);
        }

        private static String allowedMethods(List<Route> routes) {
            return routes.stream().map(Route::method).distinct().collect(
                    java.util.stream.Collectors.joining(", ")
            );
        }
    }

    private record RouteMatch(Route route, String taskId) {
    }

    private record StaticContent(byte[] bytes, String contentType) {
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
