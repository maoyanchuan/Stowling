package dev.stowling.v2.control;

import java.time.Instant;

public record StatusResponse(String state, Instant observedAt) {
}
