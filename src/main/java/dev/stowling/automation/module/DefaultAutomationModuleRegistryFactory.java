package dev.stowling.automation.module;

import dev.stowling.automation.AutomationClock;
import dev.stowling.automation.AutomationModuleRegistry;
import dev.stowling.automation.CoordinateResolver;
import dev.stowling.automation.CraftingPort;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.PickupWorkflowPort;
import java.util.Objects;

public final class DefaultAutomationModuleRegistryFactory {
    private final CoordinateResolver coordinates;
    private final NavigationPort navigation;
    private final AutomationClock clock;
    private final PickupWorkflowPort pickups;
    private final ItemTransferPort transfers;
    private final InventoryQueryPort inventory;
    private final MaterialAcquisitionPort materials;
    private final CraftingPort crafting;

    public DefaultAutomationModuleRegistryFactory(
            CoordinateResolver coordinates,
            NavigationPort navigation,
            AutomationClock clock,
            PickupWorkflowPort pickups,
            ItemTransferPort transfers,
            InventoryQueryPort inventory,
            MaterialAcquisitionPort materials,
            CraftingPort crafting
    ) {
        this.coordinates = Objects.requireNonNull(coordinates, "coordinates");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pickups = Objects.requireNonNull(pickups, "pickups");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.crafting = Objects.requireNonNull(crafting, "crafting");
    }

    public AutomationModuleRegistry create() {
        AutomationModuleRegistry registry = new AutomationModuleRegistry();
        registry.register(new NavigateAutomationModule(
                coordinates,
                navigation,
                clock
        ));
        registry.register(new WaitAutomationModule(clock));
        registry.register(new RunPickupAutomationModule(pickups));
        registry.register(new TakeItemsAutomationModule(transfers));
        registry.register(new PutItemsAutomationModule(transfers));
        registry.register(new TransferItemsAutomationModule(transfers));
        registry.register(new CheckInventoryAutomationModule(inventory));
        registry.register(new AcquireMaterialsAutomationModule(materials));
        registry.register(new CraftItemAutomationModule(crafting));
        return registry;
    }
}
