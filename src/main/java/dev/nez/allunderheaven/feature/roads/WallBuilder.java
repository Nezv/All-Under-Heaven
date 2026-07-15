package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
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
 * <p>Edge handling: a wall column standing over water is carried across on a
 * cobblestone causeway (footing sunk to the bed, body raised from the water
 * surface) so a village straddling a river or ponds keeps an unbroken ring;
 * columns hanging over a carved hole (ravine — live ground far below the
 * generator's uncarved noise surface) are still skipped, so the wall breaks
 * cleanly at dry canyon rims. Where a road crosses the wall line, a
 * geometrically constant {@link GateArch gate arch} is stamped instead (see
 * {@link #stampGates}): a 5-wide opening that arcs from 3 high at the jambs to
 * 5 at the crown, rigid relative to the road surface. Guard towers (1-3 per
 * town, chosen in {@link VillageContour}) straddle the wall as rigid diameter-7
 * cylinders with a ladder up to the parapet and a doorway facing the town.
 */
public final class WallBuilder {
    /** Solid body height above ground; merlons add one more on the outer course. */
    private static final int WALL_HEIGHT = 4;
    /** How far a column may dig down through foliage/air/water to find real ground. */
    private static final int MAX_GROUND_DIG = 12;
    /** Live ground this far below the uncarved noise surface = ravine, skip. */
    private static final int RAVINE_DROP = 6;

    /** Tower body height; the merlon crown adds one more. Also the deck level. */
    private static final int TOWER_HEIGHT = 6;
    /** Tower footprint reaches ± this on each axis (diameter 7). */
    private static final int TOWER_RADIUS = 3;
    /** Disc membership: dx²+dz² ≤ this is inside the round 7-wide footprint. */
    private static final int TOWER_DISC_R2 = 10;
    /** Shell (outer ~1-block ring): dx²+dz² ≥ this within the disc. */
    private static final int TOWER_SHELL_R2 = 6;
    /** Doorway (and ladder foot) height in the tower shell. */
    private static final int DOOR_HEIGHT = 2;
    /** Blocks of air forced above a crown so terrain can never bury the top. */
    private static final int CLEAR_ABOVE = 2;

    /** Gate opening reaches ± this laterally across the wall (5 wide). */
    private static final int GATE_HALF_WIDTH = 2;
    /** Passage height at the arch crown; the edges drop to {@code PEAK - HALF_WIDTH}. */
    private static final int GATE_PEAK = 5;
    /** Top of the gatehouse body above the road surface; a merlon crown sits one higher. */
    private static final int GATE_BODY_TOP = 6;
    /** Gate reaches ± this along the road direction, spanning the wall's 2-course band. */
    private static final int GATE_THICK = 1;
    /** Two gate anchors closer than this (squared) are the same crossing. */
    private static final int GATE_DEDUPE_DIST2 = 5 * 5;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
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

    /**
     * One road-through-wall crossing, resolved to a single rigid anchor: the
     * point on the wall line nearest the road centerline ({@code centerX/Z}),
     * the road's travel direction there ({@code dirX/Z}, un-normalized), and the
     * road surface height ({@code roadY}). The whole arch is a pure function of
     * these, so it renders identically no matter which chunk stamps which half.
     */
    public record GateArch(int centerX, int centerZ, int roadY, double dirX, double dirZ) {
    }

    // --- walls ---

    /**
     * Stamps this village's wall cells that fall inside the chunk as plain wall.
     * Gate openings are cut afterwards by {@link #stampGates}, which overwrites
     * the wall it stamps here — so the two passes stay independent of order.
     */
    public static long stampWalls(WorldGenLevel region, ServerLevel serverLevel, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        placed += stampCourse(region, serverLevel, village.wallInner(), false,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        placed += stampCourse(region, serverLevel, village.wallOuter(), true,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        return placed;
    }

    private static long stampCourse(WorldGenLevel region, ServerLevel serverLevel, long[] course, boolean outer,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (long p : course) {
            int x = VillageContour.pointX(p);
            int z = VillageContour.pointZ(p);
            if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                continue;
            }
            placed += placeWallColumn(region, serverLevel, x, z, outer);
        }
        return placed;
    }

    /**
     * One wall column on the LIVE surface (villages terraform, so the noise
     * height is wrong here), digging through trees and overhangs to real
     * ground first so the wall never stands on a canopy. Over water the wall
     * rides a cobblestone causeway sunk to the bed; over a ravine it is skipped.
     */
    private static long placeWallColumn(WorldGenLevel region, ServerLevel serverLevel, int x, int z, boolean outer) {
        Ground ground = findWallGround(region, x, z);
        if (ground == null) {
            return 0; // no reachable ground (deep void/lava column): no wall here
        }
        // Ravine skip stays for DRY drops only: a level water body is bridged
        // above, so this only fires over an uncrossed carved canyon.
        if (!ground.overWater() && RoadBuilder.terrainHeight(serverLevel, x, z) - ground.baseY() > RAVINE_DROP) {
            return 0;
        }
        int baseY = ground.baseY();

        long placed = 0;
        if (ground.overWater()) {
            // Causeway footing: cobblestone from the water surface down through
            // the water to the solid bed, giving a level crossing at the water
            // line. The wall then rises from baseY (the water surface) exactly
            // as a land wall rises from its ground block.
            for (int dy = 0; dy <= MAX_GROUND_DIG; dy++) {
                BlockPos below = new BlockPos(x, baseY - dy, z);
                BlockState state = region.getBlockState(below);
                if (dy > 0 && state.getFluidState().isEmpty() && !state.isAir()) {
                    break; // reached the solid bed
                }
                region.setBlock(below, COBBLESTONE, 2);
                placed++;
            }
        } else {
            // Land foundation: bridge small hollows under the wall (unchanged).
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos below = new BlockPos(x, baseY - dy, z);
                BlockState state = region.getBlockState(below);
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    region.setBlock(below, COBBLESTONE, 2);
                    placed++;
                }
            }
        }
        // Body: plinth, weathered brick, cornice.
        for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
            region.setBlock(new BlockPos(x, baseY + dy, z), bodyBlock(x, baseY + dy, z, dy, WALL_HEIGHT), 2);
            placed++;
        }
        // Crenellation: alternating merlons on the outer course only.
        if (outer) {
            int topY = baseY + WALL_HEIGHT + 1;
            region.setBlock(new BlockPos(x, topY, z),
                    ((x + z) & 1) == 0 ? merlonBlock(x, topY, z) : STONE_BRICK_SLAB, 2);
            placed++;
        }
        return placed;
    }

    // --- gates ---

    /**
     * Collapses raw crossings into one anchor per gate: sorts by position (a
     * total order, so the result is independent of discovery order) and drops
     * any anchor that coincides with an already-kept one. Because every chunk
     * touching an arch sees the same road+wall geometry, both halves keep the
     * same representative anchor and the arch matches across the border.
     */
    public static List<GateArch> dedupeGates(List<GateArch> raw) {
        List<GateArch> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> a.centerX() != b.centerX()
                ? Integer.compare(a.centerX(), b.centerX())
                : Integer.compare(a.centerZ(), b.centerZ()));
        List<GateArch> out = new ArrayList<>();
        for (GateArch g : sorted) {
            boolean coincident = false;
            for (GateArch kept : out) {
                int dx = kept.centerX() - g.centerX();
                int dz = kept.centerZ() - g.centerZ();
                if (dx * dx + dz * dz < GATE_DEDUPE_DIST2) {
                    coincident = true;
                    break;
                }
            }
            if (!coincident) {
                out.add(g);
            }
        }
        return out;
    }

    /** Stamps every gate arch's in-chunk columns; overwrites the plain wall. */
    public static long stampGates(WorldGenLevel region, List<GateArch> gates,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (GateArch gate : gates) {
            placed += stampGate(region, gate, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }
        return placed;
    }

    /**
     * A single constant gate arch, centered on the road↔wall crossing and rigid
     * relative to the road surface Y. Five columns wide across the wall (the
     * along-wall tangent, perpendicular to the road) and {@code 2·GATE_THICK+1}
     * deep through the 2-course band; the passage arcs from {@code GATE_PEAK -
     * GATE_HALF_WIDTH} high at the jambs to {@code GATE_PEAK} at the crown, with
     * a gatehouse lintel and merlon crown over it. Everything is force-written
     * so terrain, and the plain wall stamped earlier, never distort the profile.
     */
    private static long stampGate(WorldGenLevel region, GateArch gate,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        // Along-wall (lateral) unit vector = the road direction rotated 90°.
        double len = Math.max(1.0e-6, Math.hypot(gate.dirX(), gate.dirZ()));
        double dirX = gate.dirX() / len;
        double dirZ = gate.dirZ() / len;
        double latX = -dirZ;
        double latZ = dirX;
        int roadY = gate.roadY();

        long placed = 0;
        for (int ell = -GATE_HALF_WIDTH; ell <= GATE_HALF_WIDTH; ell++) {
            int passage = GATE_PEAK - Math.abs(ell); // 3,4,5,4,3
            for (int t = -GATE_THICK; t <= GATE_THICK; t++) {
                int x = gate.centerX() + (int) Math.round(latX * ell + dirX * t);
                int z = gate.centerZ() + (int) Math.round(latZ * ell + dirZ * t);
                if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                    continue;
                }
                // Opening: clear the passage above the road.
                for (int dy = 1; dy <= passage; dy++) {
                    BlockPos pos = new BlockPos(x, roadY + dy, z);
                    if (!region.getBlockState(pos).isAir()) {
                        region.setBlock(pos, AIR, 2);
                    }
                }
                // Lintel: clean soffit ring, weathered gatehouse body above.
                for (int dy = passage + 1; dy <= GATE_BODY_TOP; dy++) {
                    BlockState state = dy == passage + 1 ? STONE_BRICKS
                            : bodyBlock(x, roadY + dy, z, dy, GATE_BODY_TOP);
                    region.setBlock(new BlockPos(x, roadY + dy, z), state, 2);
                    placed++;
                }
                // Merlon crown, matching the wall crenellation.
                int crownY = roadY + GATE_BODY_TOP + 1;
                region.setBlock(new BlockPos(x, crownY, z),
                        ((x + z) & 1) == 0 ? merlonBlock(x, crownY, z) : STONE_BRICK_SLAB, 2);
                placed++;
                // Keep the crown from being buried by an overhang.
                for (int dy = 1; dy <= CLEAR_ABOVE; dy++) {
                    BlockPos above = new BlockPos(x, crownY + dy, z);
                    if (!region.getBlockState(above).isAir()) {
                        region.setBlock(above, AIR, 2);
                    }
                }
            }
        }
        return placed;
    }

    // --- towers ---

    /** Stamps the parts of this village's guard towers that fall inside the chunk. */
    public static long stampTowers(WorldGenLevel region, ServerLevel serverLevel, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        long placed = 0;
        for (BlockPos tower : village.towerCenters()) {
            if (tower.getX() + TOWER_RADIUS < minBlockX || tower.getX() - TOWER_RADIUS > maxBlockX
                    || tower.getZ() + TOWER_RADIUS < minBlockZ || tower.getZ() - TOWER_RADIUS > maxBlockZ) {
                continue;
            }
            placed += stampTower(region, serverLevel, tower.getX(), tower.getZ(), village.center(),
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }
        return placed;
    }

    /**
     * A rigid diameter-7 cylinder straddling the wall. The whole tower shares a
     * SINGLE base Y taken from its center column (the water surface over water,
     * live ground otherwise), and every block is stamped relative to that base —
     * interiors and everything above the shell are force-cleared to air, gaps
     * below are force-filled with cobble — so uneven terrain can never shove
     * part of the structure up or down; a neighbouring chunk stamping the other
     * half recomputes the same base from the same center and lines up exactly.
     *
     * <p>A {@link Blocks#LADDER ladder} climbs the interior face of the doorway
     * wall from the floor to the deck (with the deck left open where it emerges),
     * so a player steps through the town-facing doorway and climbs to the
     * merloned parapet. Tower centers in water build on a cobble pier; centers
     * over a ravine are skipped.
     */
    private static long stampTower(WorldGenLevel region, ServerLevel serverLevel, int cx, int cz,
            BlockPos villageCenter, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        Ground ground = findWallGround(region, cx, cz);
        if (ground == null) {
            return 0;
        }
        if (!ground.overWater() && RoadBuilder.terrainHeight(serverLevel, cx, cz) - ground.baseY() > RAVINE_DROP) {
            return 0; // tower center over a ravine: skip the tower
        }
        int baseY = ground.baseY();

        // Doorway on the outer shell cell facing the town (dominant axis).
        int toCenterX = villageCenter.getX() - cx;
        int toCenterZ = villageCenter.getZ() - cz;
        int doorDx = 0;
        int doorDz = 0;
        if (Math.abs(toCenterX) >= Math.abs(toCenterZ)) {
            doorDx = toCenterX >= 0 ? TOWER_RADIUS : -TOWER_RADIUS;
        } else {
            doorDz = toCenterZ >= 0 ? TOWER_RADIUS : -TOWER_RADIUS;
        }
        Direction outward = doorDx != 0
                ? (doorDx > 0 ? Direction.EAST : Direction.WEST)
                : (doorDz > 0 ? Direction.SOUTH : Direction.NORTH);
        // The ladder clings to the doorway wall from one interior cell inward,
        // facing into the tower (so its support block is the door wall behind).
        int ladderX = cx + doorDx - Integer.signum(doorDx);
        int ladderZ = cz + doorDz - Integer.signum(doorDz);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, outward.getOpposite());

        long placed = 0;
        for (int dx = -TOWER_RADIUS; dx <= TOWER_RADIUS; dx++) {
            for (int dz = -TOWER_RADIUS; dz <= TOWER_RADIUS; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > TOWER_DISC_R2) {
                    continue; // outside the round footprint
                }
                int x = cx + dx;
                int z = cz + dz;
                if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                    continue;
                }
                boolean shell = d2 >= TOWER_SHELL_R2;

                // Footing: force cobble down through any gap (or water) to the
                // solid bed, then a flat floor block — the tower's rigid base.
                for (int dy = 1; dy <= MAX_GROUND_DIG; dy++) {
                    BlockPos below = new BlockPos(x, baseY - dy, z);
                    BlockState state = region.getBlockState(below);
                    if (state.getFluidState().isEmpty() && !state.isAir()) {
                        break;
                    }
                    region.setBlock(below, COBBLESTONE, 2);
                    placed++;
                }
                region.setBlock(new BlockPos(x, baseY, z), COBBLESTONE, 2);
                placed++;

                if (shell) {
                    boolean door = dx == doorDx && dz == 0 || dz == doorDz && dx == 0;
                    for (int dy = 1; dy <= TOWER_HEIGHT; dy++) {
                        BlockPos pos = new BlockPos(x, baseY + dy, z);
                        if (door && dy <= DOOR_HEIGHT) {
                            region.setBlock(pos, AIR, 2);
                        } else {
                            region.setBlock(pos, bodyBlock(x, baseY + dy, z, dy, TOWER_HEIGHT), 2);
                            placed++;
                        }
                    }
                    BlockPos crown = new BlockPos(x, baseY + TOWER_HEIGHT + 1, z);
                    region.setBlock(crown, ((x + z) & 1) == 0 ? merlonBlock(x, crown.getY(), z) : STONE_BRICK_SLAB, 2);
                    placed++;
                    clearAbove(region, x, crown.getY(), z);
                } else if (x == ladderX && z == ladderZ) {
                    // Ladder shaft: rungs floor-to-deck, deck left open here so
                    // the climber emerges onto the parapet.
                    for (int dy = 1; dy <= TOWER_HEIGHT; dy++) {
                        region.setBlock(new BlockPos(x, baseY + dy, z), ladder, 2);
                        placed++;
                    }
                    clearAbove(region, x, baseY + TOWER_HEIGHT, z);
                } else {
                    // Hollow interior (also digs the tower out of a hillside),
                    // capped by the deck platform.
                    for (int dy = 1; dy < TOWER_HEIGHT; dy++) {
                        region.setBlock(new BlockPos(x, baseY + dy, z), AIR, 2);
                    }
                    region.setBlock(new BlockPos(x, baseY + TOWER_HEIGHT, z), STONE_BRICKS, 2);
                    placed++;
                    clearAbove(region, x, baseY + TOWER_HEIGHT, z);
                }
            }
        }
        return placed;
    }

    /** Forces {@link #CLEAR_ABOVE} air blocks above {@code y} so nothing buries the top. */
    private static void clearAbove(WorldGenLevel region, int x, int y, int z) {
        for (int dy = 1; dy <= CLEAR_ABOVE; dy++) {
            BlockPos above = new BlockPos(x, y + dy, z);
            if (!region.getBlockState(above).isAir()) {
                region.setBlock(above, AIR, 2);
            }
        }
    }

    // --- shared helpers ---

    /** The surface a wall/tower column stands on: its base Y and whether it sits over water. */
    private record Ground(int baseY, boolean overWater) {
    }

    /**
     * Live surface under (x, z), digging through foliage and overhangs. On land
     * this is the first solid block. Over water it is the water SURFACE (the top
     * fluid block, so a causeway can ride it) — detected by hitting fluid before
     * solid. Returns null only when no ground and no water is within
     * {@link #MAX_GROUND_DIG} (e.g. a deep void/lava column).
     */
    private static Ground findWallGround(WorldGenLevel region, int x, int z) {
        int y = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, y, z);
        int waterSurfaceY = Integer.MIN_VALUE;
        for (int dig = 0; dig < MAX_GROUND_DIG; dig++) {
            BlockState state = region.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) {
                if (waterSurfaceY == Integer.MIN_VALUE) {
                    waterSurfaceY = cursor.getY(); // topmost fluid block: causeway rides here
                }
                cursor.move(0, -1, 0);
                continue; // keep digging to the bed under the water
            }
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                return waterSurfaceY != Integer.MIN_VALUE
                        ? new Ground(waterSurfaceY, true) // solid bed under a water column
                        : new Ground(cursor.getY(), false); // dry land ground
            }
            cursor.move(0, -1, 0);
        }
        // No solid within reach: still bridge if this was a (deep) water column.
        return waterSurfaceY != Integer.MIN_VALUE ? new Ground(waterSurfaceY, true) : null;
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
