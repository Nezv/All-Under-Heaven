package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPalettes.RoadPalette;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Materializes the deterministic road plan into a single chunk while that
 * chunk is being generated (worldgen threads). Only blocks inside the chunk
 * are touched, so the result is independent of generation order.
 *
 * <p>Per chunk this stamps: (a) slices of inter-village roads, (b) a curvy
 * hull road hugging each nearby village's buildings, and (c) short spokes
 * connecting the vanilla street network's outer ends to that hull.
 *
 * <p>Village geometry comes from the structure starts referenced by the
 * chunk's own already-generated data (the same lookup vanilla uses to place
 * structure pieces) — resolving it never forces new chunk generation.
 */
public final class RoadBuilder {
    public static final AtomicLong ROAD_BLOCKS_PLACED = new AtomicLong();
    public static final AtomicLong LAMPS_PLACED = new AtomicLong();

    /** Angular resolution of the village hull outline. */
    private static final int HULL_BINS = 72;
    /** Distance between the hull road's center and the outermost building corner. */
    private static final int HULL_MARGIN = 4;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Cached village geometry, keyed by packed start-chunk position. */
    private static final Map<Long, LocalVillage> VILLAGE_CACHE = new ConcurrentHashMap<>();

    /**
     * Village geometry resolved from structure references: a smoothed polar
     * outline ({@code hullRadii} per angular bin, measured from {@code center})
     * that hugs the buildings, plus the vanilla street pieces.
     */
    private record LocalVillage(BlockPos center, float[] hullRadii,
            List<BoundingBox> streetBoxes, List<BlockPos> outerNodes) {

        float radiusAt(double angle) {
            double bin = (angle / (2 * Math.PI)) * HULL_BINS;
            int i0 = Math.floorMod((int) Math.floor(bin), HULL_BINS);
            int i1 = (i0 + 1) % HULL_BINS;
            double f = bin - Math.floor(bin);
            return (float) (hullRadii[i0] * (1 - f) + hullRadii[i1] * f);
        }

        boolean contains(int x, int z, float innerShrink) {
            double dx = x - center.getX();
            double dz = z - center.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            return dist < radiusAt(angleOf(dx, dz)) - innerShrink;
        }
    }

    /** Mutable per-chunk counters (worldgen threads each get their own). */
    private static final class Tally {
        long roadBlocks;
        long lamps;
    }

    private RoadBuilder() {
    }

    public static void clearCaches() {
        VILLAGE_CACHE.clear();
    }

    /** Returns the number of road blocks placed in this chunk. */
    public static long buildForChunk(WorldGenLevel region, ServerLevel serverLevel, ChunkPos pos) {
        RoadPlanner planner = RoadPlanner.of(serverLevel);
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
            return 0;
        }

