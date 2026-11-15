package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CollectDroppedItemsModule implements TaskModuleExecutor {
    private final DroppedItemCollectionEngine engine;

    CollectDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
        int timeoutSeconds = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.TIMEOUT_SECONDS,
                TaskModuleSupport.DEFAULT_COLLECT_TIMEOUT_SECONDS
        );
        Set<String> itemFilter = TaskModuleSupport.itemFilter(step);
        String area = step.parameters().get(ConfigValidator.AREA_REFERENCE);
        this.engine = area == null
                ? coordinateEngine(
                        context,
                        TaskModuleSupport.coordinate(context, step),
                        timeoutSeconds,
                        itemFilter
                )
                : areaEngine(
                        context,
                        area,
                        timeoutSeconds,
                        itemFilter
                );
    }

    @Override
    public TaskModuleResult start() {
        return engine.start();
    }

    @Override
    public TaskModuleResult tick() {
        return engine.tick();
    }

    @Override
    public void cancel() {
        engine.cancel();
    }

    private static DroppedItemCollectionEngine coordinateEngine(
            TaskExecutionContext context,
            CoordinatePoint coordinate,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        BlockPosition center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        int radius = TaskModuleSupport.dropRadius(coordinate);
        return new DroppedItemCollectionEngine(
                context,
                new DroppedItemCollectionEngine.Source() {
                    @Override
                    public boolean dimensionMatches() {
                        return TaskModuleSupport.dimensionMatches(
                                context,
                                coordinate
                        );
                    }

                    @Override
                    public List<DroppedItem> observe() {
                        return context.droppedItems().observe(center, radius);
                    }
                },
                timeoutSeconds,
                itemFilter
        );
    }

    private static DroppedItemCollectionEngine areaEngine(
            TaskExecutionContext context,
            String area,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        int separator = area.indexOf(
                ConfigValidator.AREA_REFERENCE_SEPARATOR
        );
        if (separator <= 0 || separator == area.length() - 1) {
            throw new IllegalArgumentException("invalid area reference");
        }
        String type = area.substring(0, separator);
        UUID id = UUID.fromString(area.substring(separator + 1));
        if ("coordinate".equals(type)) {
            CoordinatePoint coordinate = context.coordinates().get(id);
            if (coordinate == null) {
                throw new IllegalArgumentException(
                        "coordinate does not exist: " + id
                );
            }
            return coordinateEngine(
                    context,
                    coordinate,
                    timeoutSeconds,
                    itemFilter
            );
        }
        if ("region".equals(type)) {
            RegionDefinition region = context.regions().get(id);
            if (region == null) {
                throw new IllegalArgumentException(
                        "region does not exist: " + id
                );
            }
            return new DroppedItemCollectionEngine(
                    context,
                    new DroppedItemCollectionEngine.Source() {
                        @Override
                        public boolean dimensionMatches() {
                            return true;
                        }

                        @Override
                        public List<DroppedItem> observe() {
                            return context.droppedItems().observe(region);
                        }
                    },
                    timeoutSeconds,
                    itemFilter
            );
        }
        throw new IllegalArgumentException(
                "area reference must use coordinate or region"
        );
    }
}
