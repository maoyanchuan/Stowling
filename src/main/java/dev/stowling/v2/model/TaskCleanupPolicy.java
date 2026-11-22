package dev.stowling.v2.model;

import java.util.List;

public record TaskCleanupPolicy(String destinationRef, TaskResumeMode resumeMode,
        List<String> emptyBoxRefs, String packingPositionRef) {
    public static final TaskCleanupPolicy DEFAULT = new TaskCleanupPolicy(null, TaskResumeMode.RESTART);

    public TaskCleanupPolicy(String destinationRef, TaskResumeMode resumeMode) {
        this(destinationRef, resumeMode, List.of(), null);
    }

    public TaskCleanupPolicy {
        resumeMode = resumeMode == null ? TaskResumeMode.RESTART : resumeMode;
        if (destinationRef == null || destinationRef.isBlank()) {
            destinationRef = null;
        } else {
            var ref = ResourceReference.parse(destinationRef);
            if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION
                    && ref.type() != ResourceType.WAREHOUSE) {
                throw new IllegalArgumentException("中断存放位置必须引用点位、区域或仓库");
            }
            destinationRef = ref.encode();
        }
        emptyBoxRefs = emptyBoxRefs == null ? List.of() : emptyBoxRefs.stream()
                .map(TaskCleanupPolicy::pointReference).distinct().toList();
        if (emptyBoxRefs.size() > 64) {
            throw new IllegalArgumentException("空盒供应最多选择 64 个点位");
        }
        packingPositionRef = packingPositionRef == null || packingPositionRef.isBlank() ? null
                : pointReference(packingPositionRef);
    }

    public TaskCleanupPolicy withDestination(String ref) {
        return new TaskCleanupPolicy(ref, resumeMode, emptyBoxRefs, packingPositionRef);
    }

    private static String pointReference(String value) {
        var ref = ResourceReference.parse(value);
        if (ref.type() != ResourceType.COORDINATE) {
            throw new IllegalArgumentException("空盒供应和后备打包位置必须引用坐标点");
        }
        return ref.encode();
    }
}
