package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Choice;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RunPickupAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.RUN_PICKUP,
                    "运行拾取",
                    "选择带范围的命名坐标执行纯区域拾取；"
                            + "可等待范围内出现客户端可见物品。移动和存放由其他步骤负责。",
                    List.of(
                            new Parameter(
                                    "coordinateId",
                                    "拾取坐标",
                                    "coordinate",
                                    false
                            ),
                            new Parameter(
                                    "radius",
                                    "拾取半径（格）",
                                    "positive-integer",
                                    false
                            ),
                            new Parameter(
                                    "regionId",
                                    "拾取区域",
                                    "pickup-region",
                                    false
                            ),
                            new Parameter(
                                    "idleSeconds",
                                    "空闲等待秒数",
                                    "nonnegative-integer",
                                    false
                            ),
                            new Parameter(
                                    "waitForItems",
                                    "等待区域有物品再拾取",
                                    "boolean",
                                    false
                            ),
                            new Parameter(
                                    "standbyCoordinateId",
                                    "待机坐标（旧配置）",
                                    "coordinate",
                                    false
                            ),
                            new Parameter(
                                    "firstCornerCoordinateId",
                                    "拾取角点一（旧配置）",
                                    "coordinate",
                                    false
                            ),
                            new Parameter(
                                    "secondCornerCoordinateId",
                                    "拾取角点二（旧配置）",
                                    "coordinate",
                                    false
                            ),
                            new Parameter(
                                    "cacheIds",
                                    "缓存容器（旧配置）",
                                    "containers",
                                    false
                            ),
                            new Parameter(
                                    "classificationTiming",
                                    "分类时机（旧配置）",
                                    "choice",
                                    false,
                                    List.of(),
                                    List.of(
                                            new Choice("AFTER_REGION", "区域结束后"),
                                            new Choice("AFTER_ROUND", "一轮结束后")
                                    )
                            ),
                            new Parameter("workflowId", "拾取流程（旧配置）", "pickup", false)
                    )
            );

    private final PickupWorkflowPort pickup;

    public RunPickupAutomationModule(PickupWorkflowPort pickup) {
        this.pickup = Objects.requireNonNull(pickup, "pickup");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            Optional<CustomPickupRules> rules = CustomPickupRules.fromParameters(
                    context.step().parameters()
            );
            boolean waitForItems = ModuleParameters.optional(
                    context.step(),
                    "waitForItems"
            ).map(RunPickupAutomationModule::parseBoolean).orElse(false);
            PickupWorkflowPort.Request request = rules.isPresent()
                    ? PickupWorkflowPort.Request.custom(rules.orElseThrow(), waitForItems)
                    : new PickupWorkflowPort.Request(
                            ModuleParameters.firstRequired(context.step(), "workflowId"),
                            ModuleParameters.optional(context.step(), "regionId"),
                            waitForItems
            );
            return OperationModuleSupport.execute(context, request, pickup);
        });
    }

    private static boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "waitForItems must be true or false"
            );
        };
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, pickup);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, pickup);
    }
}
