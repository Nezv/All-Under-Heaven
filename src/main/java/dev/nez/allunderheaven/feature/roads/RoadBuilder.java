package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPalettes.RoadPalette;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Materializes the deterministic road plan into a single chunk when that chunk
 * is first generated. Only blocks inside the chunk are touched, so the result
 * is independent of chunk generation order.
 *
 * <p>Per chunk this stamps: (a) slices of inter-village roads, (b) the ring
 * road "wrapping" each nearby village (village bounds + margin), and (c) short
 * spokes connecting the vanilla street network's outer nodes to the ring.
 *
 * <p>Exact village geometry (bounds, street pieces) comes from the structure
 * starts referenced by this chunk's own already-generated data — resolving it
 * never forces new chunk generation.
 */
public final class RoadBuilder {
    public static final AtomicLong ROAD_BLOCKS_PLACED = new AtomicLong();
    public static final AtomicLong LAMPS_PLACED = new AtomicLong();

    /** Ring road distance outside the village bounding box. */
    private static final int RING_MARGIN = 8;
    /** Inter-village roads stop once they are this close to the village (the ring takes over). */
    private static final int INTERIOR_MARGIN = RING_MARGIN - 1;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Village geometry resolved from this chunk's structure references. */
    private record LocalVillage(BoundingBox bounds, List<BoundingBox> streetBoxes, List<BlockPos> outerNodes) {
    }

    private RoadBuilder() {
    }

    public static void buildForChunk(ServerLevel level, LevelChunk chunk) {
        RoadPlanner planner = RoadPlanner.of(level);
        ChunkPos pos = chunk.getPos();
        int minBlockX = pos.x() << 4;
        int minBlockZ = pos.z() << 4;
        int maxBlockX = minBlockX + 15;
        int maxBlockZ = minBlockZ + 15;

        int cellBlocks = planner.cellSizeBlocks();
        int cellX = Math.floorDiv(minBlockX + 8, cellBlocks);
        int cellZ = Math.floorDiv(minBlockZ + 8, cellBlocks);
        int searchCells = Math.floorDiv(Config.ROAD_MAX_LENGTH_BLOCKS.getAsInt(), cellBlocks) + 2;

        List<VillageNode> nodes = planner.nodesAround(cellX, cellZ, searchCells);
        if (nodes.isEmpty()) {
            return;
        }

        List<LocalVillage> localVillages = resolveLocalVillages(level, pos, planner);
        Set<Long> stamped = new HashSet<>();

        // (a) Inter-village roads.
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                VillageNode a = nodes.get(i);
                VillageNode b = nodes.get(j);
                if (!planner.edgeKept(a, b)) {
                    continue;
                }
                planner.path(a, b).ifPresent(path -> {
                    if (path.bounds().grow(4).intersects(minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
                        stampPath(level, path, localVillages, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
                    }
                });
            }
        }

        // (b) Ring roads and (c) street spokes for villages whose geometry is local.
        for (LocalVillage village : localVillages) {
            BoundingBox ring = ringBox(village.bounds());
            if (ring.maxX() + 2 < minBlockX || ring.minX() - 2 > maxBlockX
                    || ring.maxZ() + 2 < minBlockZ || ring.minZ() - 2 > maxBlockZ) {
                continue;
            }
            stampRing(level, ring, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
            stampSpokes(level, village, ring, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
        }
    }

    /**
     * Villages whose structure starts are referenced by chunks in this area.
     * The event chunk is FULL, so its references (and the starts of every
     * village within reference range) are already computed — no generation.
     */
    private static List<LocalVillage> resolveLocalVillages(ServerLevel level, ChunkPos pos, RoadPlanner planner) {
        List<LocalVillage> villages = new ArrayList<>();
        for (StructureStart start : level.structureManager()
                .startsForStructure(pos, planner.villageStructures()::contains)) {
            if (!start.isValid()) {
                continue;
            }
            BoundingBox bounds = start.getBoundingBox();
            BlockPos center = bounds.getCenter();
            List<BoundingBox> streets = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece pool && pool.getElement().toString().contains("streets")) {
                    streets.add(piece.getBoundingBox());
                }
            }
            List<BlockPos> outer = new ArrayList<>();
            streets.stream()
                    .map(BoundingBox::getCenter)
                    .sorted((p1, p2) -> Integer.compare(distSqr2d(p2, center), distSqr2d(p1, center)))
                    .forEach(p -> {
                        if (outer.size() < 6 && outer.stream().allMatch(o -> distSqr2d(o, p) > 24 * 24)) {
                            outer.add(p);
                        }
                    });
            villages.add(new LocalVillage(bounds, List.copyOf(streets), List.copyOf(outer)));
        }
        return villages;
    }

