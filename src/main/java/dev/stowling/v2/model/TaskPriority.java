package dev.stowling.v2.model;

public enum TaskPriority {
    BACKGROUND(0),
    NORMAL(10),
    URGENT(20);

    private final int rank;

    TaskPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean preempts(TaskPriority other) {
        return rank > other.rank;
    }
}
