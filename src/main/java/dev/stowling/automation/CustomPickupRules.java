package dev.stowling.automation;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
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
        String selectedRegionId,
        String selectedCoordinateId,
        int radius
) {
    private static final String REGION_ID = "custom-pickup-region";
    private static final String COORDINATE_REGION_ID = "custom-pickup-coordinate";
    private static final String PURE_WORKFLOW_ID = "pickup-region";
    private static final Set<String> LEGACY_PARAMETER_KEYS = Set.of(
            "standbyCoordinateId", "firstCornerCoordinateId", "secondCornerCoordinateId",
            "cacheIds", "classificationTiming"
    );
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public CustomPickupRules {
        boolean pureRegion = selectedRegionId != null && !selectedRegionId.isBlank();
        boolean pureCoordinate = selectedCoordinateId != null
                && !selectedCoordinateId.isBlank();
        if (pureRegion && pureCoordinate) {
            throw new IllegalArgumentException(
                    "pickup cannot select both regionId and coordinateId"
            );
        }
        if (pureRegion) {
            selectedRegionId = requireId(selectedRegionId, "selectedRegionId");
            selectedCoordinateId = null;
            if (radius != 0) {
                throw new IllegalArgumentException("radius is only valid with coordinateId");
            }
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
        } else if (pureCoordinate) {
            selectedCoordinateId = requireId(selectedCoordinateId, "selectedCoordinateId");
            selectedRegionId = null;
            if (radius < 0 || radius > 128) {
                throw new IllegalArgumentException(
                        "radius must be 0 or an integer between 1 and 128"
                );
            }
            if (hasText(standbyCoordinateId)
                    || hasText(firstCornerCoordinateId)
                    || hasText(secondCornerCoordinateId)
                    || cacheIds != null && !cacheIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "pure coordinate pickup cannot mix legacy pickup rules"
                );
            }
            standbyCoordinateId = "";
            firstCornerCoordinateId = "";
            secondCornerCoordinateId = "";
            cacheIds = List.of();
        } else {
            selectedRegionId = null;
            selectedCoordinateId = null;
            if (radius != 0) {
                throw new IllegalArgumentException("radius requires coordinateId");
            }
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
        classificationTiming = (pureRegion || pureCoordinate) && classificationTiming == null
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
                null,
                null,
                0
        );
    }

    public CustomPickupRules(
            String standbyCoordinateId,
            String firstCornerCoordinateId,
            String secondCornerCoordinateId,
            List<String> cacheIds,
            Duration idleDelay,
            ClassificationTiming classificationTiming,
            String selectedRegionId
    ) {
        this(
                standbyCoordinateId,
                firstCornerCoordinateId,
                secondCornerCoordinateId,
                cacheIds,
                idleDelay,
                classificationTiming,
                selectedRegionId,
                null,
                0
        );
    }

    public static Optional<CustomPickupRules> fromParameters(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        String workflowId = optional(parameters, "workflowId", "");
        String regionId = optional(parameters, "regionId", "");
        String coordinateId = optional(parameters, "coordinateId", "");
        boolean pureRegion = !regionId.isEmpty();
        boolean hasCoordinateParameter = parameters.containsKey("coordinateId");
        boolean hasRadiusParameter = parameters.containsKey("radius");
        boolean hasLegacyRules = LEGACY_PARAMETER_KEYS.stream()
                .anyMatch(parameters::containsKey);
        boolean hasIdleSeconds = parameters.containsKey("idleSeconds");
        if (pureRegion && hasCoordinateParameter) {
            throw new IllegalArgumentException(
                    "pickup cannot select both regionId and coordinateId"
            );
        }
        if (hasRadiusParameter && !hasCoordinateParameter) {
            throw new IllegalArgumentException("radius requires coordinateId");
        }
        if (!workflowId.isEmpty()) {
            if (hasLegacyRules || hasIdleSeconds || hasCoordinateParameter
                    || hasRadiusParameter) {
                throw new IllegalArgumentException(
                        "legacy workflows cannot mix direct, coordinate, or idle pickup rules"
                );
            }
            return Optional.empty();
        }
        if (pureRegion) {
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
                    requireId(regionId, "regionId"),
                    null,
                    0
            ));
        }
        if (hasCoordinateParameter) {
            if (coordinateId.isEmpty()) {
                throw new IllegalArgumentException("coordinateId must not be blank");
            }
            if (hasLegacyRules) {
                throw new IllegalArgumentException(
                        "pure coordinate pickup cannot mix legacy pickup rules"
                );
            }
            return Optional.of(new CustomPickupRules(
                    "",
                    "",
                    "",
                    List.of(),
                    parseIdleSeconds(parameters),
                    null,
                    null,
                    requireId(coordinateId, "coordinateId"),
                    parseRadius(parameters)
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
            PickupRegion region = config.pickupRegions().stream()
                    .filter(candidate -> candidate.id().equals(selectedRegionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown regionId: " + selectedRegionId
                    ));
            return purePlan(taskId, region);
        }
        if (selectedCoordinateId != null) {
            NamedCoordinate coordinate = coordinate(
                    config,
                    selectedCoordinateId,
                    "coordinateId"
            );
            PickupShape shape;
            if (radius > 0) {
                if (coordinate.type() != CoordinateType.POINT) {
                    throw new IllegalArgumentException(
                            "radius is only valid for legacy POINT coordinate pickups"
                    );
                }
                shape = new SpherePickupShape(
                        coordinate.id(),
                        coordinate.position(),
                        radius
                );
            } else {
                shape = coordinate.pickupShape();
            }
            PickupRegion region = new PickupRegion(
                    COORDINATE_REGION_ID,
                    "命名坐标拾取",
                    coordinate.dimension(),
                    coordinate.id(),
                    coordinate.position(),
                    shape
            );
            return purePlan(taskId, region);
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

    private PickupAutomationPlan purePlan(UUID taskId, PickupRegion region) {
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

    private static int parseRadius(Map<String, String> parameters) {
        String value = parameters.get("radius");
        if (value == null) {
            return 0;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "radius must be an integer between 1 and 128"
            );
        }
        final int radius;
        try {
            radius = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "radius must be an integer between 1 and 128"
            );
        }
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException(
                    "radius must be an integer between 1 and 128"
            );
        }
        return radius;
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
