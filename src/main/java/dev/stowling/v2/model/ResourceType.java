package dev.stowling.v2.model;

public enum ResourceType {
    COORDINATE("coordinate"),
    REGION("region"),
    CATEGORY_ZONE("category-zone"),
    TASK("task"),
    OUTPUT("output");

    private final String prefix;

    ResourceType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
