package dev.stowling.v2.model;

import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TaskInteractionCompiler {
    public static final String OPERATION = "operation";

    private static final Set<String> ALLOWED = Set.of(
            OPERATION,
            ConfigValidator.AREA_REF,
            ConfigValidator.TARGET_REF,
            ConfigValidator.CONTAINER_TYPES,
            ConfigValidator.MAX_VOLUME,
            ConfigValidator.ITEM_SOURCE,
            ConfigValidator.ITEM_FILTER,
            ConfigValidator.TIMEOUT_SECONDS
    );
    private static final Set<String> SOURCES = Set.of(
            "collected",
            "carried",
            "managed"
    );

    private TaskInteractionCompiler() {
    }

    public static TaskDefinition compile(
            TaskDefinition task,
            List<TaskTriggerDefinition> triggers
    ) {
        Objects.requireNonNull(task, "task");
        List<TaskTriggerDefinition> frozenTriggers = List.copyOf(
                Objects.requireNonNull(triggers, "triggers")
        );
        List<TaskStep> steps = compileSteps(task, task.steps(), frozenTriggers);
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                steps,
                task.interruptionMode(),
                task.interruptionTaskRef(),
                task.defaultInterruptionFlow(),
                task.requeueAfterInterruption(),
                task.folderId(),
                task.cleanupPolicy(),
                task.autoEatOptions()
        );
    }

    public static void validateCompactStep(TaskStep step) {
        if (step.kind() != TaskStepKind.ACTION) {
            return;
        }
        if (!ConfigValidator.CONTAINER_INTERACTION.equals(step.moduleId())) {
            throw new IllegalArgumentException(
                    "not a container interaction step"
            );
        }
        Map<String, String> parameters = step.parameters();
        if (!ALLOWED.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException(
                    "CONTAINER_INTERACTION has unsupported parameters"
            );
        }
        String operation = required(parameters, OPERATION);
        switch (operation) {
            case "COLLECT" -> validateCollect(parameters);
            case "FIND" -> validateFind(parameters);
            case "READ" -> validateRead(parameters);
            case "WITHDRAW" -> validateWithdraw(parameters);
            case "DEPOSIT" -> validateDeposit(parameters);
            default -> throw new IllegalArgumentException(
                    "unsupported CONTAINER_INTERACTION operation: " + operation
            );
        }
    }

    private static void validateCollect(Map<String, String> parameters) {
        requireOnly(
                parameters,
                Set.of(
                        ConfigValidator.AREA_REF,
                        ConfigValidator.ITEM_FILTER,
                        ConfigValidator.TIMEOUT_SECONDS
                )
        );
        String area = parameters.getOrDefault(ConfigValidator.AREA_REF, "detector");
        if (!"detector".equals(area)) {
            ResourceReference reference = ResourceReference.parse(area);
            if (reference.type() != ResourceType.COORDINATE
                    && reference.type() != ResourceType.REGION) {
                throw new IllegalArgumentException(
                        "COLLECT areaRef must be detector, coordinate or region"
                );
            }
        }
        validateInteger(
                parameters,
                ConfigValidator.TIMEOUT_SECONDS,
                0,
                86_400
        );
        validateFilter(parameters);
    }

    private static void validateFind(Map<String, String> parameters) {
        requireOnly(
                parameters,
                Set.of(
                        ConfigValidator.TARGET_REF,
                        ConfigValidator.CONTAINER_TYPES,
                        ConfigValidator.MAX_VOLUME
                )
        );
        ResourceReference target = ResourceReference.parse(
                required(parameters, ConfigValidator.TARGET_REF)
        );
        if (target.type() == ResourceType.REGION) {
            validateInteger(
                    parameters,
                    ConfigValidator.MAX_VOLUME,
                    1,
                    1_048_576
            );
            return;
        }
        if (target.type() == ResourceType.CATEGORY_ZONE) {
            if (parameters.containsKey(ConfigValidator.CONTAINER_TYPES)) {
                throw new IllegalArgumentException(
                        "FIND category-zone does not support containerTypes"
                );
            }
            validateInteger(
                    parameters,
                    ConfigValidator.MAX_VOLUME,
                    1,
                    1_048_576
            );
            return;
        }
        throw new IllegalArgumentException(
                "FIND targetRef must be region or category-zone"
        );
    }
    private static void validateRead(Map<String, String> parameters) {
        requireOnly(parameters, Set.of(ConfigValidator.TARGET_REF));
        ResourceReference target = ResourceReference.parse(
                required(parameters, ConfigValidator.TARGET_REF)
        );
        requireContainerTarget(target, "READ");
    }

    private static void validateWithdraw(Map<String, String> parameters) {
        requireOnly(
                parameters,
                Set.of(ConfigValidator.TARGET_REF, ConfigValidator.ITEM_SOURCE)
        );
        ResourceReference target = ResourceReference.parse(
                required(parameters, ConfigValidator.TARGET_REF)
        );
        requireContainerTarget(target, "WITHDRAW");
        String source = parameters.getOrDefault(
                ConfigValidator.ITEM_SOURCE,
                "classified"
        );
        if (!"classified".equals(source)) {
            throw new IllegalArgumentException(
                    "WITHDRAW itemSource must be classified"
            );
        }
    }

    private static void validateDeposit(Map<String, String> parameters) {
        requireOnly(
                parameters,
                Set.of(
                        ConfigValidator.TARGET_REF,
                        ConfigValidator.CONTAINER_TYPES,
                        ConfigValidator.MAX_VOLUME,
                        ConfigValidator.ITEM_SOURCE
                )
        );
        ResourceReference target = ResourceReference.parse(
                required(parameters, ConfigValidator.TARGET_REF)
        );
        if (target.type() != ResourceType.COORDINATE
                && target.type() != ResourceType.REGION
                && target.type() != ResourceType.CATEGORY_ZONE
                && target.type() != ResourceType.OUTPUT) {
            throw new IllegalArgumentException(
                    "DEPOSIT targetRef must be coordinate, region, "
                            + "category-zone or output"
            );
        }
        if (target.type() == ResourceType.COORDINATE
                || target.type() == ResourceType.OUTPUT) {
            if (parameters.containsKey(ConfigValidator.CONTAINER_TYPES)
                    || parameters.containsKey(ConfigValidator.MAX_VOLUME)) {
                throw new IllegalArgumentException(
                        "single DEPOSIT targets do not support scan parameters"
                );
            }
        }
        String source = parameters.getOrDefault(
                ConfigValidator.ITEM_SOURCE,
                "collected"
        );
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException(
                    "DEPOSIT itemSource is not supported"
            );
        }
        validateInteger(
                parameters,
                ConfigValidator.MAX_VOLUME,
                1,
                1_048_576
        );
    }

    private static void requireContainerTarget(
            ResourceReference target,
            String operation
    ) {
        if (target.type() == ResourceType.COORDINATE) {
            return;
        }
        if (target.type() == ResourceType.OUTPUT
                && ("container".equals(target.key())
                || "marker".equals(target.key()))) {
            return;
        }
        throw new IllegalArgumentException(
                operation + " targetRef must be a coordinate or container output"
        );
    }

    private static List<TaskStep> compileSteps(
            TaskDefinition task,
            List<TaskStep> steps,
            List<TaskTriggerDefinition> triggers
    ) {
        List<TaskStep> result = new ArrayList<>();
        for (TaskStep step : steps) {
            if (step.kind() == TaskStepKind.ACTION
                    && ConfigValidator.SORT_UNSTACKABLE_ITEMS.equals(step.moduleId())) {
                result.addAll(sortUnstackable(step));
                continue;
            }
            if (step.kind() == TaskStepKind.ACTION
                    && ConfigValidator.CONTAINER_INTERACTION.equals(
                    step.moduleId()
            )) {
                validateCompactStep(step);
                result.addAll(compileInteraction(task, step, triggers));
                continue;
            }
            List<TaskStep> nested = compileSteps(task, step.steps(), triggers);
            List<TaskStep> otherwise = compileSteps(
                    task,
                    step.otherwise(),
                    triggers
            );
            if (nested.equals(step.steps())
                    && otherwise.equals(step.otherwise())) {
                result.add(step);
            } else {
                result.add(new TaskStep(
                        step.id(),
                        step.kind(),
                        step.moduleId(),
                        step.parameters(),
                        nested,
                        otherwise
                ));
            }
        }
        return List.copyOf(result);
    }

    private static List<TaskStep> sortUnstackable(TaskStep step) {
        Map<String, String> parameters = step.parameters();
        if (!Set.of(ConfigValidator.REGION_REF, ConfigValidator.TARGET_REF)
                .containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("SORT_UNSTACKABLE_ITEMS has unsupported parameters");
        }
        ResourceReference cache = ResourceReference.parse(
                required(parameters, ConfigValidator.REGION_REF));
        ResourceReference storage = ResourceReference.parse(
                required(parameters, ConfigValidator.TARGET_REF));
        if (cache.type() != ResourceType.REGION || storage.type() != ResourceType.REGION) {
            throw new IllegalArgumentException("不可堆叠分类的缓存和储存目标必须是区域");
        }
        if (cache.equals(storage)) {
            throw new IllegalArgumentException("不可堆叠储存区需要使用独立区域");
        }
        return TaskFlowTemplates.sortUnstackable(cache.encode(), storage.encode());
    }

    private static List<TaskStep> compileInteraction(
            TaskDefinition task,
            TaskStep step,
            List<TaskTriggerDefinition> triggers
    ) {
        String operation = required(step.parameters(), OPERATION);
        return switch (operation) {
            case "COLLECT" -> List.of(collect(task, step, triggers));
            case "FIND" -> find(step);
            case "READ" -> read(step);
            case "WITHDRAW" -> withdraw(step);
            case "DEPOSIT" -> deposit(step);
            default -> throw new IllegalArgumentException(
                    "unsupported CONTAINER_INTERACTION operation: " + operation
            );
        };
    }

    private static TaskStep collect(
            TaskDefinition task,
            TaskStep step,
            List<TaskTriggerDefinition> triggers
    ) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String area = step.parameters().getOrDefault(
                ConfigValidator.AREA_REF,
                "detector"
        );
        if ("detector".equals(area)) {
            List<TaskTriggerDefinition> detectors = triggers.stream()
                    .filter(trigger -> trigger.targetTaskId().equals(task.id()))
                    .filter(trigger -> trigger.type()
                            == TaskTriggerType.DROPPED_ITEMS)
                    .toList();
            if (detectors.isEmpty()) {
                throw new DetectorNotBoundException(
                        "拾取范围设为 detector，但任务没有掉落物检测器；"
                                + "请绑定检测器或指定坐标/区域拾取范围"
                );
            }
            if (detectors.size() > 1) {
                throw new IllegalArgumentException(
                        "任务绑定了多个掉落物检测器，无法确定 detector "
                                + "拾取范围；请指定坐标/区域或只保留一个检测器"
                );
            }
            TaskTriggerDefinition detector = detectors.getFirst();
            parameters.put(
                    ConfigValidator.AREA_REF,
                    ResourceReference.of(
                            ResourceType.COORDINATE,
                            detector.coordinateId()
                    ).encode()
            );
            String filter = step.parameters().getOrDefault(
                    ConfigValidator.ITEM_FILTER,
                    detector.itemFilter()
            );
            putIfPresent(parameters, ConfigValidator.ITEM_FILTER, filter);
        } else {
            parameters.put(ConfigValidator.AREA_REF, area);
            putIfPresent(
                    parameters,
                    ConfigValidator.ITEM_FILTER,
                    step.parameters().get(ConfigValidator.ITEM_FILTER)
            );
        }
        putIfPresent(
                parameters,
                ConfigValidator.TIMEOUT_SECONDS,
                step.parameters().get(ConfigValidator.TIMEOUT_SECONDS)
        );
        return action(ConfigValidator.COLLECT_DROPPED_ITEMS, parameters);
    }

    private static List<TaskStep> find(TaskStep step) {
        ResourceReference target = ResourceReference.parse(
                required(step.parameters(), ConfigValidator.TARGET_REF)
        );
        if (target.type() == ResourceType.REGION) {
            Map<String, String> scan = new LinkedHashMap<>();
            scan.put(ConfigValidator.REGION_REF, target.encode());
            copy(
                    step.parameters(),
                    scan,
                    ConfigValidator.CONTAINER_TYPES,
                    ConfigValidator.MAX_VOLUME
            );
            return List.of(action(
                    ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                    scan
            ));
        }
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(ConfigValidator.CATEGORY_ZONE_REF, target.encode());
        copy(step.parameters(), scan, ConfigValidator.MAX_VOLUME);
        TaskStep select = action(
                ConfigValidator.SELECT_MARKER_CONTAINER,
                Map.of()
        );
        TaskStep move = action(
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("marker").encode()
                )
        );
        TaskStep read = action(
                ConfigValidator.READ_CONTAINER,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("marker").encode()
                )
        );
        TaskStep repeat = TaskStep.repeat(
                UUID.randomUUID(),
                condition(TaskCondition.HAS_MARKER_CONTAINERS),
                List.of(select, move, read)
        );
        TaskStep save = action(
                ConfigValidator.SAVE_CATEGORY_MARKER_CACHE,
                Map.of(ConfigValidator.CATEGORY_ZONE_REF, target.encode())
        );
        return List.of(
                action(ConfigValidator.SCAN_CATEGORY_MARKERS, scan),
                repeat,
                save
        );
    }

    private static List<TaskStep> read(TaskStep step) {
        String target = required(step.parameters(), ConfigValidator.TARGET_REF);
        return List.of(
                action(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.TARGET_REF, target)
                ),
                action(
                        ConfigValidator.READ_CONTAINER,
                        Map.of(ConfigValidator.TARGET_REF, target)
                )
        );
    }

    private static List<TaskStep> withdraw(TaskStep step) {
        String target = required(step.parameters(), ConfigValidator.TARGET_REF);
        return List.of(
                action(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.TARGET_REF, target)
                ),
                action(
                        ConfigValidator.WITHDRAW_ITEMS,
                        Map.of(
                                ConfigValidator.TARGET_REF,
                                target,
                                ConfigValidator.ITEM_SOURCE,
                                step.parameters().getOrDefault(
                                        ConfigValidator.ITEM_SOURCE,
                                        "classified"
                                )
                        )
                )
        );
    }

    private static List<TaskStep> deposit(TaskStep step) {
        String target = required(step.parameters(), ConfigValidator.TARGET_REF);
        ResourceReference reference = ResourceReference.parse(target);
        String source = step.parameters().getOrDefault(
                ConfigValidator.ITEM_SOURCE,
                "collected"
        );
        if (reference.type() == ResourceType.COORDINATE
                || reference.type() == ResourceType.OUTPUT) {
            return singleDeposit(target, source);
        }
        if (reference.type() == ResourceType.REGION) {
            return regionDeposit(step, target, source);
        }
        return categoryDeposit(step, target, source);
    }

    private static List<TaskStep> singleDeposit(
            String target,
            String source
    ) {
        return List.of(
                action(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.TARGET_REF, target)
                ),
                depositStep(target, source),
                assertSource(source)
        );
    }

    private static List<TaskStep> regionDeposit(
            TaskStep step,
            String target,
            String source
    ) {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(ConfigValidator.REGION_REF, target);
        copy(
                step.parameters(),
                scan,
                ConfigValidator.CONTAINER_TYPES,
                ConfigValidator.MAX_VOLUME
        );
        String output = ResourceReference.output("container").encode();
        TaskStep depositWhenAvailable = TaskStep.conditional(
                UUID.randomUUID(),
                sourceCondition(source),
                List.of(
                        action(
                                ConfigValidator.MOVE_TO_COORDINATE,
                                Map.of(ConfigValidator.TARGET_REF, output)
                        ),
                        depositStep(output, source)
                ),
                List.of()
        );
        TaskStep repeat = TaskStep.repeat(
                UUID.randomUUID(),
                condition(TaskCondition.HAS_CONTAINERS),
                List.of(
                        action(ConfigValidator.SELECT_CONTAINER, Map.of()),
                        depositWhenAvailable
                )
        );
        return List.of(
                action(ConfigValidator.SCAN_CONTAINERS_IN_REGION, scan),
                repeat,
                assertSource(source)
        );
    }

    private static List<TaskStep> categoryDeposit(
            TaskStep step,
            String target,
            String source
    ) {
        Map<String, String> resolve = new LinkedHashMap<>();
        resolve.put(ConfigValidator.CATEGORY_ZONE_REF, target);
        resolve.put(ConfigValidator.ALLOW_EMPTY_CONTAINER_SCAN, "true");
        copy(
                step.parameters(),
                resolve,
                ConfigValidator.CONTAINER_TYPES,
                ConfigValidator.MAX_VOLUME
        );
        String storage = ResourceReference.output("storage").encode();
        TaskStep repeat = TaskStep.repeat(
                UUID.randomUUID(),
                sourceCondition(source),
                List.of(
                        action(
                                ConfigValidator.SELECT_STORAGE_TARGET,
                                Map.of(ConfigValidator.ITEM_SOURCE, source)
                        ),
                        action(
                                ConfigValidator.MOVE_TO_COORDINATE,
                                Map.of(ConfigValidator.TARGET_REF, storage)
                        ),
                        depositStep(storage, source)
                )
        );
        return List.of(
                action(
                        ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                        resolve
                ),
                repeat,
                assertSource(source)
        );
    }

    private static TaskStep depositStep(String target, String source) {
        return action(
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        target,
                        ConfigValidator.ITEM_SOURCE,
                        source
                )
        );
    }

    private static TaskStep assertSource(String source) {
        return action(
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of(ConfigValidator.ITEM_SOURCE, source)
        );
    }

    private static Map<String, String> sourceCondition(String source) {
        if ("carried".equals(source)) {
            return condition(TaskCondition.HAS_CARRIED_ITEMS);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(
                TaskFlowTemplates.CONDITION,
                TaskCondition.HAS_MANAGED_ITEMS.name()
        );
        if ("collected".equals(source)) {
            parameters.put(ConfigValidator.ITEM_SOURCE, source);
        }
        return Map.copyOf(parameters);
    }

    private static Map<String, String> condition(TaskCondition condition) {
        return Map.of(TaskFlowTemplates.CONDITION, condition.name());
    }

    private static TaskStep action(
            String moduleId,
            Map<String, String> parameters
    ) {
        return TaskStep.action(
                UUID.randomUUID(),
                moduleId,
                Map.copyOf(parameters)
        );
    }

    private static String required(
            Map<String, String> parameters,
            String key
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private static void requireOnly(
            Map<String, String> parameters,
            Set<String> allowed
    ) {
        Set<String> keys = new HashSet<>(parameters.keySet());
        keys.remove(OPERATION);
        if (!allowed.containsAll(keys)) {
            throw new IllegalArgumentException(
                    "CONTAINER_INTERACTION operation has unsupported parameters"
            );
        }
    }

    private static void validateInteger(
            Map<String, String> parameters,
            String key,
            int minimum,
            int maximum
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " is out of range");
        }
    }

    private static void validateFilter(Map<String, String> parameters) {
        String value = parameters.get(ConfigValidator.ITEM_FILTER);
        if (value != null && value.trim().length() > 2_048) {
            throw new IllegalArgumentException("itemFilter is too long");
        }
    }

    private static void copy(
            Map<String, String> source,
            Map<String, String> target,
            String... keys
    ) {
        for (String key : keys) {
            putIfPresent(target, key, source.get(key));
        }
    }

    private static void putIfPresent(
            Map<String, String> target,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    public static final class DetectorNotBoundException
            extends IllegalArgumentException {
        public DetectorNotBoundException(String message) {
            super(message);
        }
    }
}
