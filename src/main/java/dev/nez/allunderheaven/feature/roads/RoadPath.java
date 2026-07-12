package dev.nez.allunderheaven.feature.roads;

import java.util.List;

/**
 * An immutable, fully-deterministic road plan between two villages: sample
 * points every ~4 blocks with slope-relaxed heights, plus precomputed lamp
 * spots. Any chunk can independently materialize its slice of the same path.
 */
public record RoadPath(int[] xs, int[] zs, float[] ys, boolean[] wet, List<Lamp> lamps, Bounds2D bounds) {

    /** Lamp post location (side offset already applied). */
    public record Lamp(int x, int z) {
    }

    /** Simple XZ bounding rectangle. */
    public record Bounds2D(int minX, int minZ, int maxX, int maxZ) {
        public Bounds2D grow(int amount) {
            return new Bounds2D(minX - amount, minZ - amount, maxX + amount, maxZ + amount);
        }

        public boolean intersects(int otherMinX, int otherMinZ, int otherMaxX, int otherMaxZ) {
            return maxX >= otherMinX && minX <= otherMaxX && maxZ >= otherMinZ && minZ <= otherMaxZ;
        }
    }

    public int sampleCount() {
        return xs.length;
    }
}
