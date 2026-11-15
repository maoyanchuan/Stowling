package dev.stowling.automation;

import java.util.List;

public interface ContainerCategoryPort {
    ContainerCategoryResult match(List<ContainerTarget> containers);
}
