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
        Integer pickupRadius,
        String cacheLocationRef,
        List<AutoClassifyAreaDefinition> classificationAreas,
        TaskPriority collectionPriority,
        boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String unstackableRegionRef, // Read legacy settings before converting them to layout refs.
        String overflowDestinationRef,
        CacheIntakePolicy cachePolicy,
        Kind kind,
        String followUpTaskRef,
        Integer detectionIntervalSeconds,
        Integer followUpDelaySeconds,
        Boolean followUpEnabled,
        ClassificationRemainderPolicy remainderPolicy,
        Boolean robotWarehouseMixedBoxes
) {


    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            Integer pickupRadius,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef,
            String overflowDestinationRef,
            CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer detectionIntervalSeconds,
            Integer followUpDelaySeconds,
            Boolean followUpEnabled,
            ClassificationRemainderPolicy remainderPolicy
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheLocationRef, classificationAreas,
                collectionPriority, overflowEnabled, cleanupPolicy, unstackableRegionRef,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                detectionIntervalSeconds, followUpDelaySeconds, followUpEnabled,
                remainderPolicy, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            Integer pickupRadius,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef,
            String overflowDestinationRef,
            CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer detectionIntervalSeconds,
            Integer followUpDelaySeconds,
            Boolean followUpEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheLocationRef, classificationAreas,
                collectionPriority, overflowEnabled, cleanupPolicy, unstackableRegionRef,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                detectionIntervalSeconds, followUpDelaySeconds, followUpEnabled, null);
    }

    public enum Kind {
        COMBINED,
        PICKUP,
        CLASSIFICATION
    }

    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            Integer pickupRadius, String cacheLocationRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef, String overflowDestinationRef, CacheIntakePolicy cachePolicy,
            Kind kind, String followUpTaskRef, Integer detectionIntervalSeconds,
            Integer followUpDelaySeconds) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheLocationRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                detectionIntervalSeconds, followUpDelaySeconds, null);
    }

    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            Integer pickupRadius, String cacheLocationRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef, String overflowDestinationRef, CacheIntakePolicy cachePolicy,
            Kind kind, String followUpTaskRef) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheLocationRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                null, null);
    }

    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            Integer pickupRadius, String cacheLocationRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef, String overflowDestinationRef,
            CacheIntakePolicy cachePolicy) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheLocationRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, overflowDestinationRef, cachePolicy, Kind.COMBINED, null);
    }

    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            Integer pickupRadius, String cacheLocationRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef, String overflowDestinationRef) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheLocationRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, overflowDestinationRef, null);
    }

    public AutoClassifyDefinition(UUID id, String name, boolean enabled,
            boolean collectionEnabled, boolean classificationEnabled, String detectionCoordinateRef,
            Integer pickupRadius, String cacheLocationRef, List<AutoClassifyAreaDefinition> areas,
            TaskPriority priority, boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String unstackableRegionRef) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detectionCoordinateRef,
                pickupRadius, cacheLocationRef, areas, priority, overflowEnabled, cleanupPolicy,
                unstackableRegionRef, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            Integer pickupRadius,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheLocationRef, classificationAreas,
                collectionPriority, overflowEnabled, cleanupPolicy, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            Integer pickupRadius,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheLocationRef, classificationAreas,
                collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyDefinition(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            String detectionCoordinateRef,
            Integer pickupRadius,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled,
                detectionCoordinateRef, pickupRadius, cacheLocationRef, classificationAreas,
                collectionPriority, false);
    }

    public static final int DEFAULT_PICKUP_RADIUS = 8;
    public static final int MIN_PICKUP_RADIUS = 1;
    public static final int MAX_PICKUP_RADIUS = 64;
    public static final int DEFAULT_DETECTION_INTERVAL_SECONDS = 1;
    public static final int MAX_DETECTION_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_FOLLOW_UP_DELAY_SECONDS = 30;
    public static final int MAX_FOLLOW_UP_DELAY_SECONDS = 86_400;

    public AutoClassifyDefinition {
        detectionIntervalSeconds = detectionIntervalSeconds == null
                ? DEFAULT_DETECTION_INTERVAL_SECONDS : detectionIntervalSeconds;
        followUpDelaySeconds = followUpDelaySeconds == null
                ? DEFAULT_FOLLOW_UP_DELAY_SECONDS : followUpDelaySeconds;
        if (detectionIntervalSeconds < 1
                || detectionIntervalSeconds > MAX_DETECTION_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("掉落物检测间隔需为 1 至 60 秒");
        }
        if (followUpDelaySeconds < 0 || followUpDelaySeconds > MAX_FOLLOW_UP_DELAY_SECONDS) {
            throw new IllegalArgumentException("创建任务倒计时需为 0 至 86400 秒");
        }
        robotWarehouseMixedBoxes = robotWarehouseMixedBoxes == null
                || robotWarehouseMixedBoxes;
        kind = kind == null ? Kind.COMBINED : kind;
        remainderPolicy = remainderPolicy == null
                ? ClassificationRemainderPolicy.DEFAULT : remainderPolicy;
        if (kind != Kind.COMBINED) {
            collectionEnabled = kind == Kind.PICKUP;
            classificationEnabled = kind == Kind.CLASSIFICATION;
            unstackableRegionRef = null;
        }
        if (kind == Kind.PICKUP) {
            classificationAreas = List.of();
            overflowEnabled = false;
            if (followUpTaskRef != null && !followUpTaskRef.isBlank()) {
                followUpTaskRef = requireReference(followUpTaskRef, ResourceType.TASK,
                        "followUpTaskRef");
                if (ResourceReference.parse(followUpTaskRef).uuid().equals(id)) {
                    throw new IllegalArgumentException("掉落物拾取结束后不能创建自身");
                }
            } else {
                followUpTaskRef = null;
            }
        } else {
            followUpTaskRef = null;
        }
        // Legacy configurations used the task reference itself as the opt-in.
        followUpEnabled = kind == Kind.PICKUP && (followUpEnabled == null
                ? followUpTaskRef != null : followUpEnabled);
        if (kind == Kind.CLASSIFICATION) {
            enabled = false;
            pickupRadius = null;
            cachePolicy = CacheIntakePolicy.DEFAULT;
        }
        cachePolicy = cachePolicy == null ? CacheIntakePolicy.DEFAULT : cachePolicy;
        if (kind == Kind.PICKUP && cachePolicy.enabled()
                && cachePolicy.mode() == CacheIntakePolicy.Mode.CLASSIFY_FIRST) {
            throw new IllegalArgumentException("独立拾取任务的打包缓存只支持打包优先");
        }
        cleanupPolicy = cleanupPolicy == null || cleanupPolicy.destinationRef() == null
                ? (cleanupPolicy == null ? TaskCleanupPolicy.DEFAULT : cleanupPolicy)
                        .withDestination(cacheLocationRef) : cleanupPolicy;
        if (cleanupPolicy.resumeMode() != TaskResumeMode.RESTART) {
            throw new IllegalArgumentException("自动分类中断后需要重新扫描分类");
        }
        var boxWarehouse = cleanupPolicy.emptyBoxWarehouse();
        if (boxWarehouse != null && boxWarehouse.enabled()
                && boxWarehouse.warehouseRef().equals(cacheLocationRef)) {
            throw new IllegalArgumentException("空盒仓库和物品来源必须使用不同的位置");
        }
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        detectionCoordinateRef = kind == Kind.CLASSIFICATION ? null : requireReference(
                detectionCoordinateRef,
                ResourceType.COORDINATE,
                "detectionCoordinateRef"
        );
        if (pickupRadius != null && (pickupRadius < MIN_PICKUP_RADIUS
                || pickupRadius > MAX_PICKUP_RADIUS)) {
            throw new IllegalArgumentException(
                    "pickupRadius must be between "
                            + MIN_PICKUP_RADIUS + " and " + MAX_PICKUP_RADIUS
            );
        }
        cacheLocationRef = ResourceReference.parse(cacheLocationRef).encode();
        remainderPolicy.requireSeparateSource(cacheLocationRef);
        if (overflowDestinationRef == null || overflowDestinationRef.isBlank()) {
            if (overflowEnabled) {
                throw new IllegalArgumentException("请选择机器人仓库区域");
            }
            overflowDestinationRef = null;
        } else {
            var destination = ResourceReference.parse(overflowDestinationRef);
            if (destination.type() != ResourceType.REGION
                    && destination.type() != ResourceType.WAREHOUSE) {
                throw new IllegalArgumentException("机器人仓库必须引用容器区域或已有仓库");
            }
            overflowDestinationRef = destination.encode();
            if (cacheLocationRef.equals(overflowDestinationRef)) {
                throw new IllegalArgumentException("机器人仓库与物品来源必须使用不同的位置");
            }
        }
        unstackableRegionRef = unstackableRegionRef == null || unstackableRegionRef.isBlank()
                ? null : requireReference(unstackableRegionRef, ResourceType.REGION,
                        "unstackableRegionRef");
        if (cacheLocationRef.equals(unstackableRegionRef)) {
            throw new IllegalArgumentException("不可堆叠储存区需要使用独立区域");
        }
        classificationAreas = List.copyOf(
                Objects.requireNonNull(
                        classificationAreas,
                        "classificationAreas"
                )
        );
        if (classificationEnabled && classificationAreas.isEmpty() && unstackableRegionRef == null) {
            throw new IllegalArgumentException("请至少选择一个目标分类区");
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
        return kind == Kind.CLASSIFICATION ? id : derivedId("classification");
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
