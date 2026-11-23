package dev.stowling.v2.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stowling.api.BearerTokenStore;
import dev.stowling.v2.config.StartupConfiguration.Issue;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.v2.control.AutoClassifyDetectionRequest;
import dev.stowling.v2.control.AutoClassifyAreaRequest;
import dev.stowling.v2.control.AutoClassifyTaskRequest;
import dev.stowling.v2.control.AutoClassifyTaskUpdateRequest;
import dev.stowling.v2.control.AutoEatTask;
import dev.stowling.v2.control.CoordinateInUseException;
import dev.stowling.v2.control.CategoryBindingRequest;
import dev.stowling.v2.control.CategoryContainerOverrideRequest;
import dev.stowling.v2.control.CategoryZoneExpansionRequest;
import dev.stowling.v2.control.CategoryZoneRequest;
import dev.stowling.v2.control.CategoryZoneUpdateRequest;
import dev.stowling.v2.control.CoordinateRequest;
import dev.stowling.v2.control.CoordinateUpdateRequest;
import dev.stowling.v2.control.RevisionRequest;
import dev.stowling.v2.control.RegionRequest;
import dev.stowling.v2.control.RegionUpdateRequest;
import dev.stowling.v2.control.TaskRequest;
import dev.stowling.v2.control.TaskStepRequest;
import dev.stowling.v2.control.TaskInUseException;
import dev.stowling.v2.control.TaskFolderRequest;
import dev.stowling.v2.control.TaskFolderUpdateRequest;
import dev.stowling.v2.control.TaskTriggerRequest;
import dev.stowling.v2.control.TaskTriggerUpdateRequest;
import dev.stowling.v2.control.TaskUpdateRequest;
import dev.stowling.v2.control.V2ControlService;
import dev.stowling.v2.control.V2NotFoundException;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerType;
import dev.stowling.v2.runtime.TaskRunNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class V2ControlServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final String INVENTORY_ICON_PATH = "/v1/player-inventory/icons/";

    private final InetSocketAddress address;
    private final V2ControlService service;
    private final List<Issue> configurationIssues;
    private final byte[] tokenHash;
    private final Map<String, StaticContent> staticContent;
    private HttpServer server;
    private ExecutorService executor;

    public V2ControlServer(
            InetSocketAddress address,
            V2ControlService service,
            String token
    ) {
        this(address, Objects.requireNonNull(service, "service"), token, List.of());
    }

    private V2ControlServer(
            InetSocketAddress address,
            V2ControlService service,
            String token,
            List<Issue> configurationIssues
    ) {
        this.address = Objects.requireNonNull(address, "address");
        this.service = service;
        this.configurationIssues = List.copyOf(configurationIssues);
        this.tokenHash = hash(Objects.requireNonNull(token, "token"));
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.staticContent = loadStaticContent();
    }

    public static V2ControlServer loopbackWithTokenFile(
            V2ControlService service,
            Path tokenFile,
            int port
    ) {
        String token = new BearerTokenStore(tokenFile).loadOrCreate();
        return new V2ControlServer(loopback(port), service, token);
    }

    public static V2ControlServer recoveryWithTokenFile(
            List<Issue> issues, Path tokenFile, int port
    ) {
        if (issues.isEmpty()) {
            throw new IllegalArgumentException("recovery requires configuration issues");
        }
        String token = new BearerTokenStore(tokenFile).loadOrCreate();
        return new V2ControlServer(loopback(port), null, token, issues);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("server already started");
        }
        HttpServer created = HttpServer.create(address, 0);
        ExecutorService createdExecutor = Executors.newFixedThreadPool(
                4,
                new ControlThreadFactory()
        );
        created.createContext("/", this::handle);
        created.setExecutor(createdExecutor);
        created.start();
        server = created;
        executor = createdExecutor;
    }

    public synchronized InetSocketAddress localAddress() {
        if (server == null) {
            throw new IllegalStateException("server is not running");
        }
        return server.getAddress();
    }

    public synchronized void stop() {
        HttpServer active = server;
        ExecutorService activeExecutor = executor;
        server = null;
        executor = null;
        if (active != null) {
            active.stop(0);
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
            applySecurityHeaders(exchange);
            String path = exchange.getRequestURI().getRawPath();
            StaticContent content = staticContent.get(path);
            if (content != null) {
                serveStatic(exchange, content);
                return;
            }
            if (!authorized(exchange.getRequestHeaders())) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendError(
                        exchange,
                        401,
                        "unauthorized",
                        "A valid bearer token is required"
                );
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null
                    || path.contains("%")
                    || path.contains("\\")) {
                throw new RequestFailure(400, "invalid_request", "Invalid request target");
            }
            if (configurationIssues.isEmpty() && "/v1/schematic-materials".equals(path)) {
                previewSchematic(exchange);
                return;
            }
            String body = readBody(exchange.getRequestBody());
            String method = exchange.getRequestMethod();
            if (configurationIssues.isEmpty()) {
                dispatch(exchange, path, method, body);
            } else if ("/v1/status".equals(path)) {
                requireMethod(method, "GET");
                requireEmpty(body);
                sendJson(exchange, 200, Map.of(
                        "state", "CONFIG_INVALID",
                        "observedAt", Instant.now(),
                        "configurationIssues", configurationIssues
                ));
            } else {
                sendError(exchange, 503, "configuration_invalid",
                        "请修正配置文件并重启 Minecraft");
            }
        } catch (RequestFailure exception) {
            if (exception.allow != null) {
                exchange.getResponseHeaders().set("Allow", exception.allow);
            }
            sendErrorQuietly(
                    exchange,
                    exception.status,
                    exception.code,
                    exception.getMessage()
            );
        } catch (V2RevisionConflictException exception) {
            sendErrorQuietly(exchange, 409, "revision_conflict", exception.getMessage());
        } catch (TaskRunNotFoundException exception) {
            sendErrorQuietly(exchange, 404, "not_found", exception.getMessage());
        } catch (CoordinateInUseException exception) {
            sendJsonQuietly(exchange, 409, new ErrorResponse(new ErrorBody(
                    "coordinate_in_use",
                    exception.getMessage(),
                    exception.taskNames()
            )));
        } catch (TaskInUseException exception) {
            sendJsonQuietly(exchange, 409, new ErrorResponse(new ErrorBody(
                    "task_in_use",
                    exception.getMessage(),
                    exception.taskNames()
            )));
        } catch (V2NotFoundException exception) {
            sendErrorQuietly(exchange, 404, "not_found", exception.getMessage());
        } catch (IllegalStateException exception) {
            sendErrorQuietly(
                    exchange,
                    409,
                    "invalid_state",
                    exception.getMessage()
            );
        } catch (IllegalArgumentException exception) {
            sendErrorQuietly(exchange, 400, "invalid_request", exception.getMessage());
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

    private void dispatch(
            HttpExchange exchange,
            String path,
            String method,
            String body
    ) throws IOException {
        if (path.startsWith(INVENTORY_ICON_PATH)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            String model = path.substring(INVENTORY_ICON_PATH.length());
            sendJson(exchange, 200, service.playerItemIcon(model));
            return;
        }
        if ("/v1/player-inventory".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.playerInventory());
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFields(json, Set.of("revision", "key", "keepCount"));
                sendJson(exchange, 200, service.allocatePlayerInventory(
                        string(json, "revision"), string(json, "key"), integer(json, "keepCount")));
            } else {
                throw methodNotAllowed("GET, PUT");
            }
            return;
        }
        if ("/v1/status".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.status());
            return;
        }
        if ("/v1/warehouse-inventory".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.warehouseInventory());
            return;
        }
        if ("/v1/inventory".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.inventory());
            return;
        }
        if ("/v1/task-modules".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.modules());
            return;
        }
        if ("/v1/category-rules".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.categoryRules());
            return;
        }
        if (path.startsWith("/v1/task-diagnostics/run/")) {
            requireMethod(method, "GET");
            requireEmpty(body);
            UUID runId = uuid(path.substring("/v1/task-diagnostics/run/".length()));
            sendJson(exchange, 200, service.taskDiagnostics(runId));
            return;
        }
        if ("/v1/task-diagnostics".equals(path)
                || path.startsWith("/v1/task-diagnostics/since/")) {
            requireMethod(method, "GET");
            requireEmpty(body);
            long after = 0;
            int limit = dev.stowling.v2.runtime.TaskDiagnosticLog.CAPACITY;
            if (!"/v1/task-diagnostics".equals(path)) {
                String cursor = path.substring("/v1/task-diagnostics/since/".length());
                if (!cursor.matches("[0-9]{1,18}")) {
                    throw new RequestFailure(400, "invalid_request", "Invalid diagnostic cursor");
                }
                after = Long.parseLong(cursor);
                limit = 1000;
            }
            sendJson(exchange, 200, service.taskDiagnostics(after, limit));
            return;
        }
        if ("/v1/task-runs".equals(path)) {
            requireMethod(method, "GET");
            requireEmpty(body);
            sendJson(exchange, 200, service.taskRuns());
            return;
        }
        if ("/v1/task-triggers".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.taskTriggers());
            } else if ("POST".equals(method)) {
                sendJson(
                        exchange,
                        201,
                        service.createTaskTrigger(
                                taskTriggerRequest(object(body), false)
                        )
                );
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if ("/v1/task-folders".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.taskFolders());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFields(json, Set.of("name"));
                sendJson(
                        exchange,
                        201,
                        service.createTaskFolder(new TaskFolderRequest(
                                string(json, "name")
                        ))
                );
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if ("/v1/coordinate-resources".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.coordinateDirectory());
            } else {
                requireMethod(method, "PUT");
                JsonObject json = object(body);
                requireFieldsAny(json, Set.of("revision", "resourceRef", "folderId", "beforeRef"),
                        Set.of("revision", "resourceRef"));
                sendJson(exchange, 200, service.placeCoordinateBookmark(string(json, "revision"),
                        string(json, "resourceRef"), optionalUuid(json, "folderId"),
                        optionalString(json, "beforeRef")));
            }
            return;
        }
        if (coordinateCatalogRoute(exchange, method, path, body, "/v1/coordinate-folders", true)
                || coordinateCatalogRoute(exchange, method, path, body, "/v1/warehouses", false)) {
            return;
        }
        if ("/v1/coordinates".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.coordinates());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of("name", "x", "y", "z", "dimension", "radius"),
                        Set.of("name", "x", "y", "z")
                );
                sendJson(exchange, 201, service.createCoordinate(new CoordinateRequest(
                        string(json, "name"),
                        integer(json, "x"),
                        integer(json, "y"),
                        integer(json, "z"),
                        optionalString(json, "dimension"),
                        optionalInteger(json, "radius")
                )));
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if ("/v1/tasks".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.tasks());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of(
                                "name",
                                "priority",
                                "repeatWhenIdle",
                                "steps",
                                "interruptionMode",
                                "interruptionTaskRef",
                                "defaultInterruptionFlow",
                                "requeueAfterInterruption",
                                "folderId",
                                "cleanupPolicy",
                                "schedule"
                        ),
                        Set.of("steps")
                );
                sendJson(exchange, 201, service.createTask(taskRequest(json)));
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if ("/v1/auto-eat-tasks".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.autoEatTasks());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                sendJson(exchange, 201, service.saveAutoEatTask(null, string(json, "revision"),
                        autoEatSettings(json)));
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if (path.startsWith("/v1/auto-eat-tasks/")) {
            String suffix = path.substring("/v1/auto-eat-tasks/".length());
            if (suffix.endsWith("/start") || suffix.endsWith("/stop")) {
                requireMethod(method, "POST");
                UUID id = uuid(suffix.substring(0, suffix.lastIndexOf('/')));
                sendJson(exchange, 200, service.setAutoEatListening(
                        id, revision(body), suffix.endsWith("/start")));
                return;
            }
            UUID id = uuid(suffix);
            if ("PUT".equals(method)) {
                JsonObject json = object(body);
                sendJson(exchange, 200, service.saveAutoEatTask(id, string(json, "revision"),
                        autoEatSettings(json)));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteAutoEatTask(id, revision(body)));
            } else {
                throw methodNotAllowed("PUT, DELETE");
            }
            return;
        }
        if ("/v1/auto-classify-tasks".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.autoClassifyTasks());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        autoClassifyFields(false),
                        autoClassifyRequiredFields(false)
                );
                sendJson(
                        exchange,
                        201,
                        service.createAutoClassifyTask(
                                autoClassifyRequest(json)
                        )
                );
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if (path.startsWith("/v1/auto-classify-tasks/")) {
            String suffix = path.substring(
                    "/v1/auto-classify-tasks/".length()
            );
            if (suffix.endsWith("/start") || suffix.endsWith("/stop")) {
                requireMethod(method, "POST");
                boolean start = suffix.endsWith("/start");
                String idPart = suffix.substring(
                        0,
                        suffix.length() - (start ? 6 : 5)
                );
                UUID id = uuid(idPart);
                RevisionRequest revision = revision(body);
                sendJson(exchange, 200, start
                        ? service.startAutoClassifyTask(id, revision)
                        : service.stopAutoClassifyTask(id, revision));
                return;
            }
            UUID id = uuid(suffix);
            if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        autoClassifyFields(true),
                        autoClassifyRequiredFields(true)
                );
                sendJson(exchange, 200, service.updateAutoClassifyTask(
                        id,
                        autoClassifyUpdateRequest(json)
                ));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteAutoClassifyTask(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("PUT, DELETE");
            }
            return;
        }
        if ("/v1/regions".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.regions());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFields(json, Set.of(
                        "name",
                        "firstX",
                        "firstY",
                        "firstZ",
                        "secondX",
                        "secondY",
                        "secondZ"
                ));
                sendJson(exchange, 201, service.createRegion(regionRequest(json)));
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if ("/v1/category-zones".equals(path)) {
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.categoryZones());
            } else if ("POST".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of("name", "bindings", "expansion"),
                        Set.of("name", "bindings")
                );
                sendJson(
                        exchange,
                        201,
                        service.createCategoryZone(categoryZoneRequest(json))
                );
            } else {
                throw methodNotAllowed("GET, POST");
            }
            return;
        }
        if (path.startsWith("/v1/category-zones/")) {
            UUID id = pathUuid(path, "/v1/category-zones/");
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.categoryZone(id));
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of("revision", "name", "bindings", "expansion"),
                        Set.of("revision", "name", "bindings")
                );
                sendJson(exchange, 200, service.updateCategoryZone(
                        id,
                        new CategoryZoneUpdateRequest(
                                string(json, "revision"),
                                string(json, "name"),
                                categoryBindings(json),
                                categoryExpansion(json)
                        )
                ));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteCategoryZone(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("GET, PUT, DELETE");
            }
            return;
        }
        if (path.startsWith("/v1/regions/")) {
            UUID id = pathUuid(path, "/v1/regions/");
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.region(id));
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFields(json, Set.of(
                        "revision",
                        "name",
                        "firstX",
                        "firstY",
                        "firstZ",
                        "secondX",
                        "secondY",
                        "secondZ"
                ));
                sendJson(exchange, 200, service.updateRegion(
                        id,
                        new RegionUpdateRequest(
                                string(json, "revision"),
                                string(json, "name"),
                                integer(json, "firstX"),
                                integer(json, "firstY"),
                                integer(json, "firstZ"),
                                integer(json, "secondX"),
                                integer(json, "secondY"),
                                integer(json, "secondZ")
                        )
                ));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteRegion(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("GET, PUT, DELETE");
            }
            return;
        }
        if (path.startsWith("/v1/coordinates/")) {
            UUID id = pathUuid(path, "/v1/coordinates/");
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.coordinate(id));
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of(
                                "revision",
                                "name",
                                "x",
                                "y",
                                "z",
                                "dimension",
                                "radius"
                        ),
                        Set.of("revision", "name", "x", "y", "z")
                );
                sendJson(exchange, 200, service.updateCoordinate(id,
                        new CoordinateUpdateRequest(
                                string(json, "revision"),
                                string(json, "name"),
                                integer(json, "x"),
                                integer(json, "y"),
                                integer(json, "z"),
                                optionalString(json, "dimension"),
                                optionalInteger(json, "radius")
                        )));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteCoordinate(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("GET, PUT, DELETE");
            }
            return;
        }
        if (path.startsWith("/v1/tasks/")) {
            String suffix = path.substring("/v1/tasks/".length());
            if (suffix.endsWith("/start") || suffix.endsWith("/stop")) {
                requireMethod(method, "POST");
                UUID id = uuid(suffix.substring(0, suffix.lastIndexOf('/')));
                sendJson(exchange, 200, service.setRefreshTaskRunning(
                        id, revision(body), suffix.endsWith("/start")));
                return;
            }
            if (suffix.endsWith("/runs")) {
                requireMethod(method, "POST");
                UUID id = uuid(suffix.substring(0, suffix.length() - 5));
                JsonObject json = body.isBlank()
                        ? new JsonObject()
                        : object(body);
                requireFieldsAny(json, Set.of("priority"), Set.of());
                sendJson(exchange, 202, service.runTask(
                        id,
                        optionalPriority(json, "priority")
                ));
                return;
            }
            UUID id = uuid(suffix);
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.task(id));
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFieldsAny(
                        json,
                        Set.of(
                                "revision",
                                "name",
                                "priority",
                                "repeatWhenIdle",
                                "steps",
                                "interruptionMode",
                                "interruptionTaskRef",
                                "defaultInterruptionFlow",
                                "requeueAfterInterruption",
                                "folderId",
                                "cleanupPolicy",
                                "schedule"
                        ),
                        Set.of("revision", "steps")
                );
                sendJson(exchange, 200, service.updateTask(id,
                        new TaskUpdateRequest(
                                string(json, "revision"),
                                optionalString(json, "name"),
                                optionalPriority(json, "priority"),
                                optionalBoolean(json, "repeatWhenIdle"),
                                steps(json),
                                optionalInterruptionMode(json),
                                optionalString(json, "interruptionTaskRef"),
                                optionalBoolean(json, "defaultInterruptionFlow"),
                                optionalBoolean(json, "requeueAfterInterruption"),
                                optionalUuid(json, "folderId"),
                                cleanupPolicy(json),
                                taskSchedule(json)
                        )));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteTask(id, revision(body)));
            } else {
                throw methodNotAllowed("GET, PUT, DELETE");
            }
            return;
        }
        if (path.startsWith("/v1/task-runs/") && path.endsWith("/cancel")) {
            requireMethod(method, "POST");
            requireEmpty(body);
            String value = path.substring(
                    "/v1/task-runs/".length(),
                    path.length() - "/cancel".length()
            );
            service.cancelRun(uuid(value));
            sendJson(exchange, 200, Map.of("cancelled", true));
            return;
        }
        String runPrefix = "/v1/task-runs/";
        String forceStopSuffix = "/force-stop";
        if (path.startsWith(runPrefix) && path.substring(runPrefix.length())
                .endsWith(forceStopSuffix)) {
            requireMethod(method, "POST");
            requireEmpty(body);
            String runPath = path.substring(0, path.length() - forceStopSuffix.length());
            service.forceStopRun(pathUuid(runPath, runPrefix));
            sendJson(exchange, 202, Map.of("accepted", true));
            return;
        }
        if (path.startsWith("/v1/task-runs/")
                && path.endsWith("/retry-interruption")) {
            requireMethod(method, "POST");
            requireEmpty(body);
            String prefix = "/v1/task-runs/";
            String suffix = "/retry-interruption";
            String value = path.substring(
                    prefix.length(),
                    path.length() - suffix.length()
            );
            service.retryInterruption(uuid(value));
            sendJson(exchange, 200, Map.of("retried", true));
            return;
        }
        if (path.startsWith("/v1/task-runs/")
                && path.endsWith("/skip-interruption")) {
            requireMethod(method, "POST");
            requireEmpty(body);
            String prefix = "/v1/task-runs/";
            String suffix = "/skip-interruption";
            String value = path.substring(
                    prefix.length(),
                    path.length() - suffix.length()
            );
            service.skipInterruption(uuid(value));
            sendJson(exchange, 200, Map.of("skipped", true));
            return;
        }
        if (path.startsWith("/v1/task-triggers/")) {
            UUID id = pathUuid(path, "/v1/task-triggers/");
            if ("GET".equals(method)) {
                requireEmpty(body);
                sendJson(exchange, 200, service.taskTrigger(id));
            } else if ("PUT".equals(method)) {
                JsonObject json = object(body);
                TaskTriggerRequest request = taskTriggerRequest(json, true);
                sendJson(exchange, 200, service.updateTaskTrigger(
                        id,
                        new TaskTriggerUpdateRequest(
                                string(json, "revision"),
                                request.name(),
                                request.type(),
                                request.targetTaskId(),
                                request.priority(),
                                request.intervalTicks(),
                                request.cooldownTicks(),
                                request.coordinateId(),
                                request.itemFilter(),
                                request.foodLevelThreshold(),
                                request.cacheRegionId()
                        )
                ));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteTaskTrigger(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("GET, PUT, DELETE");
            }
            return;
        }
        if (path.startsWith("/v1/task-folders/")) {
            UUID id = pathUuid(path, "/v1/task-folders/");
            if ("PUT".equals(method)) {
                JsonObject json = object(body);
                requireFields(json, Set.of("revision", "name"));
                sendJson(exchange, 200, service.updateTaskFolder(
                        id,
                        new TaskFolderUpdateRequest(
                                string(json, "revision"),
                                string(json, "name")
                        )
                ));
            } else if ("DELETE".equals(method)) {
                sendJson(exchange, 200, service.deleteTaskFolder(
                        id,
                        revision(body)
                ));
            } else {
                throw methodNotAllowed("PUT, DELETE");
            }
            return;
        }
        throw new RequestFailure(404, "not_found", "Route not found");
    }

    private static dev.stowling.v2.model.TaskCleanupPolicy cleanupPolicy(JsonObject json) {
        if (!json.has("cleanupPolicy") || json.get("cleanupPolicy").isJsonNull()) return null;
        JsonObject policy = jsonObject(json.get("cleanupPolicy"), "cleanupPolicy");
        requireFieldsAny(policy, Set.of("destinationRef", "resumeMode", "emptyBoxRefs",
                "packingPositionRef"), Set.of());
        String mode = optionalString(policy, "resumeMode");
        try {
            return new dev.stowling.v2.model.TaskCleanupPolicy(
                    optionalString(policy, "destinationRef"), mode == null || mode.isBlank() ? null
                            : dev.stowling.v2.model.TaskResumeMode.valueOf(mode),
                    policy.has("emptyBoxRefs") ? stringArray(policy, "emptyBoxRefs") : List.of(),
                    optionalString(policy, "packingPositionRef"));
        } catch (IllegalArgumentException exception) {
            throw invalid("cleanupPolicy: " + exception.getMessage());
        }
    }

    private static TaskRequest taskRequest(JsonObject json) {
        return new TaskRequest(
                optionalString(json, "name"),
                optionalPriority(json, "priority"),
                optionalBoolean(json, "repeatWhenIdle"),
                steps(json),
                optionalInterruptionMode(json),
                optionalString(json, "interruptionTaskRef"),
                optionalBoolean(json, "defaultInterruptionFlow"),
                optionalBoolean(json, "requeueAfterInterruption"),
                optionalUuid(json, "folderId"),
                cleanupPolicy(json),
                taskSchedule(json)
        );
    }

    private static dev.stowling.v2.model.TaskSchedule taskSchedule(JsonObject json) {
        if (!json.has("schedule")) {
            return null;
        }
        var value = jsonObject(json.get("schedule"), "schedule");
        requireFieldsAny(value, Set.of("mode", "time", "zoneId", "intervalMinutes"),
                Set.of("mode"));
        var mode = dev.stowling.v2.model.TaskSchedule.Mode.valueOf(string(value, "mode"));
        return new dev.stowling.v2.model.TaskSchedule(mode, optionalString(value, "time"),
                optionalString(value, "zoneId"), optionalInteger(value, "intervalMinutes"), null);
    }

    private static AutoEatTask.Settings autoEatSettings(JsonObject json) {
        Set<String> required = Set.of("revision", "foodItemId", "foodLevelThreshold",
                "intervalSeconds");
        requireFieldsAny(json, Set.of("revision", "name", "foodItemId", "coordinateId",
                "foodLevelThreshold", "intervalSeconds", "enabled"), required);
        return new AutoEatTask.Settings(optionalString(json, "name"), string(json, "foodItemId"),
                optionalUuid(json, "coordinateId"), integer(json, "foodLevelThreshold"),
                integer(json, "intervalSeconds"), optionalBoolean(json, "enabled"));
    }

    private static AutoClassifyTaskRequest autoClassifyRequest(
            JsonObject json
    ) {
        AutoClassifyDetectionRequest detection = autoClassifyDetection(json);
        return new AutoClassifyTaskRequest(
                optionalString(json, "name"),
                optionalBooleanValue(json, "enabled"),
                optionalBooleanValue(json, "collectionEnabled"),
                optionalBooleanValue(json, "classificationEnabled"),
                detection,
                string(json, "cacheLocationRef"),
                autoClassifyAreas(json),
                optionalPriority(json, "collectionPriority"),
                optionalBooleanValue(json, "overflowEnabled"),
                cleanupPolicy(json),
                optionalString(json, "overflowDestinationRef"),
                cachePolicy(json),
                autoClassifyKind(json),
                optionalString(json, "followUpTaskRef"),
                optionalInteger(json, "followUpDelaySeconds"),
                optionalBooleanValue(json, "followUpEnabled")
        );
    }

    private static AutoClassifyTaskUpdateRequest autoClassifyUpdateRequest(
            JsonObject json
    ) {
        AutoClassifyDetectionRequest detection = autoClassifyDetection(json);
        return new AutoClassifyTaskUpdateRequest(
                string(json, "revision"),
                optionalString(json, "name"),
                optionalBooleanValue(json, "enabled"),
                optionalBooleanValue(json, "collectionEnabled"),
                optionalBooleanValue(json, "classificationEnabled"),
                detection,
                string(json, "cacheLocationRef"),
                autoClassifyAreas(json),
                optionalPriority(json, "collectionPriority"),
                optionalBooleanValue(json, "overflowEnabled"),
                cleanupPolicy(json),
                optionalString(json, "overflowDestinationRef"),
                cachePolicy(json),
                autoClassifyKind(json),
                optionalString(json, "followUpTaskRef"),
                optionalInteger(json, "followUpDelaySeconds"),
                optionalBooleanValue(json, "followUpEnabled")
        );
    }

    private static List<AutoClassifyAreaRequest> autoClassifyAreas(
            JsonObject json
    ) {
        List<AutoClassifyAreaRequest> result = new ArrayList<>();
        for (JsonElement value : array(json, "classificationAreas")) {
            JsonObject area = jsonObject(value, "classification area");
            requireFieldsAny(area, Set.of("categoryZoneRef", "storagePolicy"),
                    Set.of("categoryZoneRef"));
            result.add(new AutoClassifyAreaRequest(
                    string(area, "categoryZoneRef"),
                    area.has("storagePolicy") ? storagePolicy(area) : null
            ));
        }
        return List.copyOf(result);
    }

    private static dev.stowling.v2.model.CacheIntakePolicy cachePolicy(JsonObject json) {
        if (!json.has("cachePolicy") || json.get("cachePolicy").isJsonNull()) return null;
        JsonObject policy = jsonObject(json.get("cachePolicy"), "cachePolicy");
        requireFieldsAny(policy, Set.of("enabled", "mode", "thresholdPercent"),
                Set.of("mode", "thresholdPercent"));
        try {
            return new dev.stowling.v2.model.CacheIntakePolicy(
                    optionalBoolean(policy, "enabled"),
                    dev.stowling.v2.model.CacheIntakePolicy.Mode.valueOf(string(policy, "mode")),
                    integer(policy, "thresholdPercent"));
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("请选择有效的缓存策略和 1 到 100 的容量百分比");
        }
    }

    private static AutoClassifyDetectionRequest autoClassifyDetection(
            JsonObject json
    ) {
        if (!json.has("detection") || json.get("detection").isJsonNull()) return null;
        JsonObject detection = jsonObject(
                json.get("detection"),
                "detection"
        );
        requireFieldsAny(
                detection,
                Set.of("coordinateRef", "pickupRadius", "intervalSeconds"),
                Set.of("coordinateRef")
        );
        return new AutoClassifyDetectionRequest(
                string(detection, "coordinateRef"),
                optionalInteger(detection, "pickupRadius"),
                optionalInteger(detection, "intervalSeconds")
        );
    }

    private static dev.stowling.v2.model.AutoClassifyDefinition.Kind autoClassifyKind(
            JsonObject json) {
        String kind = optionalString(json, "kind");
        return kind == null ? null : dev.stowling.v2.model.AutoClassifyDefinition.Kind.valueOf(kind);
    }

    private static Set<String> autoClassifyFields(boolean revision) {
        Set<String> fields = new java.util.HashSet<>(Set.of(
                "name",
                "enabled",
                "collectionEnabled",
                "classificationEnabled",
                "overflowEnabled",
                "overflowDestinationRef",
                "cleanupPolicy",
                "cachePolicy",
                "kind",
                "followUpTaskRef",
                "followUpDelaySeconds",
                "followUpEnabled",
                "detection",
                "cacheLocationRef",
                "classificationAreas",
                "collectionPriority"
        ));
        if (revision) {
            fields.add("revision");
        }
        return Set.copyOf(fields);
    }

    private static Set<String> autoClassifyRequiredFields(boolean revision) {
        Set<String> fields = new java.util.HashSet<>(Set.of(
                "cacheLocationRef",
                "classificationAreas"
        ));
        if (revision) {
            fields.add("revision");
        }
        return Set.copyOf(fields);
    }

    private static TaskTriggerRequest taskTriggerRequest(
            JsonObject json,
            boolean revision
    ) {
        requireFieldsAny(
                json,
                triggerFields(revision),
                triggerRequiredFields(revision)
        );
        String coordinateId = optionalString(json, "coordinateId");
        return new TaskTriggerRequest(
                string(json, "name"),
                triggerType(json),
                uuid(string(json, "targetTaskId")),
                optionalPriority(json, "priority"),
                integer(json, "intervalTicks"),
                integer(json, "cooldownTicks"),
                coordinateId == null ? null : uuid(coordinateId),
                optionalString(json, "itemFilter"),
                optionalInteger(json, "foodLevelThreshold"),
                optionalUuid(json, "cacheRegionId")
        );
    }

    private static Set<String> triggerFields(boolean revision) {
        Set<String> fields = Set.of(
                "name",
                "type",
                "targetTaskId",
                "priority",
                "intervalTicks",
                "cooldownTicks",
                "coordinateId",
                "itemFilter",
                "foodLevelThreshold",
                "cacheRegionId"
        );
        if (!revision) {
            return fields;
        }
        Set<String> withRevision = new java.util.HashSet<>(fields);
        withRevision.add("revision");
        return Set.copyOf(withRevision);
    }

    private static Set<String> triggerRequiredFields(boolean revision) {
        return revision
                ? Set.of(
                        "revision",
                        "name",
                        "type",
                        "targetTaskId",
                        "intervalTicks",
                        "cooldownTicks"
                )
                : Set.of(
                        "name",
                        "type",
                        "targetTaskId",
                        "intervalTicks",
                        "cooldownTicks"
                );
    }

    private static TaskTriggerType triggerType(JsonObject json) {
        try {
            return TaskTriggerType.valueOf(
                    string(json, "type").trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw invalid(
                    "type must be DROPPED_ITEMS, "
                            + "FOOD_LEVEL_AT_OR_BELOW, or CACHE_UPDATED"
            );
        }
    }

    private static RegionRequest regionRequest(JsonObject json) {
        return new RegionRequest(
                string(json, "name"),
                integer(json, "firstX"),
                integer(json, "firstY"),
                integer(json, "firstZ"),
                integer(json, "secondX"),
                integer(json, "secondY"),
                integer(json, "secondZ")
        );
    }

    private static CategoryZoneRequest categoryZoneRequest(JsonObject json) {
        return new CategoryZoneRequest(
                string(json, "name"),
                categoryBindings(json),
                categoryExpansion(json)
        );
    }

    private static CategoryZoneExpansionRequest categoryExpansion(
            JsonObject json
    ) {
        JsonElement value = json.get("expansion");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        JsonObject expansion = jsonObject(value, "expansion");
        requireFields(
                expansion,
                Set.of(
                        "firstX",
                        "firstY",
                        "firstZ",
                        "secondX",
                        "secondY",
                        "secondZ",
                        "direction",
                        "step"
                )
        );
        return new CategoryZoneExpansionRequest(
                integer(expansion, "firstX"),
                integer(expansion, "firstY"),
                integer(expansion, "firstZ"),
                integer(expansion, "secondX"),
                integer(expansion, "secondY"),
                integer(expansion, "secondZ"),
                string(expansion, "direction"),
                integer(expansion, "step")
        );
    }

    private static List<CategoryBindingRequest> categoryBindings(
            JsonObject json
    ) {
        List<CategoryBindingRequest> result = new ArrayList<>();
        for (JsonElement value : array(json, "bindings")) {
            JsonObject binding = jsonObject(value, "binding");
            requireFieldsAny(
                    binding,
                    Set.of(
                            "markerFirstX",
                            "markerFirstY",
                            "markerFirstZ",
                            "markerSecondX",
                            "markerSecondY",
                            "markerSecondZ",
                            "storageFirstX",
                            "storageFirstY",
                            "storageFirstZ",
                            "storageSecondX",
                            "storageSecondY",
                            "storageSecondZ",
                            "ruleId",
                            "ignoredMarkerBlockIds",
                            "containerOverrides",
                            "standingArea",
                            "storagePolicy",
                            "wholeStorage"
                    ),
                    Set.of(
                            "markerFirstX",
                            "markerFirstY",
                            "markerFirstZ",
                            "markerSecondX",
                            "markerSecondY",
                            "markerSecondZ",
                            "storageFirstX",
                            "storageFirstY",
                            "storageFirstZ",
                            "storageSecondX",
                            "storageSecondY",
                            "storageSecondZ",
                            "ruleId"
                    )
            );
            result.add(new CategoryBindingRequest(
                    integer(binding, "markerFirstX"),
                    integer(binding, "markerFirstY"),
                    integer(binding, "markerFirstZ"),
                    integer(binding, "markerSecondX"),
                    integer(binding, "markerSecondY"),
                    integer(binding, "markerSecondZ"),
                    integer(binding, "storageFirstX"),
                    integer(binding, "storageFirstY"),
                    integer(binding, "storageFirstZ"),
                    integer(binding, "storageSecondX"),
                    integer(binding, "storageSecondY"),
                    integer(binding, "storageSecondZ"),
                    string(binding, "ruleId"),
                    stringArray(binding, "ignoredMarkerBlockIds"),
                    containerOverrideRequests(binding),
                    standingArea(binding),
                    storagePolicy(binding),
                    optionalBoolean(binding, "wholeStorage")
            ));
        }
        return List.copyOf(result);
    }

    private static dev.stowling.v2.model.StoragePolicy storagePolicy(JsonObject binding) {
        JsonElement value = binding.get("storagePolicy");
        if (value == null || value.isJsonNull()) {
            return dev.stowling.v2.model.StoragePolicy.DEFAULT;
        }
        JsonObject policy = jsonObject(value, "storagePolicy");
        requireFieldsAny(policy, Set.of("mode", "loosePercent", "allowPartialBoxes", "packingArea",
                        "refillLoose", "looseSlots", "partialBoxDestinationRef"),
                Set.of("mode", "allowPartialBoxes"));
        return V2Json.gson().fromJson(policy, dev.stowling.v2.model.StoragePolicy.class);
    }

    private static dev.stowling.v2.model.AreaBounds standingArea(JsonObject binding) {
        JsonElement value = binding.get("standingArea");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        JsonObject area = jsonObject(value, "standingArea");
        requireFields(area, Set.of("firstX", "firstY", "firstZ", "secondX", "secondY", "secondZ"));
        return new dev.stowling.v2.model.AreaBounds(integer(area, "firstX"), integer(area, "firstY"),
                integer(area, "firstZ"), integer(area, "secondX"), integer(area, "secondY"),
                integer(area, "secondZ"));
    }

    private static List<CategoryContainerOverrideRequest>
            containerOverrideRequests(JsonObject binding) {
        JsonElement value = binding.get("containerOverrides");
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw invalid("containerOverrides must be an array");
        }
        List<CategoryContainerOverrideRequest> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject override = jsonObject(item, "container override");
            requireFields(override, Set.of(
                    "storageX",
                    "storageY",
                    "storageZ",
                    "mode",
                    "itemIds"
            ));
            result.add(new CategoryContainerOverrideRequest(
                    integer(override, "storageX"),
                    integer(override, "storageY"),
                    integer(override, "storageZ"),
                    string(override, "mode"),
                    stringArray(override, "itemIds")
            ));
        }
        return List.copyOf(result);
    }

    private static List<TaskStepRequest> steps(JsonObject json) {
        return taskStepList(json, "steps");
    }

    private static List<TaskStepRequest> taskStepList(
            JsonObject json,
            String key
    ) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw invalid(key + " must be an array");
        }
        List<TaskStepRequest> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject step = jsonObject(item, "step");
            validateTaskStep(step);
            TaskStepKind kind = optionalStepKind(step);
            String moduleId = kind == TaskStepKind.ACTION
                    ? string(step, "moduleId")
                    : "";
            result.add(new TaskStepRequest(
                    optionalString(step, "id"),
                    kind,
                    moduleId,
                    parameters(step),
                    taskStepList(step, "steps"),
                    taskStepList(step, "otherwise")
            ));
        }
        return List.copyOf(result);
    }

    private static void validateTaskStep(JsonObject step) {
        requireFieldsAny(
                step,
                Set.of("id", "kind", "moduleId", "parameters", "steps", "otherwise"),
                Set.of("parameters")
        );
        if (step.has("kind")
                && !step.get("kind").isJsonPrimitive()) {
            throw invalid("kind must be a string");
        }
    }

    private static TaskStepKind optionalStepKind(JsonObject step) {
        String value = optionalString(step, "kind");
        if (value == null || value.isBlank()) {
            return TaskStepKind.ACTION;
        }
        try {
            return TaskStepKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("kind must be ACTION, IF, REPEAT, or CREATE_TASK");
        }
    }

    private static TaskInterruptionMode optionalInterruptionMode(
            JsonObject json
    ) {
        String value = optionalString(json, "interruptionMode");
        if (value == null || value.isBlank()) {
            return TaskInterruptionMode.DEFAULT;
        }
        try {
            return TaskInterruptionMode.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw invalid("interruptionMode must be DEFAULT, CUSTOM, or NONE");
        }
    }

    private static Map<String, String> parameters(JsonObject step) {
        JsonObject values = object(step.get("parameters"), "parameters");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new RequestFailure(
                        400,
                        "invalid_request",
                        "parameter values must be strings"
                );
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(result);
    }

    private static RevisionRequest revision(String body) {
        JsonObject json = object(body);
        requireFields(json, Set.of("revision"));
        return new RevisionRequest(string(json, "revision"));
    }

    private static JsonObject object(String body) {
        try {
            validateStrictJson(body);
            JsonElement value = JsonParser.parseString(body);
            if (!value.isJsonObject()) {
                throw invalid("body must be an object");
            }
            return value.getAsJsonObject();
        } catch (IOException | JsonParseException exception) {
            throw invalid("body must be valid JSON");
        }
    }

    private static void validateStrictJson(String body) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(body));
        reader.setStrictness(Strictness.STRICT);
        validateJsonValue(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw invalid("body must contain one JSON value");
        }
    }

    private static void validateJsonValue(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> validateJsonObject(reader);
            case BEGIN_ARRAY -> validateJsonArray(reader);
            case STRING -> reader.nextString();
            case NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw invalid("body must be valid JSON");
        }
    }

    private static void validateJsonObject(JsonReader reader) throws IOException {
        reader.beginObject();
        Set<String> names = new java.util.HashSet<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw invalid("JSON object contains a duplicate field");
            }
            validateJsonValue(reader);
        }
        reader.endObject();
    }

    private static void validateJsonArray(JsonReader reader) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            validateJsonValue(reader);
        }
        reader.endArray();
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw invalid(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonObject jsonObject(JsonElement value, String name) {
        return object(value, name);
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            throw invalid(key + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static List<String> stringArray(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw invalid(key + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()) {
                throw invalid(key + " must contain strings");
            }
            String text = item.getAsString().trim();
            if (text.isEmpty()) {
                throw invalid(key + " must not contain blank values");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject json, String key) {
        String value = optionalString(json, key);
        if (value == null || value.isBlank()) {
            throw invalid(key + " must not be blank");
        }
        return value;
    }

    private static String optionalString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(key + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(key + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)
                || number != Math.rint(number)
                || number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE) {
            throw invalid(key + " must be a 32-bit integer");
        }
        return (int) number;
    }

    private static Integer optionalInteger(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return integer(json, key);
    }

    private static UUID optionalUuid(JsonObject json, String key) {
        String value = optionalString(json, key);
        return value == null ? null : uuid(value);
    }

    private static boolean optionalBoolean(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static Boolean optionalBooleanValue(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return optionalBoolean(json, key);
    }

    private static TaskPriority optionalPriority(
            JsonObject json,
            String key
    ) {
        String value = optionalString(json, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskPriority.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw invalid(
                    key + " must be BACKGROUND, NORMAL, or URGENT"
            );
        }
    }

    private static void requireFields(JsonObject json, Set<String> required) {
        requireFieldsAny(json, required, required);
    }

    private static void requireFieldsAny(
            JsonObject json,
            Set<String> allowed,
            Set<String> required
    ) {
        if (!allowed.containsAll(json.keySet())) {
            throw invalid("unknown JSON field");
        }
        if (!json.keySet().containsAll(required)) {
            throw invalid("missing required JSON field");
        }
    }

    private boolean coordinateCatalogRoute(HttpExchange exchange, String method, String path,
            String body, String route, boolean folder) throws IOException {
        if (!path.equals(route) && !path.startsWith(route + "/")) return false;
        boolean create = path.equals(route);
        boolean delete = "DELETE".equals(method) && !create;
        if (!delete) requireMethod(method, create ? "POST" : "PUT");
        JsonObject json = object(body);
        UUID id = create ? UUID.randomUUID() : pathUuid(path, route + "/");
        Set<String> fields = delete ? Set.of("revision")
                : folder ? Set.of("revision", "name") : Set.of("revision", "name", "members");
        requireFields(json, fields);
        String name = delete ? null : string(json, "name");
        var members = delete || folder ? List.<String>of() : stringArray(json, "members");
        var result = service.editCoordinateDirectory(string(json, "revision"), catalog -> {
            boolean present = folder ? catalog.folders().stream().anyMatch(item -> id.equals(item.id()))
                    : catalog.warehouses().stream().anyMatch(item -> id.equals(item.id()));
            if (!create && !present) throw new V2NotFoundException("坐标资源不存在");
            return folder ? catalog.editFolder(id, name) : catalog.editWarehouse(id, name, members);
        });
        sendJson(exchange, create ? 201 : 200, result);
        return true;
    }

    private static UUID pathUuid(String path, String prefix) {
        String value = path.substring(prefix.length());
        if (value.contains("/")) {
            throw new RequestFailure(404, "not_found", "Route not found");
        }
        return uuid(value);
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase(Locale.ROOT))) {
                throw invalid("id must be a canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalid("id must be a canonical UUID");
        }
    }

    private static void requireEmpty(String body) {
        if (!body.isBlank()) {
            throw invalid("request body must be empty");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw methodNotAllowed(expected);
        }
    }

    private static RequestFailure methodNotAllowed(String allowed) {
        return new RequestFailure(
                405,
                "method_not_allowed",
                "Allowed: " + allowed,
                allowed
        );
    }

    private static RequestFailure invalid(String message) {
        return new RequestFailure(400, "invalid_request", message);
    }

    private static void previewSchematic(HttpExchange exchange) throws IOException {
        requireMethod(exchange.getRequestMethod(), "POST");
        var bytes = readBytes(exchange.getRequestBody(),
                dev.stowling.client.automation.LitematicMaterials.MAX_UPLOAD_BYTES);
        dev.stowling.client.automation.LitematicMaterials.Preview preview;
        try {
            preview = dev.stowling.client.automation.LitematicMaterials.read(
                    new java.io.ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException exception) {
            throw invalid("无法解析投影，请检查 .litematic 文件格式和大小");
        }
        if (!preview.items().isEmpty()) {
            dev.stowling.v2.model.MaterialRequirements.parse(
                    V2Json.gson().toJson(preview.items()));
        }
        sendJson(exchange, 200, preview);
    }

    private static String readBody(InputStream input) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(input, MAX_REQUEST_BYTES)))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("request body must be valid UTF-8");
        }
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new RequestFailure(
                        413,
                        "request_too_large",
                        "Request body is too large"
                );
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private boolean authorized(Headers headers) {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) {
            return false;
        }
        String value = values.getFirst();
        int separator = value.indexOf(' ');
        if (separator <= 0 || separator != value.lastIndexOf(' ')) {
            return false;
        }
        return "Bearer".equalsIgnoreCase(value.substring(0, separator))
                && MessageDigest.isEqual(
                        tokenHash,
                        hash(value.substring(separator + 1))
                );
    }

    private static void serveStatic(HttpExchange exchange, StaticContent content)
            throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())
                && !"HEAD".equals(exchange.getRequestMethod())) {
            throw methodNotAllowed("GET, HEAD");
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", content.contentType());
        headers.set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            headers.set("Content-Length", Integer.toString(content.bytes().length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, content.bytes().length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content.bytes());
        }
    }

    private static void applySecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set(
                "Content-Security-Policy",
                "default-src 'self'; "
                        + "script-src 'self'; "
                        + "style-src 'self'; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'none'; "
                        + "base-uri 'none'; "
                        + "form-action 'self'"
        );
    }

    private static void sendError(
            HttpExchange exchange,
            int status,
            String code,
            String message
    ) throws IOException {
        sendJson(exchange, status, new ErrorResponse(new ErrorBody(
                code,
                message,
                List.of()
        )));
    }

    private static void sendErrorQuietly(
            HttpExchange exchange,
            int status,
            String code,
            String message
    ) {
        sendJsonQuietly(exchange, status, new ErrorResponse(new ErrorBody(
                code,
                message,
                List.of()
        )));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        byte[] bytes = V2Json.gson().toJson(value)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendJsonQuietly(HttpExchange exchange, int status, Object value) {
        try {
            sendJson(exchange, status, value);
        } catch (IOException | RuntimeException ignored) {
            // The client disconnected or the response was already committed.
        }
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static InetSocketAddress loopback(int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        } catch (IOException exception) {
            throw new IllegalStateException("IPv4 loopback is unavailable", exception);
        }
    }

    private static Map<String, StaticContent> loadStaticContent() {
        String html = "text/html; charset=utf-8";
        String js = "text/javascript; charset=utf-8";
        return Map.ofEntries(
                Map.entry("/", read("stowling-web/index.html", html)),
                Map.entry("/index.html", read("stowling-web/index.html", html)),
                Map.entry("/coordinates.html", read("stowling-web/coordinates.html", html)),
                Map.entry("/category-zones.html", read("stowling-web/category-zones.html", html)),
                Map.entry("/tasks.html", read("stowling-web/tasks.html", html)),
                Map.entry("/theme.js", read("stowling-web/theme.js", js)),
                Map.entry("/common.js", read("stowling-web/common.js", js)),
                Map.entry("/storage.html", read("stowling-web/storage.html", html)),
                Map.entry("/storage.js", read("stowling-web/storage.js", js)),
                Map.entry("/backpack.html", read("stowling-web/backpack.html", html)),
                Map.entry("/backpack.js", read("stowling-web/backpack.js", js)),
                Map.entry("/backpack-model.js", read("stowling-web/backpack-model.js", js)),
                Map.entry("/dashboard.js", read("stowling-web/dashboard.js", js)),
                Map.entry("/coordinates.js", read("stowling-web/coordinates.js", js)),
                Map.entry("/coordinate-editor.js", read("stowling-web/coordinate-editor.js", js)),
                Map.entry("/coordinate-editor-model.js",
                        read("stowling-web/coordinate-editor-model.js", js)),
                Map.entry("/coordinate-editor.css",
                        read("stowling-web/coordinate-editor.css", "text/css; charset=utf-8")),
                Map.entry(
                        "/category-zone-model.js",
                        read("stowling-web/category-zone-model.js", js)
                ),
                Map.entry(
                        "/category-zones.js",
                        read("stowling-web/category-zones.js", js)
                ),
                Map.entry(
                        "/coordinate-model.js",
                        read("stowling-web/coordinate-model.js", js)
                ),
                Map.entry(
                        "/region-model.js",
                        read("stowling-web/region-model.js", js)
                ),
                Map.entry("/tasks.js", read("stowling-web/tasks.js", js)),
                Map.entry("/task-autosave.js", read("stowling-web/task-autosave.js", js)),
                Map.entry("/warehouse-model.js", read("stowling-web/warehouse-model.js", js)),
                Map.entry("/resource-picker.js", read("stowling-web/resource-picker.js", js)),
                Map.entry("/task-select-ui.js", read("stowling-web/task-select-ui.js", js)),
                Map.entry(
                        "/pickup-task-settings.css",
                        read("stowling-web/pickup-task-settings.css", "text/css; charset=utf-8")
                ),
                Map.entry("/task-cleanup-settings.js",
                        read("stowling-web/task-cleanup-settings.js", js)),
                Map.entry("/resource-browser.js", read("stowling-web/resource-browser.js", js)),
                Map.entry(
                        "/task-monitor-model.js",
                        read("stowling-web/task-monitor-model.js", js)
                ),
                Map.entry(
                        "/move-task-model.js",
                        read("stowling-web/move-task-model.js", js)
                ),
                Map.entry(
                        "/auto-classify-task-model.js",
                        read("stowling-web/auto-classify-task-model.js", js)
                ),
                Map.entry(
                        "/auto-eat-ui.js",
                        read("stowling-web/auto-eat-ui.js", js)
                ),
                Map.entry("/refresh-cache-ui.js", read("stowling-web/refresh-cache-ui.js", js)),
                Map.entry(
                        "/material-collection-ui.js",
                        read("stowling-web/material-collection-ui.js", js)
                ),
                Map.entry(
                        "/auto-classify-ui.js",
                        read("stowling-web/auto-classify-ui.js", js)
                ),
                Map.entry(
                        "/v2-task-model.js",
                        read("stowling-web/v2-task-model.js", js)
                ),
                Map.entry(
                        "/v2-trigger-model.js",
                        read("stowling-web/v2-trigger-model.js", js)
                ),
                Map.entry(
                        "/v2-task-editor.js",
                        read("stowling-web/v2-task-editor.js", js)
                ),
                Map.entry(
                        "/styles.css",
                        read("stowling-web/styles.css", "text/css; charset=utf-8")
                ),
                Map.entry(
                        "/workbench.css",
                        read("stowling-web/workbench.css", "text/css; charset=utf-8")
                ),
                Map.entry(
                        "/storage-policy-editor.js",
                        read("stowling-web/storage-policy-editor.js", js)
                ),
                Map.entry(
                        "/settings-ui.js",
                        read("stowling-web/settings-ui.js", js)
                ),
                Map.entry(
                        "/icon.svg",
                        read("assets/stowling/icon.svg", "image/svg+xml")
                ),
                Map.entry(
                        "/icon.png",
                        read("assets/stowling/icon.png", "image/png")
                )
        );
    }

    private static StaticContent read(String name, String type) {
        try (InputStream input = V2ControlServer.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing static resource: " + name);
            }
            return new StaticContent(input.readAllBytes(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read static resource: " + name, exception);
        }
    }

    private record StaticContent(byte[] bytes, String contentType) {
    }

    private record ErrorResponse(ErrorBody error) {
    }

    private record ErrorBody(String code, String message, List<String> taskNames) {
    }

    private static final class RequestFailure extends RuntimeException {
        private final int status;
        private final String code;
        private final String allow;

        private RequestFailure(int status, String code, String message) {
            this(status, code, message, null);
        }

        private RequestFailure(
                int status,
                String code,
                String message,
                String allow
        ) {
            super(message);
            this.status = status;
            this.code = code;
            this.allow = allow;
        }
    }

    private static final class ControlThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    task,
                    "stowling-v2-control-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
