package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceReferenceTest {
    @Test
    void parsesAndEncodesCanonicalReferences() {
        UUID coordinate = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        assertEquals(
                ResourceType.COORDINATE,
                ResourceReference.parse("coordinate:" + coordinate).type()
        );
        assertEquals(
                "coordinate:" + coordinate,
                ResourceReference.of(
                        ResourceType.COORDINATE,
                        coordinate
                ).encode()
        );
        assertEquals(
                "task:" + coordinate,
                ResourceReference.parse("task:" + coordinate).encode()
        );
    }

    @Test
    void parsesOnlyKnownOutputs() {
        assertEquals(
                "output:storage",
                ResourceReference.parse("output:storage").encode()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourceReference.parse("output:unknown")
        );
        assertThrows(
                IllegalStateException.class,
                () -> ResourceReference.output("container").uuid()
        );
    }
}