    private static BoundingBox ringBox(BoundingBox b) {
        return new BoundingBox(b.minX() - RING_MARGIN, b.minY(), b.minZ() - RING_MARGIN,
                b.maxX() + RING_MARGIN, b.maxY(), b.maxZ() + RING_MARGIN);
    }

    private static void stampPath(ServerLevel level, RoadPath path, List<LocalVillage> villages,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped) {
        int n = path.sampleCount();
        for (int i = 1; i < n; i++) {
            double segLen = Math.hypot(path.xs()[i] - path.xs()[i - 1], path.zs()[i] - path.zs()[i - 1]);
            int steps = Math.max(1, (int) Math.ceil(segLen));
            for (int s = 0; s <= steps; s++) {
                double f = (double) s / steps;
                int x = (int) Math.round(path.xs()[i - 1] + (path.xs()[i] - path.xs()[i - 1]) * f);
                int z = (int) Math.round(path.zs()[i - 1] + (path.zs()[i] - path.zs()[i - 1]) * f);
                if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                    continue;
                }
                int y = Math.round(path.ys()[i - 1] + (path.ys()[i] - path.ys()[i - 1]) * (float) f);
                boolean wet = path.wet()[i - 1] || path.wet()[i];
                stampCell3Wide(level, x, y, z, wet, villages, null,
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
            }
        }
        for (RoadPath.Lamp lamp : path.lamps()) {
            if (lamp.x() >= minBlockX && lamp.x() <= maxBlockX && lamp.z() >= minBlockZ && lamp.z() <= maxBlockZ
                    && !insideVillageInterior(lamp.x(), lamp.z(), villages)) {
                placeLamp(level, lamp.x(), lamp.z());
            }
        }
    }

    private static void stampRing(ServerLevel level, BoundingBox ring,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped) {
        for (int x = ring.minX(); x <= ring.maxX(); x++) {
            stampCell3Wide(level, x, Integer.MIN_VALUE, ring.minZ(), false, null, null,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
            stampCell3Wide(level, x, Integer.MIN_VALUE, ring.maxZ(), false, null, null,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
        }
        for (int z = ring.minZ(); z <= ring.maxZ(); z++) {
            stampCell3Wide(level, ring.minX(), Integer.MIN_VALUE, z, false, null, null,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
            stampCell3Wide(level, ring.maxX(), Integer.MIN_VALUE, z, false, null, null,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
        }
        if (Config.ROAD_LAMPS.getAsBoolean()) {
            int[][] corners = {
                    {ring.minX(), ring.minZ()}, {ring.minX(), ring.maxZ()},
                    {ring.maxX(), ring.minZ()}, {ring.maxX(), ring.maxZ()}
            };
            for (int[] corner : corners) {
                if (corner[0] >= minBlockX && corner[0] <= maxBlockX && corner[1] >= minBlockZ && corner[1] <= maxBlockZ) {
                    placeLamp(level, corner[0], corner[1]);
                }
            }
        }
    }

    private static void stampSpokes(ServerLevel level, LocalVillage village, BoundingBox ring,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped) {
        for (BlockPos outer : village.outerNodes()) {
            // Nearest point on the ring rectangle.
            int targetX = clamp(outer.getX(), ring.minX(), ring.maxX());
            int targetZ = clamp(outer.getZ(), ring.minZ(), ring.maxZ());
            int dxMin = Math.abs(outer.getX() - ring.minX());
            int dxMax = Math.abs(outer.getX() - ring.maxX());
            int dzMin = Math.abs(outer.getZ() - ring.minZ());
            int dzMax = Math.abs(outer.getZ() - ring.maxZ());
            int best = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));
            if (best == dxMin) {
                targetX = ring.minX();
            } else if (best == dxMax) {
                targetX = ring.maxX();
            } else if (best == dzMin) {
                targetZ = ring.minZ();
            } else {
                targetZ = ring.maxZ();
            }

            int steps = Math.max(1, (int) Math.ceil(Math.hypot(targetX - outer.getX(), targetZ - outer.getZ())));
            for (int s = 0; s <= steps; s++) {
                int x = (int) Math.round(outer.getX() + (targetX - outer.getX()) * (double) s / steps);
                int z = (int) Math.round(outer.getZ() + (targetZ - outer.getZ()) * (double) s / steps);
                stampCell3Wide(level, x, Integer.MIN_VALUE, z, false, null, village.streetBoxes(),
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped);
            }
        }
    }

    /**
     * Places a 3-wide road patch centered on (x, z). {@code plannedY} of
     * {@code Integer.MIN_VALUE} means "follow the live terrain" (rings/spokes).
     * Cells inside {@code interiorClip} village interiors are skipped so
     * inter-village roads hand over to the ring; cells inside {@code boxClip}
     * boxes are skipped so spokes never pave over the vanilla streets.
     */
    private static void stampCell3Wide(ServerLevel level, int x, int plannedY, int z, boolean wet,
            List<LocalVillage> interiorClip, List<BoundingBox> boxClip,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int bx = x + dx;
                int bz = z + dz;
                if (bx < minBlockX || bx > maxBlockX || bz < minBlockZ || bz > maxBlockZ) {
                    continue;
                }
                long key = (bx & 0xFFFFFFFFL) | ((long) bz << 32);
                if (!stamped.add(key)) {
                    continue;
                }
                if (interiorClip != null && insideVillageInterior(bx, bz, interiorClip)) {
                    continue;
                }
                if (boxClip != null && insideAnyBox(bx, bz, boxClip)) {
                    continue;
                }
                placeRoadColumn(level, bx, plannedY, bz, wet);
            }
        }
    }

