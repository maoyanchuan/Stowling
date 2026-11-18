package dev.stowling.v2.runtime;

import dev.stowling.automation.FoodStatus;
import dev.stowling.v2.model.TaskCondition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleIds;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TaskFlowExecutor {
    private static final int DEFAULT_MAX_ITERATIONS = 1024;
    private static final int OPERATION_BUDGET = 64;

    public interface Host {
        TaskModuleExecutor createAction(TaskStep step);

        void createTask(String taskRef, TaskPriority priority);
    }

    private final TaskExecutionContext context;
    private final Host host;
    private final boolean allowTaskCreation;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final int totalTopLevelSteps;
    private ActionState action;
    private boolean stopRequested;
    private int completedTopLevelSteps;

    public TaskFlowExecutor(
            List<TaskStep> steps,
            TaskExecutionContext context,
            Host host,
            boolean allowTaskCreation
    ) {
        this(steps, context, host, allowTaskCreation, 0);
    }

    public TaskFlowExecutor(List<TaskStep> steps, TaskExecutionContext context, Host host,
            boolean allowTaskCreation, int startStep) {
        this.context = Objects.requireNonNull(context, "context");
        this.host = Objects.requireNonNull(host, "host");
        this.allowTaskCreation = allowTaskCreation;
        List<TaskStep> rootSteps = List.copyOf(
                Objects.requireNonNull(steps, "steps")
        );
        this.totalTopLevelSteps = rootSteps.size();
        if (startStep < 0 || startStep > rootSteps.size()) {
            throw new IllegalArgumentException("invalid resume step");
        }
        Frame root = Frame.root(rootSteps);
        root.index = startStep;
        frames.push(root);
        completedTopLevelSteps = startStep;
    }

    public synchronized TaskModuleResult tick() {
        if (stopRequested) {
            return tickWhileStopping();
        }
        try {
            return tickActiveFlow();
        } catch (RuntimeException exception) {
            return failureFor(exception);
        }
    }

    public synchronized TaskModuleResult tickWhileStopping() {
        stopRequested = true;
        try {
            return tickStoppingAction();
        } catch (RuntimeException exception) {
            return failureFor(exception);
        }
    }

    private TaskModuleResult tickActiveFlow() {
        int operations = 0;
        while (operations++ < OPERATION_BUDGET) {
            if (stopRequested) {
                return tickWhileStopping();
            }
            Frame frame = frames.peek();
            if (frame == null) {
                return TaskModuleResult.succeeded("流程已完成");
            }
            if (frame.isRepeat() && frame.index >= frame.steps.size()) {
                TaskModuleResult repeatResult = continueRepeat(frame);
                if (repeatResult != null) {
                    return repeatResult;
                }
                continue;
            }
            if (frame.index >= frame.steps.size()) {
                frames.pop();
                if (frame.isRoot()) {
                    return TaskModuleResult.succeeded("流程已完成");
                }
                completeControl(frame.parent);
                continue;
            }
            TaskStep step = frame.steps.get(frame.index);
            TaskModuleResult result = switch (step.kind()) {
                case ACTION -> tickAction(frame, step);
                case IF -> enterConditional(frame, step);
                case REPEAT -> enterRepeat(frame, step);
                case CREATE_TASK -> createTask(frame, step);
            };
            if (result != null) {
                return result;
            }
        }
        return TaskModuleResult.running("流程推进中");
    }

    private TaskModuleResult tickStoppingAction() {
        if (action == null) {
            return TaskModuleResult.succeeded("流程已到安全停止点");
        }
        TaskModuleResult result = action.started
                ? action.executor.tick()
                : startAction();
        if (result.state() == TaskModuleState.RUNNING
                || result.state() == TaskModuleState.WAITING) {
            return result;
        }
        ActionState stopping = action;
        action = null;
        return result.state() == TaskModuleState.FAILED
                ? failAt(stopping, result.message())
                : TaskModuleResult.succeeded("当前动作已确认，可安全停止");
    }

    public synchronized void requestStop() {
        stopRequested = true;
        if (action != null) {
            action.executor.requestStop();
        }
    }

    public synchronized boolean safeToStop() {
        return action == null || action.executor.safeToStop();
    }

    public synchronized void cancel() {
        if (action != null) {
            action.executor.cancel();
            action = null;
        }
    }

    public synchronized int completedTopLevelSteps() {
        return completedTopLevelSteps;
    }

    public synchronized int totalTopLevelSteps() {
        return totalTopLevelSteps;
    }

    private TaskModuleResult tickAction(Frame frame, TaskStep step) {
        if (action == null) {
            action = new ActionState(frame, step, host.createAction(step));
        }
        TaskModuleResult result = action.started
                ? action.executor.tick()
                : startAction();
        if (result.state() == TaskModuleState.FAILED) {
            ActionState failed = action;
            action = null;
            return failAt(failed, result.message());
        }
        if (result.state() != TaskModuleState.SUCCEEDED) {
            return result;
        }
        action = null;
        completeStep(frame);
        return null;
    }

    private TaskModuleResult startAction() {
        action.started = true;
        return action.executor.start();
    }

    private TaskModuleResult enterConditional(Frame frame, TaskStep step) {
        boolean matched = evaluate(step);
        List<TaskStep> branch = matched ? step.steps() : step.otherwise();
        if (branch.isEmpty()) {
            completeStep(frame);
            return null;
        }
        frames.push(Frame.branch(frame, step, branch));
        return null;
    }

    private TaskModuleResult enterRepeat(Frame frame, TaskStep step) {
        if (!evaluate(step)) {
            completeStep(frame);
            return null;
        }
        if (step.steps().isEmpty()) {
            return failAt(frame, "REPEAT 的流程体不能为空");
        }
        int maxIterations = integerParameter(
                step,
                TaskModuleIds.MAX_ITERATIONS,
                DEFAULT_MAX_ITERATIONS
        );
        if (maxIterations <= 0) {
            return failAt(frame, "maxIterations 必须为正数");
        }
        frames.push(Frame.repeat(frame, step, maxIterations));
        return null;
    }

    private TaskModuleResult continueRepeat(Frame frame) {
        if (!evaluate(frame.controlStep)) {
            frames.pop();
            completeControl(frame.parent);
            return null;
        }
        if (frame.iterations >= frame.maxIterations) {
            return failAt(
                    frame.location(),
                    "REPEAT 达到最大迭代次数 " + frame.maxIterations
            );
        }
        frame.iterations++;
        frame.index = 0;
        return null;
    }

    private TaskModuleResult createTask(Frame frame, TaskStep step) {
        if (!allowTaskCreation) {
            return failAt(frame, "中断收尾流程不能创建新任务");
        }
        String taskRef = parameter(step, TaskModuleIds.TASK_REF);
        TaskPriority priority = null;
        String priorityValue = parameterOptional(
                step,
                TaskModuleIds.PRIORITY
        );
        if (priorityValue != null) {
            priority = TaskPriority.valueOf(priorityValue.toUpperCase());
        }
        host.createTask(taskRef, priority);
        completeStep(frame);
        return null;
    }

    private void completeStep(Frame frame) {
        frame.index++;
        if (frame.isRoot()) {
            completedTopLevelSteps++;
        }
    }

    private void completeControl(Frame parent) {
        if (parent != null) {
            completeStep(parent);
        }
    }

    private boolean evaluate(TaskStep step) {
        String value = parameter(step, TaskModuleIds.CONDITION);
        TaskCondition condition = TaskCondition.parse(value);
        boolean result = switch (condition) {
            case ALWAYS -> true;
            case FOOD_BELOW_THRESHOLD -> foodBelowThreshold(step);
            case HAS_CONTAINERS -> context.workspace().hasContainers();
            case HAS_CARRIED_ITEMS -> context.workspace().hasCarriedItems();
            case HAS_MANAGED_ITEMS -> hasSourceItems(step, "managed");
            case CACHE_HAS_MORE -> context.workspace().cacheHasMore();
            case HAS_MARKER_CONTAINERS -> context.workspace().hasMarkerContainers();
        };
        boolean negate = Boolean.parseBoolean(parameterOptional(
                step,
                TaskModuleIds.NEGATE
        ));
        return negate ? !result : result;
    }

    private boolean hasSourceItems(TaskStep step, String fallback) {
        String source = parameterOptional(step, TaskModuleIds.ITEM_SOURCE);
        String effective = source == null ? fallback : source;
        return switch (effective) {
            case "collected", "carried", "managed" ->
                    !context.workspace().itemsForSource(effective).isEmpty();
            default -> throw new IllegalArgumentException(
                    "unsupported condition itemSource: " + effective
            );
        };
    }

    private boolean foodBelowThreshold(TaskStep step) {
        FoodStatus food = context.foodConsumption().observe();
        if (!food.connected()) {
            throw new IllegalStateException("无法读取当前饱食度");
        }
        int threshold = integerParameter(
                step,
                TaskModuleIds.FOOD_LEVEL_THRESHOLD,
                -1
        );
        if (threshold < 0) {
            throw new IllegalArgumentException("缺少 foodLevelThreshold");
        }
        return food.foodLevel() < threshold;
    }

    private static String parameter(TaskStep step, String key) {
        String value = parameterOptional(step, key);
        if (value == null) {
            throw new IllegalArgumentException("missing task parameter: " + key);
        }
        return value;
    }

    private static String parameterOptional(TaskStep step, String key) {
        String value = step.parameters().get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int integerParameter(
            TaskStep step,
            String key,
            int fallback
    ) {
        String value = parameterOptional(step, key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private TaskModuleResult failureFor(RuntimeException exception) {
        StepLocation location = action == null
                ? currentLocation()
                : action.frame.location();
        return failAt(location, safeMessage(exception));
    }

    private StepLocation currentLocation() {
        Frame frame = frames.peek();
        return frame == null
                ? new StepLocation("?", "流程")
                : frame.location();
    }

    private static TaskModuleResult failAt(
            ActionState failedAction,
            String message
    ) {
        return failAt(failedAction.frame.location(), message);
    }

    private static TaskModuleResult failAt(Frame frame, String message) {
        return failAt(frame.location(), message);
    }

    private static TaskModuleResult failAt(
            StepLocation location,
            String message
    ) {
        String detail = message == null || message.isBlank()
                ? "未知错误"
                : message;
        return TaskModuleResult.failed(location.describe() + "执行失败: " + detail);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static String stepLabel(TaskStep step) {
        return step.moduleId().isBlank()
                ? step.kind().name()
                : step.moduleId();
    }

    private static final class ActionState {
        private final Frame frame;
        private final TaskStep step;
        private final TaskModuleExecutor executor;
        private boolean started;

        private ActionState(
                Frame frame,
                TaskStep step,
                TaskModuleExecutor executor
        ) {
            this.frame = frame;
            this.step = step;
            this.executor = executor;
        }
    }

    private static final class Frame {
        private final Frame parent;
        private final TaskStep controlStep;
        private final List<TaskStep> steps;
        private final boolean root;
        private final boolean repeat;
        private final int maxIterations;
        private int index;
        private int iterations;

        private Frame(
                Frame parent,
                TaskStep controlStep,
                List<TaskStep> steps,
                boolean root,
                boolean repeat,
                int maxIterations
        ) {
            this.parent = parent;
            this.controlStep = controlStep;
            this.steps = List.copyOf(steps);
            this.root = root;
            this.repeat = repeat;
            this.maxIterations = maxIterations;
        }

        private static Frame root(List<TaskStep> steps) {
            return new Frame(null, null, steps, true, false, 0);
        }

        private static Frame branch(
                Frame parent,
                TaskStep controlStep,
                List<TaskStep> steps
        ) {
            return new Frame(parent, controlStep, steps, false, false, 0);
        }

        private static Frame repeat(
                Frame parent,
                TaskStep controlStep,
                int maxIterations
        ) {
            return new Frame(
                    parent,
                    controlStep,
                    controlStep.steps(),
                    false,
                    true,
                    maxIterations
            );
        }

        private StepLocation location() {
            TaskStep step = index < steps.size()
                    ? steps.get(index)
                    : controlStep;
            return new StepLocation(
                    path(),
                    step == null ? "流程" : stepLabel(step)
            );
        }

        private String path() {
            if (controlStep == null) {
                int position = Math.min(index + 1, Math.max(steps.size(), 1));
                return Integer.toString(Math.max(position, 1));
            }
            String parentPath = parent == null ? "1" : parent.path();
            return index >= steps.size()
                    ? parentPath
                    : parentPath + "." + (index + 1);
        }

        private boolean isRoot() {
            return root;
        }

        private boolean isRepeat() {
            return repeat;
        }
    }

    private record StepLocation(String path, String label) {
        private String describe() {
            return "第 " + path + " 步（" + label + "）";
        }
    }
}
