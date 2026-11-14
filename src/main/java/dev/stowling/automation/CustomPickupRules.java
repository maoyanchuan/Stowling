package dev.stowling.automation;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Per-step pickup rules; their resolved region is never added to shared resources. */
public record CustomPickupRules(
        String standbyCoordinateId,
        String firstCornerCoordinateId,
        String secondCornerCoordinateId,
        List<String> cacheIds,
        Duration idleDelay,
        ClassificationTiming classificationTiming,
        String selectedRegionId
) {
    private static final String REGION_ID = "custom-pickup-region";
    private static final String PURE_WORKFLOW_ID = "pickup-region";
    private static final Set<String> LEGACY_PARAMETER_KEYS = Set.of(
            "standbyCoordinateId", "firstCornerCoordinateId", "secondCornerCoordinateId",
            "cacheIds", "classificationTiming"
    );
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public CustomPickupRules {
        boolean pureRegion = selectedRegionId != null && !selectedRegionId.isBlank();
        if (pureRegion) {
            selectedRegionId = requireId(selectedRegionId, "selectedRegionId");
            if (hasText(standbyCoordinateId)
                    || hasText(firstCornerCoordinateId)
                    || hasText(secondCornerCoordinateId)
                    || cacheIds != null && !cacheIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "pure region pickup cannot mix legacy pickup rules"
                );
            }
            standbyCoordinateId = "";
            firstCornerCoordinateId = "";
            secondCornerCoordinateId = "";
            cacheIds = List.of();
        } else {
            selectedRegionId = null;
            standbyCoordinateId = requireId(standbyCoordinateId, "standbyCoordinateId");
            firstCornerCoordinateId = requireId(
                    firstCornerCoordinateId,
                    "firstCornerCoordinateId"
            );
            secondCornerCoordinateId = requireId(
                    secondCornerCoordinateId,
                    "secondCornerCoordinateId"
            );
            cacheIds = List.copyOf(Objects.requireNonNull(cacheIds, "cacheIds")).stream()
                    .map(id -> requireId(id, "cacheIds"))
                    .toList();
            if (cacheIds.isEmpty()
                    || cacheIds.stream().distinct().count() != cacheIds.size()) {
                throw new IllegalArgumentException(
                        "cacheIds must select distinct cache containers"
                );
            }
        }
        idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isNegative() || idleDelay.getNano() != 0) {
            throw new IllegalArgumentException("idleSeconds must be a nonnegative integer");
        }
        classificationTiming = pureRegion && classificationTiming == null
                ? ClassificationTiming.AFTER_ROUND
                : Objects.requireNonNull(classificationTiming, "classificationTiming");
    }

    public CustomPickupRules(
            String standbyCoordinateId,
            String firstCornerCoordinateId,
            String secondCornerCoordinateId,
            List<String> cacheIds,
            Duration idleDelay,
            ClassificationTiming classificationTiming
    ) {
        this(
                standbyCoordinateId,
                firstCornerCoordinateId,
                secondCornerCoordinateId,
                cacheIds,
                idleDelay,
                classificationTiming,
                null
        );
    }

    public static Optional<CustomPickupRules> fromParameters(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        String workflowId = optional(parameters, "workflowId", "");
        String regionId = optional(parameters, "regionId", "");
        boolean hasLegacyRules = LEGACY_PARAMETER_KEYS.stream()
                .anyMatch(parameters::containsKey);
        boolean hasIdleSeconds = parameters.containsKey("idleSeconds");
        if (!workflowId.isEmpty()) {
            if (hasLegacyRules || hasIdleSeconds) {
                throw new IllegalArgumentException(
                        "legacy workflows cannot mix direct or region pickup rules"
                );
            }
            return Optional.empty();
        }
        if (!regionId.isEmpty()) {
            if (hasLegacyRules) {
                throw new IllegalArgumentException(
                        "pure region pickup cannot mix legacy pickup rules"
                );
            }
            return Optional.of(new CustomPickupRules(
                    "",
                    "",
                    "",
                    List.of(),
                    parseIdleSeconds(parameters),
                    null,
                    requireId(regionId, "regionId")
            ));
        }
        if (!hasLegacyRules && !hasIdleSeconds) {
            return Optional.empty();
        }
        if (!hasLegacyRules) {
            throw new IllegalArgumentException(
                    "missing required direct pickup parameters"
            );
        }
        ClassificationTiming timing;
        try {
            timing = ClassificationTiming.valueOf(
                    optional(parameters, "classificationTiming", "AFTER_ROUND")
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("classificationTiming has an invalid value");
        }
        return Optional.of(new CustomPickupRules(
                required(parameters, "standbyCoordinateId"),
                required(parameters, "firstCornerCoordinateId"),
                required(parameters, "secondCornerCoordinateId"),
                Arrays.asList(required(parameters, "cacheIds").split(",", -1)),
                parseIdleSeconds(parameters),
                timing
        ));
    }

    public PickupAutomationPlan plan(UUID taskId, StowlingConfig config) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(config, "config");
        if (selectedRegionId != null) {
            return purePlan(taskId, config);
        }
        NamedCoordinate standby = coordinate(config, standbyCoordinateId, "standbyCoordinateId");
        NamedCoordinate first = coordinate(config, firstCornerCoordinateId, "firstCornerCoordinateId");
        NamedCoordinate second = coordinate(
                config, secondCornerCoordinateId, "secondCornerCoordinateId"
        );
        requireDimension(standby.dimension(), first.dimension(), "firstCornerCoordinateId");
        requireDimension(standby.dimension(), second.dimension(), "secondCornerCoordinateId");
        Map<String, ContainerRef> caches = new LinkedHashMap<>();
        for (String id : cacheIds) {
            NamedCache cache = config.cacheContainers().stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown cacheIds: " + id));
            requireDimension(standby.dimension(), cache.dimension(), "cacheIds");
            caches.put(id, cache.toContainerRef());
        }
        BlockPosition a = first.position();
        BlockPosition b = second.position();
        BlockPosition minimum = new BlockPosition(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())
        );
        BlockPosition maximum = new BlockPosition(
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())
        );
        PickupRegion region = new PickupRegion(
                REGION_ID, "自定义角点范围", standby.dimension(), standbyCoordinateId,
                standby.position(), new CuboidPickupShape(minimum, maximum)
        );
        PickupWorkflow body = new PickupWorkflow(
                "custom-pickup", "自定义拾取规则", standby.dimension(),
                List.of(new PickupRegionBinding(REGION_ID, 100, idleDelay)),
                cacheIds, CachePolicy.NEAREST_AVAILABLE, classificationTiming,
                CompletionMode.ALL_IDLE, Duration.ZERO
        );
        return new PickupAutomationPlan(
                taskId, body, Optional.empty(), Map.of(REGION_ID, region), caches,
                config.storageRoutes(), config.overflowContainer(), 2, 3,
                Duration.ofSeconds(8), Duration.ofMinutes(5)
        );
    }

    private PickupAutomationPlan purePlan(UUID taskId, StowlingConfig config) {
        PickupRegion region = config.pickupRegions().stream()
                .filter(candidate -> candidate.id().equals(selectedRegionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown regionId: " + selectedRegionId
                ));
        PickupWorkflow body = new PickupWorkflow(
                PURE_WORKFLOW_ID,
                "区域拾取",
                region.dimension(),
                List.of(new PickupRegionBinding(region.id(), 100, idleDelay)),
                List.of(),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ZERO
        );
        return new PickupAutomationPlan(
                taskId,
                body,
                Optional.empty(),
                Map.of(region.id(), region),
                Map.of(),
                List.of(),
                null,
                2,
                3,
                Duration.ofSeconds(8),
                Duration.ofMinutes(5),
                true
        );
    }

    private static NamedCoordinate coordinate(StowlingConfig config, String id, String key) {
        return config.coordinatePoints().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown " + key + ": " + id));
    }

    private static void requireDimension(String expected, String actual, String key) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(key + " must be in dimension " + expected);
        }
    }

    private static String required(Map<String, String> parameters, String key) {
        String value = optional(parameters, key, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return value;
    }

    private static String optional(Map<String, String> parameters, String key, String fallback) {
        String value = parameters.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration parseIdleSeconds(Map<String, String> parameters) {
        long idleSeconds;
        try {
            idleSeconds = Long.parseLong(optional(parameters, "idleSeconds", "2"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("idleSeconds must be a nonnegative integer");
        }
        return Duration.ofSeconds(idleSeconds);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireId(String value, String key) {
        if (value == null || !SAFE_ID.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(key + " must be a valid resource ID");
        }
        return value.trim();
    }
}
