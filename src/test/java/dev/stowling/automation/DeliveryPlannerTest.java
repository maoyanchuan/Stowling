package dev.stowling.automation;

import static dev.stowling.automation.AutomationTestSupport.DIAMOND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.StorageRoute;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryPlannerTest {
    @Test
    void highestPriorityMatchingRouteWins() {
        AutomationTestSupport support = new AutomationTestSupport();
        ContainerRef preferred = new ContainerRef(
                "preferred",
                AutomationTestSupport.DIMENSION,
                new BlockPosition(40, 64, 0),
                ContainerType.BARREL
        );
        StorageRoute fallback = route("fallback", support.bulk, 1);
        StorageRoute preferredRoute = route("preferred-route", preferred, 20);
        ContainerView buffer = AutomationFakes.view(
                support.cache,
                2,
                List.of(new ContainerSlot(0, DIAMOND, 5))
        );

        List<DeliveryIntent> plan = new DeliveryPlanner().plan(
                UUID.randomUUID(),
                support.cache,
                buffer,
                List.of(fallback, preferredRoute),
                support.overflow,
                ignored -> Set.of()
        );

        assertEquals(1, plan.size());
        assertEquals(preferred, plan.getFirst().destination());
        assertEquals(Optional.of("preferred-route"), plan.getFirst().routeId());
    }

    @Test
    void itemTagsCanSelectARoute() {
        AutomationTestSupport support = new AutomationTestSupport();
        StorageRoute tagged = new StorageRoute(
                "tagged",
                List.of(),
                List.of("c:gems"),
                10,
                support.bulk,
                null,
                null
        );
        ContainerView buffer = AutomationFakes.view(
                support.cache,
                2,
                List.of(new ContainerSlot(0, DIAMOND, 1))
        );

        List<DeliveryIntent> plan = new DeliveryPlanner().plan(
                UUID.randomUUID(),
                support.cache,
                buffer,
                List.of(tagged),
                support.overflow,
                itemId -> Set.of("c:gems")
        );

        assertEquals(support.bulk, plan.getFirst().destination());
    }

    private StorageRoute route(String id, ContainerRef destination, int priority) {
        return new StorageRoute(
                id,
                List.of(DIAMOND),
                List.of(),
                priority,
                destination,
                null,
                null
        );
    }
}
