package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleDescriptor.Choice;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ItemAmount;
import dev.stowling.automation.PackingOperationPort;
import dev.stowling.automation.TaskPackingPlan;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class PackItemsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.PACK_ITEMS,
                    "打包物品",
                    "使用任务指定的容器和站点完成独立打包。",
                    List.of(
                            new Parameter(
                                    "sourceCoordinateId",
                                    "来源坐标",
                                    "coordinate",
                                    true
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
                                    "boxCoordinateId",
                                    "打包盒坐标",
                                    "coordinate",
                                    true
                            ),
                            new Parameter(
                                    "outputCoordinateId",
                                    "输出坐标",
                                    "coordinate",
                                    true
                            ),
                            new Parameter(
                                    "outputContainerType",
                                    "输出容器类型",
                                    "choice",
                                    false,
                                    List.of(),
                                    containerTypeChoices()
                            ),
                            new Parameter(
                                    "fallbackCoordinateId",
                                    "溢出坐标",
                                    "coordinate",
                                    true
                            ),
                            new Parameter(
                                    "fallbackContainerType",
                                    "溢出容器类型",
                                    "choice",
                                    false,
                                    List.of(),
                                    containerTypeChoices()
                            ),
                            new Parameter("itemId", "物品 ID", "item", true),
                            new Parameter("count", "数量", "positive-integer", true),
                            new Parameter(
                                    "completionTrigger",
                                    "完成条件",
                                    "choice",
                                    true,
                                    List.of(),
                                    completionTriggerChoices()
                            ),
                            new Parameter(
                                    "completionThreshold",
                                    "完成阈值",
                                    "positive-integer",
                                    true
                            )
                    )
            );

    private final PackingOperationPort packing;
    private final Supplier<StowlingConfig> configSupplier;

    public PackItemsAutomationModule(PackingOperationPort packing) {
        this(packing, () -> {
            throw new IllegalStateException("task packing config is unavailable");
        });
    }

    public PackItemsAutomationModule(
            PackingOperationPort packing,
            Supplier<StowlingConfig> configSupplier
    ) {
        this.packing = Objects.requireNonNull(packing, "packing");
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
            ContainerRef source = TaskContainerParameters.explicitContainerRequired(
                    context.step(),
                    configSupplier,
                    "sourceCoordinateId",
                    "sourceContainerType"
            );
            NamedCoordinate box = TaskContainerParameters.coordinate(
                    configSupplier,
                    ModuleParameters.required(context.step(), "boxCoordinateId")
            );
            ContainerRef output = TaskContainerParameters.explicitContainerRequired(
                    context.step(),
                    configSupplier,
                    "outputCoordinateId",
                    "outputContainerType"
            );
            ContainerRef fallback = TaskContainerParameters.explicitContainerRequired(
                    context.step(),
                    configSupplier,
                    "fallbackCoordinateId",
                    "fallbackContainerType"
            );
            TaskContainerParameters.requireSameDimension(
                    List.of(source, output, fallback),
                    "PACK_ITEMS"
            );
            if (!box.dimension().equals(source.dimension())) {
                throw new IllegalArgumentException(
                        "PACK_ITEMS box must share the source dimension"
                );
            }
            String itemId = ModuleParameters.required(context.step(), "itemId");
            int count = ModuleParameters.positiveInt(context.step(), "count");
            PackingTrigger trigger = completionTrigger(context);
            int threshold = ModuleParameters.positiveInt(
                    context.step(),
                    "completionThreshold"
            );
            TaskPackingPlan plan = plan(
                    source,
                    box,
                    output,
                    fallback,
                    itemId,
                    trigger,
                    threshold
            );
            PackingOperationPort.Request request = new PackingOperationPort.Request(
                    plan,
                    source,
                    List.of(new ItemAmount(itemId, count))
            );
            return OperationModuleSupport.execute(context, request, packing);
        });
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, packing);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, packing);
    }

    private static TaskPackingPlan plan(
            ContainerRef source,
            NamedCoordinate box,
            ContainerRef output,
            ContainerRef fallback,
            String itemId,
            PackingTrigger trigger,
            int threshold
    ) {
        String routeId = "task-" + source.id();
        String stationId = "task-" + box.id();
        StorageRoute route = new StorageRoute(
                routeId,
                List.of(itemId),
                List.of(),
                0,
                source,
                trigger,
                threshold,
                List.of(stationId)
        );
        PackingStation station = new PackingStation(
                stationId,
                box.dimension(),
                box.position(),
                output,
                trigger,
                threshold
        );
        return new TaskPackingPlan(route, List.of(station), fallback);
    }

    private static PackingTrigger completionTrigger(
            AutomationModuleContext context
    ) {
        String value = ModuleParameters.required(
                context.step(),
                "completionTrigger"
        );
        try {
            return PackingTrigger.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "completionTrigger must be ON_ITEM_COUNT or ON_OCCUPANCY"
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

    private static List<Choice> completionTriggerChoices() {
        return List.of(
                new Choice(
                        PackingTrigger.ON_ITEM_COUNT.name(),
                        "按物品数量"
                ),
                new Choice(
                        PackingTrigger.ON_OCCUPANCY.name(),
                        "按占用率"
                )
        );
    }
}
