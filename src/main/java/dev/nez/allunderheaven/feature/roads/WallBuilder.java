package dev.nez.allunderheaven.feature.roads;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stamps the tier-2 city wall for one chunk: two 1-block courses (see
 * {@link VillageContour#wallInner()}/{@link VillageContour#wallOuter()}) built
 * as terrain-following columns, 4 blocks of body plus crenellation merlons on
 * the outer course. Materials are a deterministic per-position medieval mix —
 * cobblestone plinth, stone-brick body with cracked/mossy wear, chiseled
 * accents on the cornice — so the wall reads weathered without any RNG state.
 *
 * <p>Gates are deliberately absent for now: roads that cross the wall band are
 * sealed over (a later feature cuts gateways where roads and streets pass).
 */
public final class WallBuilder {
    /** Solid body height above ground; merlons add one more on the outer course. */
    private static final int WALL_HEIGHT = 4;
    /** How far a column may dig down through foliage/air to find real ground. */
    private static final int MAX_GROUND_DIG = 12;

    private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState STONE_BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CRACKED_STONE_BRICKS = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState MOSSY_STONE_BRICKS = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    private static final BlockState CHISELED_STONE_BRICKS = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState STONE_BRICK_SLAB = Blocks.STONE_BRICK_SLAB.defaultBlockState();

    private WallBuilder() {
    }

    /** Stamps this village's wall cells that fall inside the chunk; returns blocks placed. */
    public static long stampWalls(WorldGenLevel region, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        placed += stampCourse(region, village.wallInner(), false, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        placed += stampCourse(region, village.wallOuter(), true, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        return placed;
    }

    private static long stampCourse(WorldGenLevel region, long[] course, boolean outer,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (long p : course) {
            int x = VillageContour.pointX(p);
            int z = VillageContour.pointZ(p);
            if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                continue;
            }
            placed += placeWallColumn(region, x, z, outer);
        }
        return placed;
    }

    /**
     * One wall column on the LIVE surface (villages terraform, so the noise
     * height is wrong here), digging through trees and overhangs to real
     * ground first so the wall never stands on a canopy.
     */
    private static long placeWallColumn(WorldGenLevel region, int x, int z, boolean outer) {
        int y = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos(x, y, z);
        for (int dig = 0; dig < MAX_GROUND_DIG; dig++) {
            BlockState state = region.getBlockState(ground);
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                break;
            }
            ground.move(0, -1, 0);
        }

        long placed = 0;
        // Foundation: bridge small hollows under the wall.
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos below = ground.below(dy);
            BlockState state = region.getBlockState(below);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                region.setBlock(below, COBBLESTONE, 2);
                placed++;
            }
        }
        // Body: plinth, weathered brick, cornice.
        for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
            region.setBlock(ground.above(dy), bodyBlock(x, ground.getY() + dy, z, dy), 2);
            placed++;
        }
        // Crenellation: alternating merlons on the outer course only.
        if (outer) {
            BlockPos top = ground.above(WALL_HEIGHT + 1);
            region.setBlock(top, ((x + z) & 1) == 0 ? merlonBlock(x, top.getY(), z) : STONE_BRICK_SLAB, 2);
            placed++;
        }
        return placed;
    }

    private static BlockState bodyBlock(int x, int y, int z, int layer) {
        int roll = roll(x, y, z);
        if (layer == 1) {
            // Plinth: darker, contrasting base course.
            return roll < 60 ? COBBLESTONE : roll < 85 ? ANDESITE : MOSSY_COBBLESTONE;
        }
        if (layer == WALL_HEIGHT) {
            // Cornice: cleaner top band with chiseled accents.
            return roll < 70 ? STONE_BRICKS : roll < 90 ? CHISELED_STONE_BRICKS : CRACKED_STONE_BRICKS;
        }
        // Weathered brick body.
        return roll < 60 ? STONE_BRICKS
                : roll < 75 ? CRACKED_STONE_BRICKS
                : roll < 85 ? MOSSY_STONE_BRICKS
                : COBBLESTONE;
    }

    private static BlockState merlonBlock(int x, int y, int z) {
        return roll(x, y, z) < 80 ? STONE_BRICKS : CRACKED_STONE_BRICKS;
    }

    /** Deterministic 0-99 roll from the block position (chunk-order independent). */
    private static int roll(int x, int y, int z) {
        long hash = x * 341873128712L + z * 132897987541L + y * 914744087L;
        hash = hash * 0x27D4EB2F165667C5L + 0x9E3779B97F4A7C15L;
        return (int) Math.floorMod(hash >> 17, 100);
    }
}
