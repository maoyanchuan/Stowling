package dev.stowling.v2.runtime;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Explicit summaries omit names, component contents, credentials, and arbitrary object strings. */
public final class TaskTraceValues {
    private TaskTraceValues() {
    }

    public static String describe(Object value) {
        String text = summarize(value);
        return text.length() <= 1200 ? text : text.substring(0, 1200) + "…";
    }

    private static String summarize(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof String text) {
            return text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+([#@][a-zA-Z0-9_:.@-]+)?")
                    ? text : "text(omitted)";
        }
        if (value instanceof BlockPosition position) return position.toString();
        if (value instanceof AreaBounds area) return area.toString();
        if (value instanceof ContainerTarget target) {
            return "container(" + target.position() + "," + describe(target.type())
                    + ",standingArea=" + target.standingArea() + ",partner=" + target.partner()
                    + ",storageMode=" + target.storagePolicy().mode() + ")";
        }
        if (value instanceof DroppedItem item) {
            return ItemIdentity.base(item.itemId()) + " x" + item.count() + "@" + item.position();
        }
        if (value instanceof TaskModuleResult result) return result.state() + ":" + result.message();
        if (value instanceof Optional<?> optional) {
            return optional.isEmpty() ? "empty" : describe(optional.orElseThrow());
        }
        if (value instanceof Collection<?> values) {
            return "size=" + values.size() + ",sample="
                    + values.stream().limit(8).map(TaskTraceValues::describe).toList();
        }
        if (value instanceof Map<?, ?> values) {
            return "size=" + values.size() + ",sample=" + values.entrySet().stream().limit(8)
                    .map(entry -> describe(entry.getKey()) + "=" + describe(entry.getValue()))
                    .toList();
        }
        // Only known port result records have scalar state/message getters summarized here.
        if (value.getClass().isRecord()
                && value.getClass().getPackageName().equals("dev.stowling.automation")) {
            StringBuilder text = new StringBuilder(value.getClass().getSimpleName());
            for (var component : value.getClass().getRecordComponents()) {
                if (!java.util.Set.of("state", "succeeded", "message", "remaining", "withdrawn",
                        "matchingItemsRemain", "containers", "contents").contains(component.getName())) {
                    continue;
                }
                try {
                    Object item = component.getAccessor().invoke(value);
                    text.append(" ").append(component.getName()).append("=")
                            .append(component.getName().equals("message") ? item : describe(item));
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    text.append(" unavailable");
                }
            }
            return text.toString();
        }
        return value.getClass().getSimpleName();
    }
}
