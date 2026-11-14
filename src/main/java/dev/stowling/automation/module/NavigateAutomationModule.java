package dev.stowling.automation.module;

import dev.stowling.automation.AutomationClock;
import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.CoordinateResolver;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NavigateAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
            AutomationStepType.NAVIGATE,
            "前往命名坐标",
            "解析命名坐标并等待导航端口确认到达。",
            List.of(new Parameter("coordinateId", "目标命名坐标", "coordinate", true))
    );

    private final CoordinateResolver coordinates;
    private final NavigationPort navigation;
    private final AutomationClock clock;

    public NavigateAutomationModule(
            CoordinateResolver coordinates,
            NavigationPort navigation,
            AutomationClock clock
    ) {
        this.coordinates = Objects.requireNonNull(coordinates, "coordinates");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> navigate(context));
    }

    private AutomationModuleResult navigate(AutomationModuleContext context) {
        String coordinateId = ModuleParameters.required(
                context.step(),
                "coordinateId"
        );
        BlockPosition target = coordinates.resolve(coordinateId).orElseThrow(() ->
                new IllegalArgumentException("未知命名坐标: " + coordinateId)
        );
        boolean inProgress = context.moduleState().containsKey("target");
        NavigationStatus status = inProgress
                ? navigation.status(target)
                : navigation.start(target);
        Map<String, String> state = Map.of(
                "coordinateId", coordinateId,
                "target", target.x() + ":" + target.y() + ":" + target.z(),
                "startedAt", context.moduleState().getOrDefault(
                "startedAt",
                clock.now().toString()
        )
        );
        return switch (status) {
            case ARRIVED -> AutomationModuleResult.succeeded(
                    state,
                    "NAVIGATE:" + coordinateId
            );
            case FAILED -> AutomationModuleResult.failed(
                    "导航失败: " + coordinateId
            );
            case NAVIGATING -> AutomationModuleResult.running(state);
        };
    }
}