        List<LocalVillage> villages = resolveLocalVillages(region, serverLevel, pos, planner);
        Set<Long> stamped = new HashSet<>();
        Tally tally = new Tally();

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
                        stampPath(region, serverLevel, path, villages,
                                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
                    }
                });
            }
        }

        // (b) Hull roads hugging each village and (c) street spokes.
        for (LocalVillage village : villages) {
            stampHull(region, serverLevel, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            stampSpokes(region, serverLevel, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        }

        ROAD_BLOCKS_PLACED.addAndGet(tally.roadBlocks);
        LAMPS_PLACED.addAndGet(tally.lamps);
        return tally.roadBlocks;
    }

    /**
     * Villages whose structure starts are referenced around this chunk. Uses
     * the region-scoped structure manager during worldgen — the exact lookup
     * vanilla uses to place structure pieces, guaranteed non-generating.
     */
    private static List<LocalVillage> resolveLocalVillages(WorldGenLevel region, ServerLevel serverLevel,
            ChunkPos pos, RoadPlanner planner) {
        StructureManager structureManager = region instanceof WorldGenRegion worldGenRegion
                ? serverLevel.structureManager().forWorldGenRegion(worldGenRegion)
                : serverLevel.structureManager();
        List<LocalVillage> villages = new ArrayList<>();
        for (StructureStart start : structureManager.startsForStructure(pos, planner.villageStructures()::contains)) {
            if (start.isValid()) {
                villages.add(VILLAGE_CACHE.computeIfAbsent(start.getChunkPos().pack(), key -> buildVillage(start)));
            }
        }
        return villages;
    }

    private static LocalVillage buildVillage(StructureStart start) {
        BoundingBox bounds = start.getBoundingBox();
        BlockPos center = bounds.getCenter();

        List<BoundingBox> streets = new ArrayList<>();
        List<BoundingBox> pieceBoxes = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            pieceBoxes.add(piece.getBoundingBox());
            if (piece instanceof PoolElementStructurePiece pool && pool.getElement().toString().contains("streets")) {
                streets.add(piece.getBoundingBox());
            }
        }

        // Polar outline: farthest building corner per angular bin, plus margin.
        float[] radii = new float[HULL_BINS];
        for (BoundingBox box : pieceBoxes) {
            int[][] corners = {
                    {box.minX(), box.minZ()}, {box.minX(), box.maxZ()},
                    {box.maxX(), box.minZ()}, {box.maxX(), box.maxZ()},
                    {(box.minX() + box.maxX()) / 2, box.minZ()}, {(box.minX() + box.maxX()) / 2, box.maxZ()},
                    {box.minX(), (box.minZ() + box.maxZ()) / 2}, {box.maxX(), (box.minZ() + box.maxZ()) / 2}
            };
            for (int[] corner : corners) {
                double dx = corner[0] - center.getX();
                double dz = corner[1] - center.getZ();
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                int bin = (int) Math.floor(angleOf(dx, dz) / (2 * Math.PI) * HULL_BINS) % HULL_BINS;
                // A corner shades its own bin and the two adjacent ones so thin
                // buildings can't poke through between bins.
                for (int d = -1; d <= 1; d++) {
                    int b = Math.floorMod(bin + d, HULL_BINS);
                    radii[b] = Math.max(radii[b], dist);
                }
            }
        }
        // Fill bins no corner reached (interpolate between neighbors, circular).
        for (int i = 0; i < HULL_BINS; i++) {
            if (radii[i] == 0) {
                int prev = i;
                while (radii[Math.floorMod(prev - 1, HULL_BINS)] == 0 && prev > i - HULL_BINS) {
                    prev--;
                }
                int next = i;
                while (radii[Math.floorMod(next + 1, HULL_BINS)] == 0 && next < i + HULL_BINS) {
                    next++;
                }
                float a = radii[Math.floorMod(prev - 1, HULL_BINS)];
                float b = radii[Math.floorMod(next + 1, HULL_BINS)];
                radii[i] = Math.max(8, (a + b) / 2);
            }
        }
        // Margin + smoothing passes -> compact, curvy outline 1-2 blocks off the walls.
        for (int i = 0; i < HULL_BINS; i++) {
            radii[i] += HULL_MARGIN;
        }
        for (int pass = 0; pass < 3; pass++) {
            float[] smoothed = new float[HULL_BINS];
            for (int i = 0; i < HULL_BINS; i++) {
                smoothed[i] = (radii[Math.floorMod(i - 1, HULL_BINS)] + 2 * radii[i] + radii[(i + 1) % HULL_BINS]) / 4;
            }
            radii = smoothed;
        }

        // Outer street nodes: street ends nearest the outline, spread apart.
        List<BlockPos> outer = new ArrayList<>();
        streets.stream()
                .map(BoundingBox::getCenter)
                .sorted((p1, p2) -> Integer.compare(distSqr2d(p2, center), distSqr2d(p1, center)))
                .forEach(p -> {
                    if (outer.size() < 6 && outer.stream().allMatch(o -> distSqr2d(o, p) > 24 * 24)) {
                        outer.add(p);
                    }
                });
        return new LocalVillage(center, radii, List.copyOf(streets), List.copyOf(outer));
    }

    private static void stampPath(WorldGenLevel region, ServerLevel serverLevel, RoadPath path,
            List<LocalVillage> villages,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
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
                stampCell3Wide(region, x, y, z, wet, villages, null,
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            }
        }
        for (RoadPath.Lamp lamp : path.lamps()) {
            if (lamp.x() >= minBlockX && lamp.x() <= maxBlockX && lamp.z() >= minBlockZ && lamp.z() <= maxBlockZ
                    && !insideAnyVillage(lamp.x(), lamp.z(), villages, 0)) {
                placeLamp(region, serverLevel, lamp.x(), lamp.z(), tally);
            }
        }
    }

    /** Walks the polar outline and stamps the 3-wide hull road, with a few lamps. */
    private static void stampHull(WorldGenLevel region, ServerLevel serverLevel, LocalVillage village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        // Quick reject: hull's max radius vs chunk box.
        float maxRadius = 0;
        for (float r : village.hullRadii()) {
            maxRadius = Math.max(maxRadius, r);
        }
        int cx = village.center().getX();
        int cz = village.center().getZ();
        if (cx + maxRadius + 2 < minBlockX || cx - maxRadius - 2 > maxBlockX
                || cz + maxRadius + 2 < minBlockZ || cz - maxRadius - 2 > maxBlockZ) {
            return;
        }

        for (int bin = 0; bin < HULL_BINS; bin++) {
            double a0 = bin * 2 * Math.PI / HULL_BINS;
            double a1 = (bin + 1) * 2 * Math.PI / HULL_BINS;
            float r0 = village.hullRadii()[bin];
            float r1 = village.hullRadii()[(bin + 1) % HULL_BINS];
            double x0 = cx + Math.cos(a0) * r0;
            double z0 = cz + Math.sin(a0) * r0;
            double x1 = cx + Math.cos(a1) * r1;
            double z1 = cz + Math.sin(a1) * r1;
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0)));
            for (int s = 0; s < steps; s++) {
                double f = (double) s / steps;
                int x = (int) Math.round(x0 + (x1 - x0) * f);
                int z = (int) Math.round(z0 + (z1 - z0) * f);
                if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                    continue;
                }
                int y = terrainHeight(serverLevel, x, z);
                stampCell3Wide(region, x, y, z, false, null, village.streetBoxes(),
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            }
            // A lamp every 12 bins (~60 degrees), just outside the hull road.
            if (Config.ROAD_LAMPS.getAsBoolean() && bin % 12 == 0) {
                int lampX = (int) Math.round(cx + Math.cos(a0) * (r0 + 3));
                int lampZ = (int) Math.round(cz + Math.sin(a0) * (r0 + 3));
                if (lampX >= minBlockX && lampX <= maxBlockX && lampZ >= minBlockZ && lampZ <= maxBlockZ) {
                    placeLamp(region, serverLevel, lampX, lampZ, tally);
                }
            }
        }
    }

    /** Connects the vanilla street ends outward to the hull road. */
    private static void stampSpokes(WorldGenLevel region, ServerLevel serverLevel, LocalVillage village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        int cx = village.center().getX();
        int cz = village.center().getZ();
        for (BlockPos outer : village.outerNodes()) {
            double dx = outer.getX() - cx;
            double dz = outer.getZ() - cz;
            double dist = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
            double targetR = village.radiusAt(angleOf(dx, dz)) + 1;
            int targetX = (int) Math.round(cx + dx / dist * targetR);
            int targetZ = (int) Math.round(cz + dz / dist * targetR);

            int steps = Math.max(1, (int) Math.ceil(Math.hypot(targetX - outer.getX(), targetZ - outer.getZ())));
            for (int s = 0; s <= steps; s++) {
                int x = (int) Math.round(outer.getX() + (targetX - outer.getX()) * (double) s / steps);
                int z = (int) Math.round(outer.getZ() + (targetZ - outer.getZ()) * (double) s / steps);
                if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                    continue;
                }
                int y = terrainHeight(serverLevel, x, z);
                stampCell3Wide(region, x, y, z, false, null, village.streetBoxes(),
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            }
        }
    }

    /**
     * Places a 3-wide road patch centered on (x, z) at the planned height.
     * Cells inside {@code interiorClip} village hulls are skipped so
     * inter-village roads hand over to the hull; cells inside {@code boxClip}
     * boxes are skipped so hull/spokes never pave over the vanilla streets.
     */
    private static void stampCell3Wide(WorldGenLevel region, int x, int plannedY, int z, boolean wet,
            List<LocalVillage> interiorClip, List<BoundingBox> boxClip,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
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
                if (interiorClip != null && insideAnyVillage(bx, bz, interiorClip, 1.5f)) {
                    continue;
                }
                if (boxClip != null && insideAnyBox(bx, bz, boxClip)) {
                    continue;
                }
                placeRoadColumn(region, bx, plannedY, bz, wet, tally);
            }
        }
    }

    private static boolean insideAnyVillage(int x, int z, List<LocalVillage> villages, float innerShrink) {
        for (LocalVillage village : villages) {
            if (village.contains(x, z, innerShrink)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideAnyBox(int x, int z, List<BoundingBox> boxes) {
        for (BoundingBox b : boxes) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static void placeRoadColumn(WorldGenLevel region, int x, int y, int z, boolean wet, Tally tally) {
        BlockPos top = new BlockPos(x, y, z);
        RoadPalette palette = RoadPalettes.at(region, top);
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos above = top.above(dy);
            if (!region.getBlockState(above).isAir()) {
                region.setBlock(above, AIR, 2);
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos below = top.below(dy);
            BlockState state = region.getBlockState(below);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                region.setBlock(below, palette.fill(), 2);
            }
        }
        region.setBlock(top, RoadPalettes.surfaceAt(palette, x, z, wet), 2);
        tally.roadBlocks++;
    }

    private static void placeLamp(WorldGenLevel region, ServerLevel serverLevel, int x, int z, Tally tally) {
        int groundY = terrainHeight(serverLevel, x, z);
        BlockPos ground = new BlockPos(x, groundY, z);
        BlockState groundState = region.getBlockState(ground);
        if (groundState.isAir() || !groundState.getFluidState().isEmpty()) {
            return;
        }
        RoadPalette palette = RoadPalettes.at(region, ground);
        region.setBlock(ground.above(1), palette.post(), 2);
        region.setBlock(ground.above(2), palette.post(), 2);
        region.setBlock(ground.above(3), Blocks.LANTERN.defaultBlockState(), 2);
        tally.lamps++;
    }

    /**
     * Pre-feature terrain height from the generator noise — deterministic,
     * available for any position, and immune to trees placed earlier in the
     * decoration pipeline.
     */
    private static int terrainHeight(ServerLevel serverLevel, int x, int z) {
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        RandomState randomState = serverLevel.getChunkSource().randomState();
        return generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, randomState);
    }

    private static double angleOf(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        return angle < 0 ? angle + 2 * Math.PI : angle;
    }

    private static int distSqr2d(BlockPos p1, BlockPos p2) {
        int dx = p1.getX() - p2.getX();
        int dz = p1.getZ() - p2.getZ();
        return dx * dx + dz * dz;
    }
}
