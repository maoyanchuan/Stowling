package dev.stowling.control;

import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;

@FunctionalInterface
public interface PickupRuntimeFactory {
    PickupAutomationEngine create(PickupAutomationPlan plan);
}
