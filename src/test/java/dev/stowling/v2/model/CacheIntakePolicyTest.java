package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CacheIntakePolicyTest {
    @Test
    void oldDefinitionsDefaultToPackingAtEightyPercentAndNewPoliciesRoundTrip() {
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Cache", false, true, true,
                "coordinate:" + UUID.randomUUID(), 8, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL);
        assertEquals(CacheIntakePolicy.DEFAULT, definition.cachePolicy());
        var gson = new Gson();
        var json = gson.toJsonTree(definition).getAsJsonObject();
        json.remove("cachePolicy");
        assertEquals(CacheIntakePolicy.DEFAULT,
                gson.fromJson(json, AutoClassifyDefinition.class).cachePolicy());

        var policy = new CacheIntakePolicy(CacheIntakePolicy.Mode.CLASSIFY_FIRST, 65);
        json.add("cachePolicy", gson.toJsonTree(policy));
        var loaded = gson.fromJson(json, AutoClassifyDefinition.class);
        assertEquals(policy, loaded.cachePolicy());
        var intake = AutoClassifyTaskFactory.tasks(loaded, List.of()).getFirst()
                .steps().get(1).steps().get(1);
        assertEquals("CLASSIFY_FIRST", intake.parameters().get("cacheIntakeMode"));
        assertEquals("65", intake.parameters().get("cacheThresholdPercent"));
    }

    @Test
    void rejectsPercentagesOutsideTheConfiguredRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheIntakePolicy(CacheIntakePolicy.Mode.PACK_FIRST, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheIntakePolicy(CacheIntakePolicy.Mode.PACK_FIRST, 101));
    }
}
