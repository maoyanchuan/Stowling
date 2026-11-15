package dev.stowling.automation;

import java.util.List;
import java.util.Objects;

public record ContainerScanResult(
        boolean succeeded,
        List<ContainerTarget> containers,
        String message
) {
    public ContainerScanResult {
        containers = List.copyOf(Objects.requireNonNull(containers, "containers"));
        message = Objects.requireNonNull(message, "message");
    }

    public static ContainerScanResult success(List<ContainerTarget> containers) {
        return new ContainerScanResult(
                true,
                containers,
                "扫描到 " + containers.size() + " 个容器"
        );
    }

    public static ContainerScanResult failure(String message) {
        return new ContainerScanResult(false, List.of(), message);
    }
}
