package dev.nez.allunderheaven.feature.villages;

import java.util.Random;

import net.minecraft.world.level.ChunkPos;

/**
 * City tier of a village, derived purely from (world seed, start chunk) the
 * same way {@link VillageNames} are — stable on any server/client without
 * persisting anything.
 *
 * <ul>
 *   <li>TIER1 — plain village (5 in 10): vanilla look, dirt-path roads.</li>
 *   <li>TIER2 — walled town (4 in 10 = 2 of 5): stone streets and a stone
 *       wall wrapped around the road contour.</li>
 *   <li>TIER3 — city (1 in 10): reserved stub, currently unmodified.</li>
 * </ul>
 */
public enum VillageTier {
    TIER1,
    TIER2,
    TIER3;

    /** Stable tier for the village whose structure start sits in {@code startChunk}. */
    public static VillageTier of(long worldSeed, ChunkPos startChunk) {
        // Different mix constant than VillageNames so tier and name don't correlate.
        Random random = new Random(worldSeed ^ startChunk.pack() * 0xC2B2AE3D27D4EB4FL);
        int roll = random.nextInt(10);
        if (roll == 0) {
            return TIER3;
        }
        return roll <= 4 ? TIER2 : TIER1;
    }
}
