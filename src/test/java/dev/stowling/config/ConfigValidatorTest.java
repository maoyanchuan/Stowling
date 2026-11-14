package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.TriggerKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsEqualPriorityRouteAmbiguity() {
        StorageRoute first = ConfigTestFixtures.route(
                "first",
                List.of("minecraft:cobblestone"),
                10
        );
        StorageRoute second = ConfigTestFixtures.route(
                "second",
                List.of("minecraft:cobblestone"),
                10
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(first, second));

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );

        assertTrue(exception.getMessage().contains("ambiguous selector"));
    }

    @Test
    void allowsPriorityToResolveRouteOverlap() {
        StorageRoute fallback = ConfigTestFixtures.route(
                "fallback",
                List.of("minecraft:cobblestone"),
                1
        );
        StorageRoute specific = ConfigTestFixtures.route(
                "specific",
                List.of("minecraft:cobblestone"),
                10
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(fallback, specific));

        assertDoesNotThrow(
                () -> ConfigValidator.requireValid(config)
        );
    }

    @Test
    void rejectsUnknownPackingStation() {
        ContainerRef bulk = ConfigTestFixtures.container("bulk", 20);
        StorageRoute route = new StorageRoute(
                "pack-route",
                List.of("minecraft:diamond"),
                List.of(),
                10,
                bulk,
                64,
                "missing-station"
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(ConfigTestFixtures.config(List.of(route)))
        );

        assertTrue(exception.getMessage().contains("packing station does not exist"));
    }

    @Test
    void acceptsStationWhoseProductContainerMatchesDimension() {
        ContainerRef product = ConfigTestFixtures.container("product", 30);
        PackingStation station = new PackingStation(
                "station",
                "minecraft:overworld",
                new BlockPosition(12, 64, 0),
                product,
                PackingTrigger.ON_ITEM_COUNT,
                64
        );
        StorageRoute route = new StorageRoute(
                "pack-route",
                List.of("minecraft:diamond"),
                List.of(),
                10,
                ConfigTestFixtures.container("bulk", 20),
                64,
                station.id()
        );
        StowlingConfig base = ConfigTestFixtures.config(List.of(route));
        StowlingConfig config = new StowlingConfig(
                base.schemaVersion(),
                base.defaultIdleDelay(),
                base.pickupSites(),
                base.storageRoutes(),
                List.of(station),
                base.auditSchedules(),
                base.overflowContainer()
        );

        List<ConfigViolation> violations = ConfigValidator.validate(config);

        assertEquals(List.of(), violations);
    }

    @Test
    void allowsCachesToShareNamedCoordinates() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache first = cache("cache-one", "minecraft:overworld", 8);
        NamedCache duplicate = cache("cache-two", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache-one")
        );
        StowlingConfig valid = config(
                List.of(region),
                List.of(first, duplicate),
                List.of(workflow)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
        assertEquals(
                valid.cacheContainers().getFirst().coordinateId(),
                valid.cacheContainers().getLast().coordinateId()
        );
    }

    @Test
    void rejectsCrossDimensionWorkflowReferences() {
        PickupRegion region = region("nether-farm", "minecraft:the_nether");
        NamedCache cache = cache("overworld-cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "mixed-patrol",
                "minecraft:overworld",
                List.of("nether-farm"),
                List.of("overworld-cache")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("workflow dimension"));
    }

    @Test
    void rejectsDuplicateIdsAcrossPickupResources() {
        PickupRegion region = region("shared-id", "minecraft:overworld");
        NamedCache cache = cache("shared-id", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("shared-id"),
                List.of("shared-id")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("duplicate id"));
    }

    @Test
    void rejectsEmptyWorkflowAndWorkflowWithoutCaches() {
        PickupWorkflow workflow = workflow(
                "empty-patrol",
                "minecraft:overworld",
                List.of(),
                List.of()
        );
        StowlingConfig invalid = config(List.of(), List.of(), List.of(workflow));

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("workflow is empty"));
        assertTrue(exception.getMessage().contains("at least one cache"));
    }

    @Test
    void rejectsUnknownAndUnsafeReferences() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("missing/region"),
                List.of("missing-cache")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("pickup region does not exist"));
        assertTrue(exception.getMessage().contains("cache does not exist"));
        assertTrue(exception.getMessage().contains("id must match"));
    }

    @Test
    void allowsIndependentStandbyPositionOutsideRegion() {
        PickupRegion region = new PickupRegion(
                "farm",
                "Farm",
                "minecraft:overworld",
                new BlockPosition(20, 64, 0),
                new SpherePickupShape(new BlockPosition(0, 64, 0), 8)
        );
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache")
        );
        StowlingConfig valid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
    }

    @Test
    void rejectsInvalidAutomationReferencesAndParameters() {
        AutomationStep navigate = new AutomationStep(
                "navigate",
                AutomationStepType.NAVIGATE,
                Map.of("coordinateId", "missing-coordinate")
        );
        AutomationStep take = new AutomationStep(
                "take",
                AutomationStepType.TAKE_ITEMS,
                Map.of(
                "containerId", "missing-container",
                "itemId", "minecraft:stone",
                "count", "0"
        )
        );
        AutomationStep pickup = new AutomationStep(
                "pickup",
                AutomationStepType.RUN_PICKUP,
                Map.of(
                "workflowId", "missing-pickup",
                "regionId", "missing-region",
                "unexpected", "value"
        )
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                "invalid-automation",
                "Invalid automation",
                List.of(navigate, take, pickup)
        );
        StowlingConfig invalid = ConfigTestFixtures.configWithAutomation(
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("coordinate does not exist"));
        assertTrue(exception.getMessage().contains("container does not exist"));
        assertTrue(exception.getMessage().contains("pickup workflow does not exist"));
        assertTrue(exception.getMessage().contains("positive integer"));
        assertTrue(exception.getMessage().contains("unknown parameter"));
    }

    @Test
    void acceptsRunPickupWaitForItemsAndValidatesAfterRoundTrip() {
        StowlingConfig config = runPickupAutomationConfig(
                Map.of("waitForItems", "true")
        );
        ConfigLoader loader = new ConfigLoader();
        Path output = tempDir.resolve("run-pickup-round-trip.json");

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
        loader.write(output, config);
        StowlingConfig loaded = loader.load(output);

        assertEquals(config, loaded);
        assertDoesNotThrow(() -> ConfigValidator.requireValid(loaded));
    }

    @Test
    void rejectsInvalidRunPickupWaitForItemsBoolean() {
        StowlingConfig config = runPickupAutomationConfig(
                Map.of("waitForItems", "TRUE")
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );

        assertTrue(exception.getMessage().contains("waitForItems"));
        assertTrue(exception.getMessage().contains("true or false"));
    }

    @Test
    void roundTripsIndependentPickupRulesWithoutWorkflowOrSharedRegion() {
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of(
                "idleSeconds", "0",
                "classificationTiming", "AFTER_REGION",
                "waitForItems", "true"
        )), false);
        ConfigLoader loader = new ConfigLoader();
        Path output = tempDir.resolve("independent-pickup.json");

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
        loader.write(output, config);
        StowlingConfig loaded = loader.load(output);

        assertEquals(config, loaded);
        assertEquals(StowlingConfig.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertTrue(loaded.pickupWorkflows().isEmpty());
        assertTrue(loaded.pickupRegions().isEmpty());
        Map<String, String> parameters = loaded.automationWorkflows()
                .getFirst().steps().getFirst().parameters();
        assertFalse(parameters.containsKey("workflowId"));
        assertFalse(parameters.containsKey("regionId"));
        assertEquals("cache", parameters.get("cacheIds"));
        assertEquals("true", parameters.get("waitForItems"));
        assertDoesNotThrow(() -> ConfigValidator.requireValid(loaded));
    }

    @Test
    void rejectsEveryMissingRequiredIndependentPickupParameter() {
        for (String key : List.of(
                "standbyCoordinateId", "firstCornerCoordinateId",
                "secondCornerCoordinateId", "cacheIds"
        )) {
            Map<String, String> parameters = customPickupParameters(Map.of());
            parameters.remove(key);

            ConfigValidationException exception = assertThrows(
                    ConfigValidationException.class,
                    () -> ConfigValidator.requireValid(customPickupConfig(parameters, false))
            );

            assertTrue(exception.getMessage().contains(key));
        }
    }

    @Test
    void rejectsMixedLegacyAndInvalidIndependentPickupParameters() {
        List<Map<String, String>> invalid = List.of(
                Map.of("workflowId", "legacy-pickup"),
                Map.of("regionId", "legacy-region"),
                Map.of("idleSeconds", "-1"),
                Map.of("idleSeconds", "1.5"),
                Map.of("cacheIds", "cache,cache"),
                Map.of("cacheIds", "cache,"),
                Map.of("classificationTiming", "INVALID"),
                Map.of("waitForItems", "TRUE")
        );
        for (Map<String, String> overrides : invalid) {
            StowlingConfig config = customPickupConfig(customPickupParameters(overrides), false);

            assertThrows(
                    ConfigValidationException.class, () -> ConfigValidator.requireValid(config)
            );
        }
    }

    @Test
    void rejectsUnknownCoordinatesAndNonCacheContainersForIndependentPickup() {
        for (String key : List.of(
                "standbyCoordinateId", "firstCornerCoordinateId", "secondCornerCoordinateId"
        )) {
            StowlingConfig config = customPickupConfig(
                    customPickupParameters(Map.of(key, "missing-coordinate")), false
            );
            assertCustomPickupViolation(config, key, false);
        }
        for (String id : List.of("missing-cache", "overflow", "bulk-storage")) {
            StowlingConfig config = customPickupConfig(
                    customPickupParameters(Map.of("cacheIds", id)), false
            );
            assertCustomPickupViolation(config, "cacheIds", false);
        }
    }

    @Test
    void rejectsDeletingCoordinatesOrCachesReferencedOnlyByIndependentPickup() {
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of()), false);
        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
        Map<String, String> references = Map.of(
                "standbyCoordinateId", "standby",
                "firstCornerCoordinateId", "corner-one",
                "secondCornerCoordinateId", "corner-two"
        );
        for (Map.Entry<String, String> reference : references.entrySet()) {
            List<NamedCoordinate> remaining = config.coordinatePoints().stream()
                    .filter(coordinate -> !coordinate.id().equals(reference.getValue()))
                    .toList();
            StowlingConfig deleted = withCustomResources(
                    config, remaining, config.cacheContainers()
            );
            assertCustomPickupViolation(deleted, reference.getKey(), false);
        }
        StowlingConfig deletedCache = withCustomResources(
                config, config.coordinatePoints(), List.of()
        );
        assertCustomPickupViolation(deletedCache, "cacheIds", false);
    }

    @Test
    void rejectsIndependentPickupCrossDimensionCornersAndCaches() {
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of()), false);
        Map<String, String> references = Map.of(
                "firstCornerCoordinateId", "corner-one",
                "secondCornerCoordinateId", "corner-two",
                "cacheIds", "cache-point"
        );
        for (Map.Entry<String, String> reference : references.entrySet()) {
            List<NamedCoordinate> coordinates = config.coordinatePoints().stream()
                    .map(coordinate -> coordinate.id().equals(reference.getValue())
                            ? new NamedCoordinate(coordinate.id(), coordinate.name(),
                                    "minecraft:the_nether", coordinate.position())
                            : coordinate)
                    .toList();
            List<NamedCache> caches = config.cacheContainers();
            if (reference.getKey().equals("cacheIds")) {
                NamedCache cache = caches.getFirst();
                caches = List.of(new NamedCache(
                        cache.id(), cache.name(), cache.coordinateId(), "minecraft:the_nether",
                        cache.position(), cache.type()
                ));
            }
            StowlingConfig crossDimension = withCustomResources(config, coordinates, caches);
            assertCustomPickupViolation(crossDimension, reference.getKey(), false);
        }
    }

    @Test
    void validatesAndRoundTripsIndependentPickupInInlineInterruption() {
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of()), true);
        ConfigLoader loader = new ConfigLoader();
        Path output = tempDir.resolve("inline-independent-pickup.json");

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
        loader.write(output, config);
        assertEquals(config, loader.load(output));
    }

    @Test
    void rejectsDeletedAndNonCacheReferencesInInlineIndependentPickup() {
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of()), true);
        List<NamedCoordinate> remaining = config.coordinatePoints().stream()
                .filter(coordinate -> !coordinate.id().equals("corner-two"))
                .toList();
        assertCustomPickupViolation(
                withCustomResources(config, remaining, config.cacheContainers()),
                "secondCornerCoordinateId", true
        );
        assertCustomPickupViolation(
                withCustomResources(config, config.coordinatePoints(), List.of()), "cacheIds", true
        );
        assertCustomPickupViolation(customPickupConfig(
                customPickupParameters(Map.of("cacheIds", "bulk-storage")), true
        ), "cacheIds", true);
    }

    @Test
    void rejectsMixedIncompleteAndCrossDimensionInlineIndependentPickup() {
        for (Map<String, String> parameters : List.of(
                customPickupParameters(Map.of("workflowId", "legacy")),
                customPickupParameters(Map.of("regionId", "legacy-region")),
                Map.of("standbyCoordinateId", "standby")
        )) {
            StowlingConfig config = customPickupConfig(parameters, true);
            assertThrows(
                    ConfigValidationException.class, () -> ConfigValidator.requireValid(config)
            );
        }
        StowlingConfig config = customPickupConfig(customPickupParameters(Map.of()), true);
        List<NamedCoordinate> coordinates = config.coordinatePoints().stream()
                .map(coordinate -> coordinate.id().equals("corner-two")
                        ? new NamedCoordinate(coordinate.id(), coordinate.name(),
                                "minecraft:the_nether", coordinate.position())
                        : coordinate)
                .toList();
        assertCustomPickupViolation(
                withCustomResources(config, coordinates, config.cacheContainers()),
                "secondCornerCoordinateId", true
        );
    }

    @Test
    void preservesLegacyPickupWorkflowAndRegionValidation() {
        assertDoesNotThrow(() -> ConfigValidator.requireValid(runPickupAutomationConfig(Map.of())));
        assertDoesNotThrow(() -> ConfigValidator.requireValid(runPickupAutomationConfig(
                Map.of("waitForItems", "false")
        )));
        AutomationStep missingWorkflow = new AutomationStep(
                "pickup", AutomationStepType.RUN_PICKUP, Map.of("waitForItems", "true")
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                "legacy", "Legacy", List.of(missingWorkflow)
        );
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.requireValid(
                ConfigTestFixtures.configWithAutomation(List.of(workflow))
        ));
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        AutomationStep missingRegion = new AutomationStep(
                "pickup", AutomationStepType.RUN_PICKUP, Map.of(
                        "workflowId", base.pickupWorkflows().getFirst().id(),
                        "regionId", "missing-region"
                )
        );
        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.requireValid(ConfigTestFixtures.configWithAutomation(List.of(
                        new AutomationWorkflow("legacy", "Legacy", List.of(missingRegion))
                )))
        );
        assertTrue(exception.getMessage().contains("does not contain region"));
    }

    @Test
    void rejectsDuplicateAutomationStepIds() {
        AutomationStep first = new AutomationStep(
                "duplicate",
                AutomationStepType.WAIT,
                Map.of("seconds", "1")
        );
        AutomationStep second = new AutomationStep(
                "duplicate",
                AutomationStepType.CHECK_INVENTORY,
                Map.of("itemId", "minecraft:stone", "count", "1")
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                "duplicate-steps",
                "Duplicate steps",
                List.of(first, second)
        );
        StowlingConfig invalid = ConfigTestFixtures.configWithAutomation(
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("duplicate id"));
    }

    @Test
    void rejectsInvalidTriggerParametersAndRegionReferences() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow base = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache")
        );
        TaskSettings settings = new TaskSettings(
                TaskPriority.HIGH,
                new TaskTrigger(
                        TriggerKind.DAILY,
                        10L,
                        List.of("25:00"),
                        "Mars/Base",
                        List.of("missing-region")
                ),
                false,
                TaskInterruption.none()
        );
        PickupWorkflow configured = new PickupWorkflow(
                base.id(),
                base.name(),
                base.dimension(),
                base.regions(),
                base.cacheIds(),
                base.cachePolicy(),
                base.classificationTiming(),
                base.completionMode(),
                base.completionIdleDelay(),
                settings
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(configured)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("interval is only valid"));
        assertTrue(exception.getMessage().contains("time must use HH:mm"));
        assertTrue(exception.getMessage().contains("unknown time zone"));
        assertTrue(exception.getMessage().contains("pickup region does not exist"));
    }

    @Test
    void allowsPickupItemsPresentTriggerToUseItsOwnRegions() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow base = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache")
        );
        PickupWorkflow configured = withPickupSettings(
                base,
                taskSettings(new TaskTrigger(
                        TriggerKind.ITEMS_PRESENT,
                        0L,
                        List.of(),
                        "Asia/Shanghai",
                        List.of()
                ))
        );
        StowlingConfig valid = config(
                List.of(region),
                List.of(cache),
                List.of(configured)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
    }

    @Test
    void rejectsAutomationItemsPresentTriggerWithoutRegions() {
        AutomationWorkflow workflow = new AutomationWorkflow(
                "items-task",
                "Items task",
                List.of(waitStep("wait")),
                taskSettings(new TaskTrigger(
                        TriggerKind.ITEMS_PRESENT,
                        0L,
                        List.of(),
                        "Asia/Shanghai",
                        List.of()
                ))
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(
                        ConfigTestFixtures.configWithAutomation(List.of(workflow))
                )
        );

        assertTrue(exception.getMessage().contains("requires at least one region"));
    }

    @Test
    void requiresPositiveAfterCompletionInterval() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow base = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(withPickupSettings(
                        base,
                        taskSettings(new TaskTrigger(
                                TriggerKind.AFTER_COMPLETION,
                                0L,
                                List.of(),
                                "Asia/Shanghai",
                                List.of()
                        ))
                ))
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains(
                "after-completion interval must be positive"
        ));
        StowlingConfig valid = config(
                List.of(region),
                List.of(cache),
                List.of(withPickupSettings(
                        base,
                        taskSettings(new TaskTrigger(
                                TriggerKind.AFTER_COMPLETION,
                                30L,
                                List.of(),
                                "Asia/Shanghai",
                                List.of()
                        ))
                ))
        );
        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
    }

    @Test
    void rejectsInterruptionReferenceToNonAutomationTask() {
        String pickupId = ConfigTestFixtures.config(List.of())
                .pickupWorkflows()
                .getFirst()
                .id();
        TaskSettings settings = settingsWithReference(pickupId);
        AutomationWorkflow workflow = new AutomationWorkflow(
                "reference-task",
                "Reference task",
                List.of(waitStep("wait")),
                settings
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(
                        ConfigTestFixtures.configWithAutomation(List.of(workflow))
                )
        );

        assertTrue(exception.getMessage().contains("not an automation task"));
    }

    @Test
    void rejectsInterruptionReferenceCycles() {
        AutomationWorkflow first = new AutomationWorkflow(
                "first",
                "First",
                List.of(waitStep("wait-first")),
                settingsWithReference("second")
        );
        AutomationWorkflow second = new AutomationWorkflow(
                "second",
                "Second",
                List.of(waitStep("wait-second")),
                settingsWithReference("first")
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(
                        ConfigTestFixtures.configWithAutomation(List.of(first, second))
                )
        );

        assertTrue(exception.getMessage().contains("creates a cycle"));
    }

    @Test
    void validatesInlineInterruptionSteps() {
        AutomationStep invalid = new AutomationStep(
                "take",
                AutomationStepType.TAKE_ITEMS,
                Map.of(
                        "containerId", "missing-container",
                        "itemId", "minecraft:stone",
                        "count", "1"
                )
        );
        TaskSettings settings = new TaskSettings(
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                false,
                new TaskInterruption(InterruptionKind.INLINE, List.of(invalid), null)
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                "inline-task",
                "Inline task",
                List.of(waitStep("wait")),
                settings
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(
                        ConfigTestFixtures.configWithAutomation(List.of(workflow))
                )
        );

        assertTrue(exception.getMessage().contains("container does not exist"));
    }

    private static PickupRegion region(String id, String dimension) {
        BlockPosition center = new BlockPosition(0, 64, 0);
        return new PickupRegion(
                id,
                id,
                dimension,
                center,
                new SpherePickupShape(center, 16)
        );
    }

    private static NamedCache cache(String id, String dimension, int x) {
        return new NamedCache(
                id,
                id,
                dimension,
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static PickupWorkflow workflow(
            String id,
            String dimension,
            List<String> regionIds,
            List<String> cacheIds
    ) {
        List<PickupRegionBinding> bindings = regionIds.stream()
                .map(ConfigValidatorTest::binding)
                .toList();
        return new PickupWorkflow(
                id,
                id,
                dimension,
                bindings,
                cacheIds,
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ofSeconds(300)
        );
    }

    private static TaskSettings settingsWithReference(String taskId) {
        return new TaskSettings(
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                false,
                new TaskInterruption(
                        InterruptionKind.TASK_REFERENCE,
                        List.of(),
                        taskId
                )
        );
    }

    private static TaskSettings taskSettings(TaskTrigger trigger) {
        return new TaskSettings(
                TaskPriority.MEDIUM,
                trigger,
                false,
                TaskInterruption.none()
        );
    }

    private static StowlingConfig runPickupAutomationConfig(
            Map<String, String> parameters
    ) {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        String pickupId = base.pickupWorkflows().getFirst().id();
        String regionId = base.pickupWorkflows()
                .getFirst()
                .regions()
                .getFirst()
                .regionId();
        Map<String, String> completeParameters = new HashMap<>(parameters);
        completeParameters.put("workflowId", pickupId);
        completeParameters.put("regionId", regionId);
        AutomationStep pickup = new AutomationStep(
                "pickup",
                AutomationStepType.RUN_PICKUP,
                completeParameters
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                "pickup-wait",
                "Pickup wait",
                List.of(pickup)
        );
        return ConfigTestFixtures.configWithAutomation(List.of(workflow));
    }

    private static PickupWorkflow withPickupSettings(
            PickupWorkflow workflow,
            TaskSettings settings
    ) {
        return new PickupWorkflow(
                workflow.id(),
                workflow.name(),
                workflow.dimension(),
                workflow.regions(),
                workflow.cacheIds(),
                workflow.cachePolicy(),
                workflow.classificationTiming(),
                workflow.completionMode(),
                workflow.completionIdleDelay(),
                settings
        );
    }

    private static Map<String, String> customPickupParameters(Map<String, String> overrides) {
        Map<String, String> parameters = new HashMap<>(Map.of(
                "standbyCoordinateId", "standby",
                "firstCornerCoordinateId", "corner-one",
                "secondCornerCoordinateId", "corner-two",
                "cacheIds", "cache"
        ));
        parameters.putAll(overrides);
        return parameters;
    }

    private static StowlingConfig customPickupConfig(
            Map<String, String> parameters, boolean inline
    ) {
        String dimension = "minecraft:overworld";
        List<NamedCoordinate> coordinates = List.of(
                new NamedCoordinate("standby", "Standby", dimension,
                        new BlockPosition(20, 64, 0)),
                new NamedCoordinate("corner-one", "Corner one", dimension,
                        new BlockPosition(8, 70, 4)),
                new NamedCoordinate("corner-two", "Corner two", dimension,
                        new BlockPosition(-8, 60, -4)),
                new NamedCoordinate("cache-point", "Cache point", dimension,
                        new BlockPosition(8, 64, 0))
        );
        NamedCache cache = new NamedCache(
                "cache", "Cache", "cache-point", dimension, new BlockPosition(8, 64, 0),
                ContainerType.CHEST
        );
        AutomationStep pickup = new AutomationStep("pickup", AutomationStepType.RUN_PICKUP,
                parameters);
        TaskSettings settings = inline
                ? new TaskSettings(TaskPriority.MEDIUM, new TaskTrigger(), false,
                        new TaskInterruption(InterruptionKind.INLINE, List.of(pickup), null))
                : taskSettings(new TaskTrigger());
        AutomationWorkflow workflow = new AutomationWorkflow(
                "independent-pickup", "Independent pickup",
                inline ? List.of(waitStep("wait")) : List.of(pickup), settings
        );
        return new StowlingConfig(
                5, coordinates, List.of(), List.of(cache), List.of(), List.of(workflow),
                List.of(ConfigTestFixtures.route("storage", List.of("minecraft:stone"), 1)),
                List.of(), List.of(), ConfigTestFixtures.container("overflow", 24)
        );
    }

    private static StowlingConfig withCustomResources(
            StowlingConfig config, List<NamedCoordinate> coordinates, List<NamedCache> caches
    ) {
        return new StowlingConfig(
                config.schemaVersion(), coordinates, config.pickupRegions(), caches,
                config.pickupWorkflows(), config.automationWorkflows(), config.storageRoutes(),
                config.packingStations(), config.auditSchedules(), config.overflowContainer()
        );
    }

    private static void assertCustomPickupViolation(
            StowlingConfig config, String key, boolean inline
    ) {
        String path = inline
                ? "automationWorkflows[0].settings.interruption.steps[0].parameters"
                : "automationWorkflows[0].steps[0].parameters";
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(violation ->
                violation.path().equals(path) && violation.message().contains(key)
        ), "missing direct reference violation for " + key);
    }

    private static AutomationStep waitStep(String id) {
        return new AutomationStep(id, AutomationStepType.WAIT, Map.of("seconds", "1"));
    }

    private static PickupRegionBinding binding(String regionId) {
        return new PickupRegionBinding(regionId, 100, Duration.ofSeconds(60));
    }

    private static StowlingConfig config(
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        return new StowlingConfig(
                2,
                regions,
                caches,
                workflows,
                base.storageRoutes(),
                base.packingStations(),
                List.of(),
                base.overflowContainer()
        );
    }
}
