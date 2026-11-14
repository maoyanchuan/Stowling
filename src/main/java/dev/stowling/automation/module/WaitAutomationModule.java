package dev.stowling.automation.module;

import dev.stowling.automation.AutomationClock;
import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WaitAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
            AutomationStepType.WAIT,
            "等待",
            "按自动化时钟等待指定秒数，重启后继续使用原截止时间。",
            List.of(new Parameter("seconds", "等待秒数", "positive-integer", true))
    );

    private final AutomationClock clock;

    public WaitAutomationModule(AutomationClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> waitUntilDeadline(context));
    }

    private AutomationModuleResult waitUntilDeadline(
            AutomationModuleContext context
    ) {
        String rawSeconds = ModuleParameters.firstRequired(
                context.step(),
                "seconds",
                "durationSeconds"
        );
        long seconds = parseSeconds(rawSeconds);
        long deadline = deadline(context, seconds);
        Instant now = clock.now();
        Map<String, String> state = Map.of(
                "deadlineEpochMillis", Long.toString(deadline),
                "seconds", Long.toString(seconds)
        );
        if (now.toEpochMilli() >= deadline) {
            return AutomationModuleResult.succeeded(
                    state,
                    "WAIT:" + seconds
            );
        }
        return AutomationModuleResult.running(state);
    }

    private long deadline(AutomationModuleContext context, long seconds) {
        String stored = context.moduleState().get("deadlineEpochMillis");
        if (stored != null) {
            try {
                return Long.parseLong(stored);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("等待截止时间无效");
            }
        }
        try {
            return Math.addExact(
                    clock.now().toEpochMilli(),
                    Math.multiplyExact(seconds, 1_000L)
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("等待时长过大");
        }
    }

    private long parseSeconds(String value) {
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("seconds 必须是正整数");
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("seconds 必须是正整数");
        }
        return parsed;
    }
}