    private static boolean insideAnyBox(int x, int z, List<BoundingBox> boxes) {
        for (BoundingBox b : boxes) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideVillageInterior(int x, int z, List<LocalVillage> villages) {
        for (LocalVillage village : villages) {
            BoundingBox b = village.bounds();
            if (x >= b.minX() - INTERIOR_MARGIN && x <= b.maxX() + INTERIOR_MARGIN
                    && z >= b.minZ() - INTERIOR_MARGIN && z <= b.maxZ() + INTERIOR_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static void placeRoadColumn(ServerLevel level, int x, int plannedY, int z, boolean wet) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int y = plannedY == Integer.MIN_VALUE ? surfaceY : plannedY;
        if (Math.abs(y - surfaceY) > 6) {
            y = surfaceY; // plan/terrain mismatch (carver caves, cliffs) — follow terrain
        }
        BlockPos top = new BlockPos(x, y, z);

        RoadPalette palette = RoadPalettes.at(level, top);
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos above = top.above(dy);
            if (!level.getBlockState(above).isAir()) {
                level.setBlock(above, AIR, 3);
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos below = top.below(dy);
            BlockState state = level.getBlockState(below);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                level.setBlock(below, palette.fill(), 3);
            }
        }
        level.setBlock(top, RoadPalettes.surfaceAt(palette, x, z, wet), 3);
        ROAD_BLOCKS_PLACED.incrementAndGet();
    }

    private static void placeLamp(ServerLevel level, int x, int z) {
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos ground = new BlockPos(x, groundY, z);
        BlockState groundState = level.getBlockState(ground);
        if (groundState.isAir() || !groundState.getFluidState().isEmpty()) {
            return;
        }
        RoadPalette palette = RoadPalettes.at(level, ground);
        level.setBlock(ground.above(1), palette.post(), 3);
        level.setBlock(ground.above(2), palette.post(), 3);
        level.setBlock(ground.above(3), Blocks.LANTERN.defaultBlockState(), 3);
        LAMPS_PLACED.incrementAndGet();
    }

    private static int distSqr2d(BlockPos p1, BlockPos p2) {
        int dx = p1.getX() - p2.getX();
        int dz = p1.getZ() - p2.getZ();
        return dx * dx + dz * dz;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
