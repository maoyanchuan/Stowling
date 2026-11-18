package dev.stowling.v2.model;

public record TaskCleanupPolicy(String destinationRef, TaskResumeMode resumeMode) {
    public static final TaskCleanupPolicy DEFAULT =
            new TaskCleanupPolicy(null, TaskResumeMode.RESTART);

    public TaskCleanupPolicy {
        resumeMode = resumeMode == null ? TaskResumeMode.RESTART : resumeMode;
        if (destinationRef == null || destinationRef.isBlank()) {
            destinationRef = null;
        } else {
            ResourceReference ref = ResourceReference.parse(destinationRef);
            if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION) {
                throw new IllegalArgumentException("中断存放位置必须引用坐标或区域");
            }
            destinationRef = ref.encode();
        }
    }
}
