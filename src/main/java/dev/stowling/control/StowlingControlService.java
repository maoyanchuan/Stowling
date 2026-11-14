package dev.stowling.control;

import java.util.List;

public interface StowlingControlService extends TaskControl, AuditControl, ConfigControl {
    ControlStatus status();

    List<InventoryInfo> inventory();

    List<PickupSiteInfo> pickupSites();

    List<PickupWorkflowInfo> pickupWorkflows();

    List<ContainerInfo> containers();

    ControlStatus pause();

    ControlStatus resume();
}
