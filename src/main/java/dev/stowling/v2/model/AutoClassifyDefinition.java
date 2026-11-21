package dev.stowling.v2.model;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AutoClassifyDefinition(
        UUID id,
        String name,
        boolean enabled,
        boolean collectionEnabled,
        boolean classificationEnabled,
        String detectionCoordinateRef,
        int pickupRadius,
        String cacheRegionRef,
        List<AutoClassifyAreaDefinition> classificationAreas,
        TaskPriority collectionPriority,
        boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String unstackableRegionRef, // Read legacy settings before converting them to layout refs.
        String overflowDestinationRef
) {
    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            int pickupRadius, String cacheRegionRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheRegionRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            int pickupRadius,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheRegionRef, classificationAreas,
                collectionPriority, overflowEnabled, cleanupPolicy, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            int pickupRadius,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheRegionRef, classificationAreas,
                collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            int pickupRadius,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheRegionRef, classificationAreas,
                collectionPriority, false);
    }

    public static final int DEFAULT_PICKUP_RADIUS = 8;
    public static final int MIN_PICKUP_RADIUS = 1;
    public static final int MAX_PICKUP_RADIUS = 64;

    public AutoClassifyDefinition {
        cleanupPolicy = cleanupPolicy == null || cleanupPolicy.destinationRef() == null
                ? new TaskCleanupPolicy(cacheRegionRef, TaskResumeMode.RESTART) : cleanupPolicy;
        if (cleanupPolicy.resumeMode() != TaskResumeMode.RESTART) {
            throw new IllegalArgumentException("自动分类中断后需要重新扫描分类");
        }
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        detectionCoordinateRef = requireReference(
                detectionCoordinateRef,
                ResourceType.COORDINATE,
                "detectionCoordinateRef"
        );
        if (pickupRadius < MIN_PICKUP_RADIUS
                || pickupRadius > MAX_PICKUP_RADIUS) {
            throw new IllegalArgumentException(
                    "pickupRadius must be between "
                            + MIN_PICKUP_RADIUS + " and " + MAX_PICKUP_RADIUS
            );
        }
        cacheRegionRef = requireReference(
                cacheRegionRef,
                ResourceType.REGION,
                "cacheRegionRef"
        );
        if (!overflowEnabled || overflowDestinationRef == null || overflowDestinationRef.isBlank()) {
            overflowDestinationRef = null;
        } else {
            ResourceReference destination = ResourceReference.parse(overflowDestinationRef);
            if (destination.type() != ResourceType.COORDINATE
                    && destination.type() != ResourceType.REGION) {
                throw new IllegalArgumentException("溢出存入位置必须是坐标或区域");
            }
            overflowDestinationRef = destination.encode();
            if (cacheRegionRef.equals(overflowDestinationRef)) {
                throw new IllegalArgumentException("溢出存入位置需要与缓存区域分开");
            }
        }
        unstackableRegionRef = unstackableRegionRef == null || unstackableRegionRef.isBlank()
                ? null : requireReference(unstackableRegionRef, ResourceType.REGION,
                        "unstackableRegionRef");
        if (cacheRegionRef.equals(unstackableRegionRef)) {
            throw new IllegalArgumentException("不可堆叠储存区需要使用独立区域");
        }
        classificationAreas = List.copyOf(
                Objects.requireNonNull(
                        classificationAreas,
                        "classificationAreas"
                )
        );
        if (classificationAreas.isEmpty() && unstackableRegionRef == null) {
            throw new IllegalArgumentException("请至少添加一个分类选区");
        }
        long uniqueZones = classificationAreas.stream()
                .map(AutoClassifyAreaDefinition::categoryZoneRef)
                .distinct()
                .count();
        if (uniqueZones != classificationAreas.size()) {
            throw new IllegalArgumentException(
                    "classificationAreas must use unique zones"
            );
        }
        collectionPriority = Objects.requireNonNull(
                collectionPriority,
                "collectionPriority"
        );
        if (collectionPriority != TaskPriority.NORMAL
                && collectionPriority != TaskPriority.URGENT) {
            throw new IllegalArgumentException(
                    "collectionPriority must be NORMAL or URGENT"
            );
        }
    }

    public UUID classificationTaskId() {
        return derivedId("classification");
    }

    public UUID interruptionTaskId() {
        return derivedId("interruption");
    }

    public UUID collectionTriggerId() {
        return derivedId("collection-trigger");
    }

    public UUID classificationTriggerId() {
        return derivedId("classification-trigger");
    }

    public UUID derivedId(String role) {
        return UUID.nameUUIDFromBytes(
                (id + ":" + role).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException(
                    "name must contain 1 to 80 characters"
            );
        }
        return trimmed;
    }

    private static String requireReference(
            String value,
            ResourceType expected,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != expected) {
            throw new IllegalArgumentException(
                    field + " must reference " + expected.name()
            );
        }
        return reference.encode();
    }
}
