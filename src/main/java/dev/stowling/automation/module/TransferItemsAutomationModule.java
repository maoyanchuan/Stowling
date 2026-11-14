package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleDescriptor.Choice;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class TransferItemsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.TRANSFER_ITEMS,
                    "容器搬运",
                    "在两个容器之间搬移指定物品。",
                    List.of(
                            new Parameter(
                                    "sourceCoordinateId",
                                    "来源坐标",
                                    "coordinate",
                                    true,
                                    List.of("sourceContainerId")
                            ),
                            new Parameter(
                                    "sourceContainerType",
                                    "来源容器类型",
                                    "choice",
                                    false,
                                    List.of(),
                                    containerTypeChoices()
                            ),
                            new Parameter(
                                    "destinationCoordinateId",
                                    "目标坐标",
                                    "coordinate",
                                    true,
                                    List.of("destination")
                            ),
                            new Parameter(
                                    "destinationContainerType",
                                    "目标容器类型",
                                    "choice",
                                    false,
                                    List.of(),
                                    containerTypeChoices()
                            ),
                            new Parameter("itemId", "物品 ID", "item", true),
                            new Parameter("count", "数量", "positive-integer", true)
                    )
            );

    private final ItemTransferPort transfers;
    private final Supplier<StowlingConfig> configSupplier;

    public TransferItemsAutomationModule(ItemTransferPort transfers) {
        this(transfers, () -> {
            throw new IllegalStateException("task container config is unavailable");
        });
    }

    public TransferItemsAutomationModule(
            ItemTransferPort transfers,
            Supplier<StowlingConfig> configSupplier
    ) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.configSupplier = Objects.requireNonNull(
                configSupplier,
                "configSupplier"
        );
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            Optional<ContainerRef> source =
                    TaskContainerParameters.explicitContainer(
                    context.step(),
                    configSupplier,
                    "sourceCoordinateId",
                    "sourceContainerType"
            );
            Optional<ContainerRef> destination =
                    TaskContainerParameters.explicitContainer(
                    context.step(),
                    configSupplier,
                    "destinationCoordinateId",
                    "destinationContainerType"
            );
            requirePairedExplicitContainers(source, destination);
            if (source.isPresent() && destination.isPresent()) {
                TaskContainerParameters.requireSameDimension(
                        List.of(source.orElseThrow(), destination.orElseThrow()),
                        "TRANSFER"
                );
            }
            String sourceId = source.map(ContainerRef::id)
                    .orElseGet(() -> ModuleParameters.required(
                    context.step(),
                    "sourceContainerId"
            ));
            String destinationId = destination.map(ContainerRef::id)
                    .orElseGet(() -> ModuleParameters.required(
                    context.step(),
                    "destination"
            ));
            ItemTransferPort.Request request = new ItemTransferPort.Request(
                    ItemTransferPort.Mode.TRANSFER,
                    sourceId,
                    destinationId,
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count"),
                    source,
                    destination
            );
            return OperationModuleSupport.execute(context, request, transfers);
        });
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, transfers);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, transfers);
    }

    private static void requirePairedExplicitContainers(
            Optional<ContainerRef> source,
            Optional<ContainerRef> destination
    ) {
        if (source.isPresent() != destination.isPresent()) {
            throw new IllegalArgumentException(
                    "sourceCoordinateId and destinationCoordinateId must be provided together"
            );
        }
    }

    private static List<Choice> containerTypeChoices() {
        return List.of(
                new Choice(ContainerType.CHEST.name(), "箱子"),
                new Choice(ContainerType.TRAPPED_CHEST.name(), "陷阱箱"),
                new Choice(ContainerType.BARREL.name(), "木桶"),
                new Choice(ContainerType.SHULKER_BOX.name(), "潜影盒"),
                new Choice(ContainerType.HOPPER.name(), "漏斗"),
                new Choice(ContainerType.OTHER.name(), "其他")
        );
    }
}
