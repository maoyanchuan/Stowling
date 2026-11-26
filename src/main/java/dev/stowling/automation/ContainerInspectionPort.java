package dev.stowling.automation;

import java.util.Objects;

public interface ContainerInspectionPort {
    /** A task-owned read session; cancel it even between inspections to release its hand slot. */
    default ContainerInspectionPort openReadSession() {
        return this;
    }

    ContainerInspectionResult startInspection(ContainerTarget target);

    ContainerInspectionResult pollInspection();

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
