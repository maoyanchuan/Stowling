package dev.stowling.automation;

import java.util.Objects;

public interface ContainerInspectionPort {
    /** Refresh-owned session: reuse the safe hand and reachable position until canceled. */
    default ContainerInspectionPort openReadSession() {
        return this;
    }

    ContainerInspectionResult startInspection(ContainerTarget target);

    ContainerInspectionResult pollInspection();

    /** Caller may close the session before yielding only when no open request was sent. */
    default boolean canPauseBeforeOpen() {
        return false;
    }

    void cancelInspection();

    ContainerInspectionPort UNAVAILABLE = new ContainerInspectionPort() {
        @Override
        public ContainerInspectionResult startInspection(
                ContainerTarget target
        ) {
            Objects.requireNonNull(target, "target");
            return ContainerInspectionResult.failed("容器检查组件不可用");
        }

        @Override
        public ContainerInspectionResult pollInspection() {
            return ContainerInspectionResult.failed("容器检查组件不可用");
        }

        @Override
        public void cancelInspection() {
        }
    };
}
