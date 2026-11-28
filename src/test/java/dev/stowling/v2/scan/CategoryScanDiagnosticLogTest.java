package dev.stowling.v2.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.control.V2NotFoundException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryScanDiagnosticLogTest {
    @Test
    void capturesRealBlocksAnchorsCandidatesAndResultsWithoutChangingEvaluation() {
        var world = CategoryScanEngineTest.pressurePlateWorld();
        var log = new CategoryScanDiagnosticLog();
        var capture = log.begin("PREVIEW", world.area(), null, CategoryScanEngineTest.OPTIONS,
                Map.of("stowling", "test"));
        capture.observe(world);
        var result = CategoryScanEngine.preview(world, CategoryScanEngineTest.OPTIONS,
                capture::stage);
        capture.finish(result, null);
        var entry = log.get(capture.id());
        assertEquals("SUCCEEDED", entry.summary().state());
        assertEquals(world.blocks(), entry.world().blocks());
        assertEquals(world.markers(), entry.world().markers());
        for (var attachment : entry.world().attachments()) {
            assertEquals(world.markerAnchors().get(attachment.position()), attachment.anchor());
        }
        assertFalse(entry.world().attachments().isEmpty());
        assertTrue(entry.stages().stream().anyMatch(stage ->
                stage.stage().equals("MARKER_CANDIDATES")));
        assertTrue(entry.world().markers().stream().anyMatch(marker ->
                marker.itemId().equals("minecraft:oak_pressure_plate")));
        assertTrue(entry.stages().stream().anyMatch(stage ->
                stage.stage().equals("SLICE_CATEGORIES") && stage.data().toString()
                        .contains("minecraft:oak_pressure_plate")));
        assertTrue(entry.world().blocks().stream().anyMatch(block ->
                block.blockId().equals("minecraft:stripped_warped_hyphae")));
        var observed = new ArrayList<String>();
        var traced = CategoryScanEngine.evaluate(world, result.scan(), CategoryScanEngineTest.OPTIONS,
                (stage, data) -> observed.add(stage));
        var plain = CategoryScanEngine.evaluate(world, result.scan(), CategoryScanEngineTest.OPTIONS);
        assertEquals(plain, traced);
        assertEquals("FINAL_TEMPLATE", observed.getFirst());
        assertEquals("SLICE_RESULTS", observed.getLast());
        assertTrue(observed.contains("SLICE_CATEGORIES"));
    }

    @Test
    void keepsOnlyThreeCompleteRecordsAndFailureBeforeWorldReadRemainsQueryable() {
        var log = new CategoryScanDiagnosticLog();
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < 4; index++) {
            var capture = log.begin("PREVIEW", CategoryScanEngineTest.AREA, null,
                    CategoryScanEngineTest.OPTIONS, Map.of());
            ids.add(capture.id());
            capture.finish(null, new IllegalArgumentException("未连接世界"));
        }
        assertEquals(3, log.history().scans().size());
        assertEquals(ids.getLast(), log.history().scans().getFirst().id());
        assertThrows(V2NotFoundException.class, () -> log.get(ids.getFirst()));
        var entry = log.get(ids.getLast());
        assertEquals("FAILED", entry.summary().state());
        assertEquals("未连接世界", entry.summary().message());
        assertEquals(null, entry.world());
        assertTrue(entry.summary().elapsedMillis() >= 0);
        assertTrue(new CategoryScanDiagnosticLog().history().scans().isEmpty());
    }

    @Test
    void retainsPartialSlicesAndRedactsCustomNamesAndCredentials() {
        var world = CategoryScanEngineTest.world(-1, -1);
        var preview = CategoryScanEngine.preview(world, CategoryScanEngineTest.OPTIONS);
        var log = new CategoryScanDiagnosticLog();
        var partial = CategoryScanEngine.evaluate(CategoryScanEngineTest.world(2, -1),
                preview.scan(), CategoryScanEngineTest.OPTIONS);
        var capture = log.begin("CONFIRM", world.area(), preview.scan(),
                CategoryScanEngineTest.OPTIONS, Map.of());
        capture.finish(partial, null);
        assertEquals("PARTIAL", log.get(capture.id()).summary().state());
        assertEquals(9, log.get(capture.id()).summary().validSlices());
        var failed = log.begin("SAVE", world.area(), preview.scan(),
                CategoryScanEngineTest.OPTIONS, Map.of());
        String name = preview.scan().templates().getFirst().name();
        failed.stage("ERROR", Map.of("name", "Private name", "token", "secret",
                "message", name + " Bearer hidden-token"));
        failed.finish(null, new IllegalArgumentException(name + " password=hidden-password"));
        var entry = log.get(failed.id());
        assertFalse(entry.request().toString().contains("\"name\""));
        assertFalse(entry.stages().toString().contains("hidden-token"));
        assertFalse(entry.stages().toString().contains("Private name"));
        assertFalse(entry.summary().message().contains("hidden-password"));
        assertFalse(entry.summary().message().contains(name));
    }
}
