package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {
    @Test
    void trimsCoordinateNamesAndRejectsInvalidLengths() {
        CoordinatePoint point = new CoordinatePoint(
                UUID.randomUUID(),
                " Home ",
                0,
                64,
                0
        );

        assertEquals("Home", point.name());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(UUID.randomUUID(), "  ", 0, 64, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "x".repeat(81),
                        0,
                        64,
                        0
                )
        );
    }

    @Test
    void validatesOptionalDimensionAndRadius() {
        CoordinatePoint point = new CoordinatePoint(
                UUID.randomUUID(),
                "Home",
                0,
                64,
                0,
                " minecraft:overworld ",
                5
        );

        assertEquals("minecraft:overworld", point.dimension());
        assertEquals(5, point.radius());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "Home",
                        0,
                        64,
                        0,
                        "overworld",
                        5
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "Home",
                        0,
                        64,
                        0,
                        "minecraft:overworld",
                        129
                )
        );
    }

    @Test
    void rejectsCoordinateNamesThatDifferOnlyByCaseAndWhitespace() {
        AppConfig config = config(
                List.of(
                        new CoordinatePoint(UUID.randomUUID(), "Home", 0, 64, 0),
                        new CoordinatePoint(UUID.randomUUID(), " home ", 1, 64, 1)
                ),
                List.of()
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );
    }

    @Test
    void rejectsTaskNamesThatDifferOnlyByCaseAndWhitespace() {
        CoordinatePoint point = point("Home");
        TaskDefinition first = task("Travel", step(point.id()));
        TaskDefinition second = task(" travel ", step(point.id()));

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(first, second)
                ))
        );
    }

    @Test
    void rejectsRegionNamesThatDifferOnlyByCaseAndWhitespace() {
        RegionDefinition first = region("Farm");
        RegionDefinition second = region(" farm ");

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(),
                        List.of(),
                        List.of(first, second)
                ))
        );
    }

    @Test
    void requiresAtLeastOneTaskStep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskDefinition(UUID.randomUUID(), "Travel", List.of())
        );
    }

    @Test
    void rejectsUnknownTaskModule() {
        CoordinatePoint point = point("Home");
        TaskStep step = new TaskStep(
                UUID.randomUUID(),
                "UNKNOWN_MODULE",
                Map.of(ConfigValidator.COORDINATE_ID, point.id().toString())
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void rejectsUnexpectedModuleParameter() {
        CoordinatePoint point = point("Home");
        TaskStep step = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString(),
                        "radius",
                        "3"
                )
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void rejectsMissingCoordinateReference() {
        TaskStep step = step(UUID.randomUUID());

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void acceptsAValidMoveToCoordinateTask() {
        CoordinatePoint point = point("Home");

        ConfigValidator.requireValid(config(
                List.of(point),
                List.of(task("Travel", step(point.id())))
        ));
    }

    @Test
    void acceptsWaitCollectScanAndDepositChain() {
        CoordinatePoint point = point("Drop");
        RegionDefinition region = region("Storage");
        TaskDefinition task = new TaskDefinition(
                UUID.randomUUID(),
                "Collect and store",
                List.of(
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString(),
                                        ConfigValidator.TIMEOUT_SECONDS,
                                        "60"
                                )
                        ),
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.COLLECT_DROPPED_ITEMS,
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString()
                                )
                        ),
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                                Map.of(
                                        ConfigValidator.REGION_ID,
                                        region.id().toString()
                                )
                        ),
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                                Map.of(
                                        ConfigValidator.SCAN_SOURCE,
                                        "previous-scan"
                                )
                        ),
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                                Map.of(
                                        ConfigValidator.SCAN_SOURCE,
                                        "previous-scan",
                                        ConfigValidator.ITEM_SOURCE,
                                        "collected-this-run",
                                        ConfigValidator.FILL_MODE,
                                        "nearest-first"
                                )
                        )
                )
        );

        ConfigValidator.requireValid(new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(point),
                List.of(task),
                List.of(region)
        ));
    }

    @Test
    void validatesContainerCategoryModuleContract() {
        TaskStep valid = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                Map.of(ConfigValidator.SCAN_SOURCE, "previous-scan")
        );
        TaskStep invalid = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                Map.of(ConfigValidator.SCAN_SOURCE, "rescan")
        );

        ConfigValidator.requireValidStep(valid, Map.of(), Map.of());
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValidStep(
                        invalid,
                        Map.of(),
                        Map.of()
                )
        );
    }

    @Test
    void validatesRegionCollectionAndCacheSortContracts() {
        RegionDefinition source = region("Source");
        RegionDefinition cache = region("Cache");
        TaskStep collect = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION,
                Map.of(ConfigValidator.REGION_ID, source.id().toString())
        );
        TaskStep sort = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SORT_CACHE_ITEMS,
                Map.of(
                        ConfigValidator.CACHE_REGION_ID,
                        cache.id().toString(),
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan"
                )
        );
        Map<UUID, RegionDefinition> regions = Map.of(
                source.id(),
                source,
                cache.id(),
                cache
        );

        ConfigValidator.requireValidStep(collect, Map.of(), regions);
        ConfigValidator.requireValidStep(sort, Map.of(), regions);
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValidStep(
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator.SORT_CACHE_ITEMS,
                                Map.of(
                                        ConfigValidator.CACHE_REGION_ID,
                                        UUID.randomUUID().toString(),
                                        ConfigValidator.SCAN_SOURCE,
                                        "previous-scan"
                                )
                        ),
                        Map.of(),
                        regions
                )
        );
    }

    @Test
    void validatesCategoryZoneSortContracts() {
        RegionDefinition cache = region("Cache");
        CategoryZoneDefinition zone = categoryZone("A");
        TaskStep step = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE,
                Map.of(
                        ConfigValidator.CACHE_REGION_ID,
                        cache.id().toString(),
                        ConfigValidator.CATEGORY_ZONE_ID,
                        zone.id().toString()
                )
        );

        ConfigValidator.requireValidStep(
                step,
                Map.of(),
                Map.of(cache.id(), cache),
                Map.of(zone.id(), zone)
        );
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValidStep(
                        step,
                        Map.of(),
                        Map.of(cache.id(), cache),
                        Map.of()
                )
        );
    }

    @Test
    void rejectsUnknownRegionReferenceAndInvalidIntegerParameter() {
        RegionDefinition region = region("Storage");
        TaskStep missingRegion = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                Map.of(ConfigValidator.REGION_ID, UUID.randomUUID().toString())
        );
        TaskStep badVolume = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                Map.of(
                        ConfigValidator.REGION_ID,
                        region.id().toString(),
                        ConfigValidator.MAX_VOLUME,
                        "0"
                )
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(),
                        List.of(task("Missing", missingRegion)),
                        List.of(region)
                ))
        );
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(),
                        List.of(task("Bad volume", badVolume)),
                        List.of(region)
                ))
        );
    }

    @Test
    void rejectsContainerOverridesOutsideTheStorageArea() {
        CategoryBindingDefinition binding = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(7, -34, 7, 18, -34, 7),
                new AreaBounds(7, -34, 5, 18, -34, 6),
                "DIRECT_BLOCK_ID",
                List.of(),
                List.of(new CategoryContainerOverrideDefinition(
                        UUID.randomUUID(),
                        new BlockPosition(8, -34, 1),
                        CategoryContainerOverrideMode.ADD,
                        List.of("minecraft:oak_planks")
                ))
        );
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                List.of(),
                List.of(new CategoryZoneDefinition(
                        UUID.randomUUID(),
                        "A",
                        List.of(binding)
                ))
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );
    }

    private static AppConfig config(
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                tasks
        );
    }

    private static CoordinatePoint point(String name) {
        return new CoordinatePoint(UUID.randomUUID(), name, 0, 64, 0);
    }

    private static RegionDefinition region(String name) {
        return new RegionDefinition(
                UUID.randomUUID(),
                name,
                0,
                64,
                0,
                10,
                70,
                10
        );
    }

    private static TaskDefinition task(String name, TaskStep step) {
        return new TaskDefinition(UUID.randomUUID(), name, List.of(step));
    }

    private static TaskStep step(UUID coordinateId) {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.COORDINATE_ID, coordinateId.toString())
        );
    }

    private static CategoryZoneDefinition categoryZone(String name) {
        return new CategoryZoneDefinition(
                UUID.randomUUID(),
                name,
                List.of(new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(7, -34, 7, 18, -34, 7),
                        new AreaBounds(7, -34, 5, 18, -34, 6),
                        "DIRECT_BLOCK_ID"
                ))
        );
    }
}
