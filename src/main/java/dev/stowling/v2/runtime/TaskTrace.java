package dev.stowling.v2.runtime;

import java.util.UUID;
import java.util.function.Supplier;

/** Client-thread scope also reaches nested Minecraft operations without passing task context. */
public final class TaskTrace {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TaskTrace() {
    }

    public static <T> T within(TaskDiagnosticLog log, UUID owner, String phase, String step,
            String module, Supplier<T> operation) {
        if (log == null) return operation.get();
        Context previous = CURRENT.get();
        CURRENT.set(new Context(log, owner, phase, step, module));
        try {
            return operation.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void event(String kind, String operation, String details) {
        event(kind, operation, details, 0, true);
    }

    public static void event(String kind, String operation, String details, long elapsedMillis,
            boolean repeatable) {
        Context context = CURRENT.get();
        if (context == null) return;
        context.log.append(context.owner, context.phase, context.step, context.module,
                kind, operation, details, elapsedMillis, repeatable);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    private record Context(TaskDiagnosticLog log, UUID owner, String phase, String step,
            String module) {
    }
}
