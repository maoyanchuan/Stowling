package dev.stowling.domain;

public record BlockPosition(int x, int y, int z) {
    public BlockPosition offset(int deltaX, int deltaY, int deltaZ) {
        return new BlockPosition(x + deltaX, y + deltaY, z + deltaZ);
    }
}
