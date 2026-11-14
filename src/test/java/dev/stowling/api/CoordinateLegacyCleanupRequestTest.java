package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.control.ConfigCoordinatePointsUpdateRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoordinateLegacyCleanupRequestTest {
    private final StrictRequestParser parser = new StrictRequestParser();

    @Test
    void oldRequestAndTwoArgumentConstructorDefaultToNoLegacyCleanup() throws Exception {
        var parsed = parser.parseCoordinatePointsUpdate(body(""));
        var legacy = new ConfigCoordinatePointsUpdateRequest(
                parsed.revision(), parsed.coordinatePoints()
        );

        assertEquals("revision-1", parsed.revision());
        assertEquals("keep", parsed.coordinatePoints().getFirst().id());
        assertEquals(List.of(), parsed.removeLegacyReferencesFor());
        assertEquals(List.of(), legacy.removeLegacyReferencesFor());
        assertEquals(parsed, legacy);
    }

    @Test
    void acceptsExplicitEmptyCleanupArray() throws Exception {
        var parsed = parser.parseCoordinatePointsUpdate(
                body(",\"removeLegacyReferencesFor\":[]")
        );

        assertEquals(List.of(), parsed.removeLegacyReferencesFor());
        assertEquals(1, parsed.coordinatePoints().size());
    }

    @Test
    void preservesEveryExplicitCleanupCoordinateId() throws Exception {
        var parsed = parser.parseCoordinatePointsUpdate(body(
                ",\"removeLegacyReferencesFor\":[\"old-cache-position\",\"old-standby\"]"
        ));

        assertEquals(List.of("old-cache-position", "old-standby"),
                parsed.removeLegacyReferencesFor());
        assertEquals("keep", parsed.coordinatePoints().getFirst().id());
    }

    @Test
    void rejectsCleanupValuesThatAreNotArraysOfStrings() {
        for (String value : List.of(
                "null", "42", "true", "\"old-cache\"", "{}",
                "[42]", "[true]", "[null]", "[{}]", "[[]]", "[\"old-cache\",42]"
        )) {
            assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                    body(",\"removeLegacyReferencesFor\":" + value)), value);
        }
    }

    @Test
    void rejectsDuplicateAndEmptyCleanupIds() {
        for (String value : List.of(
                "[\"old-cache\",\"old-cache\"]", "[\"\"]", "[\"   \"]",
                "[\"old-cache\",\"\"]"
        )) {
            assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                    body(",\"removeLegacyReferencesFor\":" + value)), value);
        }
    }

    @Test
    void remainsStrictAboutUnknownFieldsAndRequiredCoordinateUpdateFields() {
        assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                body(",\"removeLegacyReferenceFor\":[]")));
        assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                "{\"revision\":\"revision-1\",\"removeLegacyReferencesFor\":[]}"));
        assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                "{\"coordinatePoints\":[],\"removeLegacyReferencesFor\":[]}"));
        assertThrows(InvalidRequestException.class, () -> parser.parseCoordinatePointsUpdate(
                body(",\"removeLegacyReferencesFor\":[],\"removeLegacyReferencesFor\":[]")));
    }

    private static String body(String extraFields) {
        return """
                {
                  "revision":"revision-1",
                  "coordinatePoints":[{
                    "id":"keep","name":"Keep coordinate","dimension":"minecraft:overworld",
                    "position":{"x":40,"y":64,"z":0}
                  }]
                  %s
                }
                """.formatted(extraFields);
    }
}
