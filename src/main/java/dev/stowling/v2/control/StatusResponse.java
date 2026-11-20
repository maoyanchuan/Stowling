package dev.stowling.v2.control;

import java.time.Instant;

public record StatusResponse(String state, Instant observedAt, String configurationNotice) {
    public StatusResponse(String state, Instant observedAt) {
        this(state, observedAt, null);
    }
}
