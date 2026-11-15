package dev.stowling.automation;

import dev.stowling.v2.model.RegionDefinition;
import java.util.Set;

public interface ContainerRegionPort {
    ContainerScanResult scan(
            RegionDefinition region,
            Set<String> containerTypes,
            int maxVolume
    );
}
