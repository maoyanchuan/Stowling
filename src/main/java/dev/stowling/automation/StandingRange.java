package dev.stowling.automation;

import dev.stowling.v2.model.AreaBounds;

/** Independent classification standing range above configured support blocks. */
public final class StandingRange {
    private static final double EPSILON = 1.0E-5;
    private static final double MAX_HORIZONTAL_SPEED = 0.03;

    private StandingRange() {
    }

    public static double minFeetY(AreaBounds area) {
        return area.minY() - 1.0 + 1.0 / 16.0;
    }

    public static double maxFeetY(AreaBounds area) {
        return area.maxY() + 1.0 / 8.0;
    }

    public static boolean contains(AreaBounds area, double x, double y, double z) {
        return x >= area.minX() && x < area.maxX() + 1.0
                && z >= area.minZ() && z < area.maxZ() + 1.0
                && y >= minFeetY(area) - EPSILON && y <= maxFeetY(area) + EPSILON;
    }

    public static boolean settled(AreaBounds area, double x, double y, double z,
            boolean onGround, double velocityX, double velocityZ) {
        return contains(area, x, y, z) && onGround
                && velocityX * velocityX + velocityZ * velocityZ
                        <= MAX_HORIZONTAL_SPEED * MAX_HORIZONTAL_SPEED;
    }
}
