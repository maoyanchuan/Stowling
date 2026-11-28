package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.scan.CategoryScanEngine;
import dev.stowling.v2.scan.CategoryScanEngineTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryScanPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void roundTripKeepsTheIndependentMotherAndNeverRecreatesAMissingMiddleSlice() throws Exception {
        var result = CategoryScanEngine.preview(CategoryScanEngineTest.world(2, 0),
                CategoryScanEngineTest.OPTIONS);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Scan", result.bindings(), null,
                CategoryScanEngineTest.OPTIONS, CategoryScanEngineTest.AREA, result.scan());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), List.of(),
                List.of(), List.of(zone));
        var path = directory.resolve("scan.json");
        Files.writeString(path, new Gson().toJson(config));
        var store = new ConfigStore(path);
        var first = store.read();
        store.save(first.revision(), first.config());
        var restored = new ConfigStore(path).read().config().categoryZones().getFirst();
        assertEquals(zone, restored);
        assertEquals(1, restored.scan().templateIndex());
        assertEquals(List.of(1, 3, 4, 5, 6, 7, 8, 9), restored.bindings().stream()
                .map(binding -> binding.storageArea().minX()).toList());
        assertEquals(restored.bindings(), CategoryScanEngine.savedBindings(restored.scan()));
    }

    @Test
    void forgedSliceStatusCannotDisagreeWithTheEffectiveBindingsOnLoad() throws Exception {
        var result = CategoryScanEngine.preview(CategoryScanEngineTest.world(2, -1),
                CategoryScanEngineTest.OPTIONS);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Scan", result.bindings(), null,
                CategoryScanEngineTest.OPTIONS, CategoryScanEngineTest.AREA, result.scan());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), List.of(),
                List.of(), List.of(zone));
        var json = JsonParser.parseString(new Gson().toJson(config)).getAsJsonObject();
        json.getAsJsonArray("categoryZones").get(0).getAsJsonObject().getAsJsonObject("scan")
                .getAsJsonArray("slices").get(2).getAsJsonObject().addProperty("reason", "");
        var path = directory.resolve("invalid-scan.json");
        Files.writeString(path, json.toString());
        assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
    }

    @Test
    void scanJsonRejectsUnknownFieldsFractionsNullCoordinatesAndStringBooleans() {
        var result = CategoryScanEngine.preview(CategoryScanEngineTest.world(-1, -1),
                CategoryScanEngineTest.OPTIONS);
        var source = JsonParser.parseString(new Gson().toJson(result.scan())).getAsJsonObject();
        CategoryScanJson.validate(source);
        for (String bad : List.of("unknown", "fraction", "null", "boolean")) {
            var value = source.deepCopy();
            switch (bad) {
                case "unknown" -> value.addProperty("unexpected", true);
                case "fraction" -> value.getAsJsonObject("area").addProperty("firstX", 0.5);
                case "null" -> value.getAsJsonObject("area").add("firstX", null);
                default -> value.getAsJsonArray("blocks").get(0).getAsJsonObject()
                        .addProperty("upper", "false");
            }
            assertThrows(V2ValidationException.class, () -> CategoryScanJson.validate(value));
        }
    }
}
