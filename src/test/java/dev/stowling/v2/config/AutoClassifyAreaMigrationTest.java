package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskPriority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoClassifyAreaMigrationTest {
    @TempDir
    Path directory;

    @Test
    void expandsLegacySingleZoneIntoBindingLevelAreas() throws Exception {
        UUID coordinateId = UUID.randomUUID();
        UUID regionId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID secondBindingId = UUID.randomUUID();
        CoordinatePoint coordinate = new CoordinatePoint(
                coordinateId,
                "Drops",
                0,
                64,
                0
        );
        RegionDefinition region = new RegionDefinition(
                regionId,
                "Cache",
                0,
                64,
                0,
                8,
                70,
                8
        );
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                zoneId,
                "Ore",
                List.of(new CategoryBindingDefinition(
                        bindingId,
                        new AreaBounds(20, 64, 20, 24, 64, 24),
                        new AreaBounds(20, 64, 18, 24, 64, 18),
                        "DIRECT_BLOCK_ID"
                ), new CategoryBindingDefinition(
                        secondBindingId,
                        new AreaBounds(30, 64, 20, 34, 64, 20),
                        new AreaBounds(30, 64, 18, 34, 64, 18),
                        "CONTAINER_CONTENTS"
                ))
        );
        String zoneRef = ResourceReference.of(
                ResourceType.CATEGORY_ZONE,
                zoneId
        ).encode();
        AutoClassifyDefinition definition = new AutoClassifyDefinition(
                UUID.randomUUID(),
                "自动分类",
                false,
                true,
                true,
                "coordinate:" + coordinateId,
                8,
                "region:" + regionId,
                List.of(new AutoClassifyAreaDefinition(
                        zoneRef,
                        bindingId,
                        "DIRECT_BLOCK_ID",
                        List.of(CategoryMarkerSource.PLACED_ITEM)
                )),
                TaskPriority.NORMAL
        );
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(coordinate),
                AutoClassifyTaskFactory.tasks(definition, List.of()),
                List.of(region),
                List.of(zone),
                List.of(),
                List.of(),
                List.of(definition)
        );
        Path path = directory.resolve("stowling.json");
        ConfigStore store = new ConfigStore(path);
        V2ConfigSnapshot initial = store.loadOrReset();
        store.save(initial.revision(), config);

        JsonObject root = JsonParser.parseString(
                Files.readString(path)
        ).getAsJsonObject();
        root.addProperty("schemaVersion", 9);
        JsonObject autoTask = root.getAsJsonArray("autoClassifyTasks")
                .get(0)
                .getAsJsonObject();
        autoTask.remove("classificationAreas");
        autoTask.addProperty("categoryZoneRef", zoneRef);
        JsonArray markerSources = new JsonArray();
        markerSources.add("PLACED_ITEM");
        autoTask.add("markerSources", markerSources);
        Files.writeString(path, root.toString());

        AutoClassifyDefinition migrated = store.loadOrReset()
                .config()
                .autoClassifyTasks()
                .getFirst();

        assertEquals(AppConfig.CURRENT_SCHEMA_VERSION, store.read()
                .config().schemaVersion());
        assertEquals(2, migrated.classificationAreas().size());
        assertEquals(
                bindingId,
                migrated.classificationAreas().getFirst().bindingId()
        );
        assertEquals(
                "DIRECT_BLOCK_ID",
                migrated.classificationAreas().getFirst().ruleId()
        );
        assertEquals(
                secondBindingId,
                migrated.classificationAreas().get(1).bindingId()
        );
        assertEquals(
                "CONTAINER_CONTENTS",
                migrated.classificationAreas().get(1).ruleId()
        );
        assertEquals(
                List.of(CategoryMarkerSource.CONTAINER_CONTENTS),
                migrated.classificationAreas().get(1).markerSources()
        );
    }
}
