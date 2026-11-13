package dev.stowling.automation;

public interface PackingPort {
    PackingDisposition submit(PackingRequest request);
}
