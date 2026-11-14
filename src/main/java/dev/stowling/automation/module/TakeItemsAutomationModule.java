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

public final class TakeItemsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.TAKE_ITEMS,
                    "从容器取物",
                    "从指定容器取物到机器人背包。",
                    List.of(
                            new Parameter(
                                    "coordinateId",
                                    "来源坐标",
                                    "coordinate",
                                    true,
                                    List.of("containerId")
                            ),
                            new Parameter(
                                    "containerType",
                                    "容器类型",
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

    public TakeItemsAutomationModule(ItemTransferPort transfers) {
        this(transfers, () -> {
            throw new IllegalStateException("task container config is unavailable");
        });
    }

    public TakeItemsAutomationModule(
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
                    "coordinateId",
                    "containerType"
            );
            String sourceId = source.map(ContainerRef::id)
                    .orElseGet(() -> ModuleParameters.required(
                    context.step(),
                    "containerId"
            ));
            ItemTransferPort.Request request = new ItemTransferPort.Request(
                    ItemTransferPort.Mode.TAKE,
                    sourceId,
                    "PLAYER",
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count"),
                    source,
                    Optional.empty()
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
