package dev.nez.allunderheaven.feature.roads;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
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
 * <p>Edge handling: columns standing in water or over a carved hole (ravine —
 * detected as live ground sitting far below the generator's uncarved noise
 * surface) are skipped, so the wall breaks cleanly at rivers and canyon rims.
 * Where a road crosses the wall line, a gate arch is built instead (see
 * {@link #placeGateColumn}): a 5-wide opening — the 3-wide road plus one
 * block of padding — with the wall rising over it in an arc. Guard towers
 * (1-3 per town, chosen in {@link VillageContour}) straddle the wall as
 * diameter-5 cylinders with an interior stair spiral and a doorway facing
 * the town.
 */
public final class WallBuilder {
    /** Solid body height above ground; merlons add one more on the outer course. */
    private static final int WALL_HEIGHT = 4;
    /** How far a column may dig down through foliage/air to find real ground. */
    private static final int MAX_GROUND_DIG = 12;
    /** Live ground this far below the uncarved noise surface = ravine, skip. */
    private static final int RAVINE_DROP = 6;
    /** Tower body height; the merlon crown adds one more. */
    private static final int TOWER_HEIGHT = 6;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState STONE_BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CRACKED_STONE_BRICKS = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState MOSSY_STONE_BRICKS = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    private static final BlockState CHISELED_STONE_BRICKS = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState STONE_BRICK_SLAB = Blocks.STONE_BRICK_SLAB.defaultBlockState();

    /** Interior ring of the tower (3x3 minus center), in walk order. */
    private static final int[][] TOWER_RING = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private WallBuilder() {
    }

    /**
     * Stamps this village's wall cells that fall inside the chunk; returns
     * blocks placed. {@code gates} maps wall cells crossed by a road (packed
     * coords, see {@link VillageContour#pack}) to {distance-from-centerline,
     * road Y} — those columns become the gate arch.
     */
    public static long stampWalls(WorldGenLevel region, ServerLevel serverLevel, VillageContour village,
            Map<Long, int[]> gates, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        placed += stampCourse(region, serverLevel, village.wallInner(), false, gates,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        placed += stampCourse(region, serverLevel, village.wallOuter(), true, gates,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        return placed;
    }

    private static long stampCourse(WorldGenLevel region, ServerLevel serverLevel, long[] course, boolean outer,
            Map<Long, int[]> gates, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (long p : course) {
            int x = VillageContour.pointX(p);
            int z = VillageContour.pointZ(p);
            if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                continue;
            }
            int[] gate = gates.get(p);
            placed += gate != null
                    ? placeGateColumn(region, x, z, gate[0], gate[1])
                    : placeWallColumn(region, serverLevel, x, z, outer);
        }
        return placed;
    }

    /**
     * One wall column on the LIVE surface (villages terraform, so the noise
     * height is wrong here), digging through trees and overhangs to real
     * ground first so the wall never stands on a canopy. Columns in water or
     * hanging over a ravine are skipped entirely.
     */
    private static long placeWallColumn(WorldGenLevel region, ServerLevel serverLevel, int x, int z, boolean outer) {
        BlockPos.MutableBlockPos ground = findGround(region, x, z);
        if (ground == null || RoadBuilder.terrainHeight(serverLevel, x, z) - ground.getY() > RAVINE_DROP) {
            return 0; // water column or carved hole (ravine): no wall here
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
            region.setBlock(ground.above(dy), bodyBlock(x, ground.getY() + dy, z, dy, WALL_HEIGHT), 2);
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

    /**
     * A gate column where a road crosses the wall: passage below, the wall
     * arcing over it. Directly over the road (distance <= 1 from the
     * centerline) the passage is 4 high; on the 1-block padding it is 3, so
     * the soffit rises toward the middle like an arch. Heights hang off the
     * road surface, which was stamped before the walls in this same chunk.
     */
    private static long placeGateColumn(WorldGenLevel region, int x, int z, int centerlineDist, int roadY) {
        int passage = centerlineDist <= 1 ? 4 : 3;
        long placed = 0;
        for (int dy = 1; dy <= passage; dy++) {
            BlockPos pos = new BlockPos(x, roadY + dy, z);
            if (!region.getBlockState(pos).isAir()) {
                region.setBlock(pos, AIR, 2);
            }
        }
        for (int dy = passage + 1; dy <= WALL_HEIGHT + 2; dy++) {
            // Soffit ring in clean brick, weathered mix above.
            BlockState state = dy == passage + 1 ? STONE_BRICKS
                    : bodyBlock(x, roadY + dy, z, dy, WALL_HEIGHT + 2);
            region.setBlock(new BlockPos(x, roadY + dy, z), state, 2);
            placed++;
        }
        return placed;
    }

    // --- towers ---

    /** Stamps the parts of this village's guard towers that fall inside the chunk. */
    public static long stampTowers(WorldGenLevel region, ServerLevel serverLevel, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (BlockPos tower : village.towerCenters()) {
            if (tower.getX() + 2 < minBlockX || tower.getX() - 2 > maxBlockX
                    || tower.getZ() + 2 < minBlockZ || tower.getZ() - 2 > maxBlockZ) {
                continue;
            }
            placed += stampTower(region, serverLevel, tower.getX(), tower.getZ(), village.center(),
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }
        return placed;
    }

    /**
     * A diameter-5 cylinder straddling the wall: shell to {@link #TOWER_HEIGHT}
     * with a merlon crown, hollow interior with a spiral of one-block steps, a
     * 2-high doorway facing the town, and a brick platform on top with an
     * opening where the stairs emerge. The whole tower shares one base height
     * (the ground under its center), so the rings stay level; columns of it in
     * other chunks recompute the same base from the same center column.
     */
    private static long stampTower(WorldGenLevel region, ServerLevel serverLevel, int cx, int cz,
            BlockPos villageCenter, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        BlockPos.MutableBlockPos ground = findGround(region, cx, cz);
        if (ground == null || RoadBuilder.terrainHeight(serverLevel, cx, cz) - ground.getY() > RAVINE_DROP) {
            return 0; // tower center in water or over a ravine: skip the tower
        }
        int baseY = ground.getY();

        // Doorway on the shell cell facing the town (dominant axis).
        int toCenterX = villageCenter.getX() - cx;
        int toCenterZ = villageCenter.getZ() - cz;
        int doorDx = 0;
        int doorDz = 0;
        if (Math.abs(toCenterX) >= Math.abs(toCenterZ)) {
            doorDx = toCenterX >= 0 ? 2 : -2;
        } else {
            doorDz = toCenterZ >= 0 ? 2 : -2;
        }
        // Stairs start next to the doorway so the entrance stays clear.
        int ringStart = 0;
        for (int i = 0; i < TOWER_RING.length; i++) {
            if (TOWER_RING[i][0] == Integer.signum(doorDx) && TOWER_RING[i][1] == Integer.signum(doorDz)) {
                ringStart = i + 1;
                break;
            }
        }
        int emergeX = cx + TOWER_RING[(ringStart + TOWER_HEIGHT - 2) % TOWER_RING.length][0];
        int emergeZ = cz + TOWER_RING[(ringStart + TOWER_HEIGHT - 2) % TOWER_RING.length][1];

        long placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 6) {
                    continue; // outside the cylinder footprint
                }
                int x = cx + dx;
                int z = cz + dz;
                if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                    continue;
                }
                boolean shell = d2 >= 4;
                // Floor and foundation.
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos below = new BlockPos(x, baseY - dy, z);
                    BlockState state = region.getBlockState(below);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        region.setBlock(below, COBBLESTONE, 2);
                        placed++;
                    }
                }
                if (shell) {
                    boolean door = dx == doorDx && dz == 0 || dz == doorDz && dx == 0;
                    for (int dy = 1; dy <= TOWER_HEIGHT; dy++) {
                        BlockPos pos = new BlockPos(x, baseY + dy, z);
                        if (door && dy <= 2) {
                            region.setBlock(pos, AIR, 2);
                        } else {
                            region.setBlock(pos, bodyBlock(x, baseY + dy, z, dy, TOWER_HEIGHT), 2);
                            placed++;
                        }
                    }
                    // Merlon crown, alternating like the wall's.
                    BlockPos top = new BlockPos(x, baseY + TOWER_HEIGHT + 1, z);
                    region.setBlock(top, ((x + z) & 1) == 0 ? merlonBlock(x, top.getY(), z) : STONE_BRICK_SLAB, 2);
                    placed++;
                } else {
                    // Hollow interior (also digs the tower out of a hillside),
                    // then the top platform with the stair emergence left open.
                    for (int dy = 1; dy < TOWER_HEIGHT; dy++) {
                        BlockPos pos = new BlockPos(x, baseY + dy, z);
                        if (!region.getBlockState(pos).isAir()) {
                            region.setBlock(pos, AIR, 2);
                        }
                    }
                    BlockPos platform = new BlockPos(x, baseY + TOWER_HEIGHT, z);
                    if (x == emergeX && z == emergeZ) {
                        region.setBlock(platform, AIR, 2);
                    } else {
                        region.setBlock(platform, STONE_BRICKS, 2);
                        placed++;
                    }
                }
            }
        }
        // Spiral steps: one-block risers walking the interior ring upward.
        for (int step = 1; step < TOWER_HEIGHT; step++) {
            int[] cell = TOWER_RING[(ringStart + step - 1) % TOWER_RING.length];
            int x = cx + cell[0];
            int z = cz + cell[1];
            if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                continue;
            }
            region.setBlock(new BlockPos(x, baseY + step, z), STONE_BRICKS, 2);
            placed++;
        }
        return placed;
    }

    // --- shared helpers ---

    /**
     * Live ground under (x, z), digging through foliage and overhangs.
     * Returns null when the column stands in water (walls skip water) or no
     * solid ground is found within {@link #MAX_GROUND_DIG}.
     */
    private static BlockPos.MutableBlockPos findGround(WorldGenLevel region, int x, int z) {
        int y = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos(x, y, z);
        for (int dig = 0; dig < MAX_GROUND_DIG; dig++) {
            BlockState state = region.getBlockState(ground);
            if (!state.getFluidState().isEmpty()) {
                return null; // water/lava column
            }
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                return ground;
            }
            ground.move(0, -1, 0);
        }
        return null; // no real ground within reach
    }

    private static BlockState bodyBlock(int x, int y, int z, int layer, int topLayer) {
        int roll = roll(x, y, z);
        if (layer == 1) {
            // Plinth: darker, contrasting base course.
            return roll < 60 ? COBBLESTONE : roll < 85 ? ANDESITE : MOSSY_COBBLESTONE;
        }
        if (layer == topLayer) {
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
