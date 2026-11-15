package dev.stowling.v2.control;

import dev.stowling.v2.model.CoordinatePoint;
import java.util.List;

public record CoordinateListResponse(
        String revision,
        List<CoordinatePoint> coordinates
) {
    public CoordinateListResponse {
        coordinates = List.copyOf(coordinates);
    }
}
