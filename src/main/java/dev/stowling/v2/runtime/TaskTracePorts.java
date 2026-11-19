package dev.stowling.v2.runtime;

import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;

/** Transparent port instrumentation; calls and results retain the delegate's exact semantics. */
public final class TaskTracePorts {
    private TaskTracePorts() {
    }

    public static <T> T wrap(Class<T> type, T delegate) {
        if (Proxy.isProxyClass(delegate.getClass())
                && Proxy.getInvocationHandler(delegate) instanceof Handler) return delegate;
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                new Handler(type, delegate)));
    }

    private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Class<?> type;
        private final Object delegate;

        private Handler(Class<?> type, Object delegate) {
            this.type = type;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0] || delegate.equals(args[0]);
                    case "hashCode" -> delegate.hashCode();
                    default -> type.getSimpleName() + "(traced)";
                };
            }
            // Per-entity probes are summarized by pickup.capacity once per collection tick.
            if (!TaskTrace.active() || method.getName().equals("canPickUp")) {
                return TaskTracePorts.invoke(delegate, method, args);
            }
            String operation = type.getSimpleName() + "." + method.getName();
            boolean polling = method.getName().startsWith("poll")
                    || method.getName().startsWith("observe")
                    || method.getName().startsWith("is")
                    || method.getName().startsWith("has")
                    || java.util.Set.of("tick", "status", "safeToStop", "playerItemCounts")
                            .contains(method.getName());
            String input = args == null ? "" : Arrays.stream(args)
                    .map(TaskTraceValues::describe).toList().toString();
            TaskTrace.event("CALL", operation, input, 0, polling);
            long started = System.nanoTime();
            try {
                Object result = TaskTracePorts.invoke(delegate, method, args);
                TaskTrace.event("RETURN", operation, TaskTraceValues.describe(result),
                        (System.nanoTime() - started) / 1_000_000, polling);
                if (result instanceof TaskModuleExecutor executor) {
                    return wrap(TaskModuleExecutor.class, executor);
                }
                if (result instanceof Optional<?> optional
                        && optional.orElse(null) instanceof TaskModuleExecutor executor) {
                    return Optional.of(wrap(TaskModuleExecutor.class, executor));
                }
                return result;
            } catch (Throwable failure) {
                TaskTrace.event("ERROR", operation, failure.getClass().getSimpleName(),
                        (System.nanoTime() - started) / 1_000_000, false);
                throw failure;
            }
        }
    }
}
