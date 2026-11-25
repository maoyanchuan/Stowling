package dev.stowling.v2.runtime.modules;

final class ClassificationSummaryModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    ClassificationSummaryModule(TaskExecutionContext context) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        return TaskModuleResult.succeeded(context.workspace().classificationSummary());
    }

    @Override
    public TaskModuleResult tick() {
        return start();
    }

    @Override
    public void cancel() {
    }
}
