package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationRemainderPolicyTest {
    @Test
    void disabledDestinationsSurviveSerialization() {
        var policy = new ClassificationRemainderPolicy(false, reference("coordinate"),
                false, reference("region"));
        var gson = new Gson();
        assertEquals(policy,
                gson.fromJson(gson.toJson(policy), ClassificationRemainderPolicy.class));
    }

    @Test
    void enabledRoutesRequireAnIndependentSpatialDestination() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassificationRemainderPolicy(true, null, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassificationRemainderPolicy(true, reference("task"), false, null));
        String source = reference("region");
        var policy = new ClassificationRemainderPolicy(true, source, false, null);
        assertThrows(IllegalArgumentException.class, () -> policy.requireSeparateSource(source));
    }

    @Test
    void routingRunsAfterAllZonesAndBeforeTheRobotWarehouse() {
        var policy = new ClassificationRemainderPolicy(true, reference("coordinate"),
                true, reference("region"));
        String source = reference("region");
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "分类", false, false, true,
                null, null, source, List.of(new AutoClassifyAreaDefinition(reference("category-zone")),
                        new AutoClassifyAreaDefinition(reference("category-zone"))),
                TaskPriority.NORMAL, true, null, null, reference("warehouse"), null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, false, policy);
        var steps = AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst().steps();
        assertEquals(ClassificationRemainderPolicy.MODULE, steps.get(4).moduleId());
        assertEquals(ClassificationRemainderPolicy.UNCLASSIFIABLE,
                steps.get(4).parameters().get(ClassificationRemainderPolicy.KIND));
        assertEquals(policy.unclassifiableDestinationRef(),
                steps.get(4).parameters().get(ConfigValidator.TARGET_REF));
        assertEquals(ClassificationRemainderPolicy.OVERFLOW,
                steps.get(5).parameters().get(ClassificationRemainderPolicy.KIND));
        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, steps.get(6).moduleId());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(), List.of(),
                List.of(), List.of(), List.of(definition));
        var normalized = TaskFlowNormalizer.normalize(config).tasks().getFirst().steps();
        var robot = normalized.stream().filter(step ->
                ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())).findFirst().orElseThrow();
        assertEquals(definition.overflowDestinationRef(),
                robot.parameters().get(ConfigValidator.TARGET_REF));
    }

    private static String reference(String type) {
        return type + ":" + UUID.randomUUID();
    }
}
