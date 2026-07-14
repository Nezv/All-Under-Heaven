package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPalettes.RoadPalette;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import dev.nez.allunderheaven.feature.villages.VillageTier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Materializes the deterministic road plan into a single chunk while that
 * chunk is being generated (worldgen threads). Only blocks inside the chunk
 * are touched, so the result is independent of generation order.
 *
 * <p>Per chunk this stamps: (a) slices of inter-village roads, clipped at the
 * village wrap and re-anchored onto the village's natural outer street ends,
 * and (b) the wrap itself — a concave contour hugging the buildings (see
 * {@link VillageContour}), with stretches that coincide with vanilla streets
 * left to the streets themselves.
 */
public final class RoadBuilder {
    public static final AtomicLong ROAD_BLOCKS_PLACED = new AtomicLong();
    public static final AtomicLong LAMPS_PLACED = new AtomicLong();
    public static final AtomicLong WALL_BLOCKS_PLACED = new AtomicLong();

    /** Max bearing difference (radians) for re-anchoring a road onto an outer street end. */
    private static final double CONNECTOR_MAX_BEARING = Math.toRadians(75);
    /** How far a path endpoint may sit from a village center and still belong to it. */
    private static final int ENDPOINT_MATCH_DISTANCE = 48;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Cached village geometry, keyed by packed start-chunk position. */
    private static final Map<Long, VillageContour> VILLAGE_CACHE = new ConcurrentHashMap<>();

    /** Mutable per-chunk counters (worldgen threads each get their own). */
    private static final class Tally {
        long roadBlocks;
        long lamps;
        long wallBlocks;
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

        List<VillageContour> villages = resolveLocalVillages(region, serverLevel, pos, planner);
        Set<Long> stamped = new HashSet<>();
        Map<Long, int[]> gates = new HashMap<>();
        Tally tally = new Tally();

        // (a) Inter-village roads (with wrap clipping + outer-node connectors).
        // Wall cells crossed by a road centerline are collected as gate cells
        // along the way, so the wall pass can arch over instead of sealing.
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                VillageNode a = nodes.get(i);
                VillageNode b = nodes.get(j);
                if (!planner.edgeKept(a, b)) {
                    continue;
                }
                planner.path(a, b).ifPresent(path -> {
                    if (path.bounds().grow(8).intersects(minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
                        stampPath(region, serverLevel, path, villages, gates,
                                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
                    }
                });
            }
        }

        // (b) The building-hugging wrap of each nearby village. Must run
        // before the tier-2 street stoning: the wrap yields to vanilla
        // streets by detecting their dirt-path surface.
        for (VillageContour village : villages) {
            stampWrap(region, serverLevel, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        }

        // (c) Tier-2 towns: vanilla dirt-path streets become stone.
        for (VillageContour village : villages) {
            if (village.tier() == VillageTier.TIER2) {
                stoneStreets(region, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, tally);
            }
        }

        // (d) Tier-2 towns: the city wall around the wrap (with gate arches
        // where roads cross), plus the guard towers.
        for (VillageContour village : villages) {
            tally.wallBlocks += WallBuilder.stampWalls(region, serverLevel, village, gates,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ);
            tally.wallBlocks += WallBuilder.stampTowers(region, serverLevel, village,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }

        ROAD_BLOCKS_PLACED.addAndGet(tally.roadBlocks);
        LAMPS_PLACED.addAndGet(tally.lamps);
        WALL_BLOCKS_PLACED.addAndGet(tally.wallBlocks);
        return tally.roadBlocks;
    }

    /**
     * Replaces the vanilla street surface (dirt path) with the stone-city mix
     * inside a tier-2 town's structure bounds. Runs after the wrap stamping so
     * street detection still sees the original paths.
     */
    private static void stoneStreets(WorldGenLevel region, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Tally tally) {
        BoundingBox bounds = village.structureBounds();
        int x0 = Math.max(bounds.minX() - 4, minBlockX);
        int x1 = Math.min(bounds.maxX() + 4, maxBlockX);
        int z0 = Math.max(bounds.minZ() - 4, minBlockZ);
        int z1 = Math.min(bounds.maxZ() + 4, maxBlockZ);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int y = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos pos = new BlockPos(x, y, z);
                if (region.getBlockState(pos).is(Blocks.DIRT_PATH)) {
                    region.setBlock(pos, RoadPalettes.stoneSurfaceAt(x, z), 2);
                    tally.roadBlocks++;
                }
            }
        }
    }

    /**
     * Villages whose structure starts are referenced around this chunk. Uses
     * the region-scoped structure manager during worldgen — the exact lookup
     * vanilla uses to place structure pieces, guaranteed non-generating.
     *
     * <p>Scans the 3x3 chunk neighborhood: structure references only exist
     * for chunks the structure's bounding box intersects, but the tier-2 wall
     * band can poke a few blocks past that box into an unreferenced chunk —
     * whose neighbor toward the village always holds the reference.
     */
    private static List<VillageContour> resolveLocalVillages(WorldGenLevel region, ServerLevel serverLevel,
            ChunkPos pos, RoadPlanner planner) {
        StructureManager structureManager = region instanceof WorldGenRegion worldGenRegion
                ? serverLevel.structureManager().forWorldGenRegion(worldGenRegion)
                : serverLevel.structureManager();
        List<VillageContour> villages = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos probe = new ChunkPos(pos.x() + dx, pos.z() + dz);
                for (StructureStart start : structureManager.startsForStructure(probe,
                        planner.villageStructures()::contains)) {
                    if (!start.isValid() || !seen.add(start.getChunkPos().pack())) {
                        continue;
                    }
                    villages.add(VILLAGE_CACHE.computeIfAbsent(start.getChunkPos().pack(), key -> {
                        VillageTier tier = Config.ENABLE_CITY_TIERS.getAsBoolean()
                                ? VillageTier.of(serverLevel.getSeed(), start.getChunkPos())
                                : VillageTier.TIER1;
                        VillageContour contour = VillageContour.of(start,
                                Config.ROAD_WRAP_MARGIN_BLOCKS.getAsInt(), tier, serverLevel.getSeed());
                        if (Config.ROADS_DEBUG_LOG.getAsBoolean()) {
                            AllUnderHeaven.LOGGER.info(
                                    "[All Under Heaven] Roads debug: wrap for {} village at {} — {} loop points, {} corners, {} outer nodes, {} wall cells, {} towers",
                                    tier, contour.center(), contour.loopPoints().length,
                                    contour.cornerIndices().length, contour.outerNodes().size(),
                                    contour.wallInner().length + contour.wallOuter().length,
                                    contour.towerCenters().size());
                        }
                        return contour;
                    }));
                }
            }
        }
        return villages;
    }

    private static void stampPath(WorldGenLevel region, ServerLevel serverLevel, RoadPath path,
            List<VillageContour> villages, Map<Long, int[]> gates,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        int n = path.sampleCount();
        for (int i = 1; i < n; i++) {
            double segLen = Math.hypot(path.xs()[i] - path.xs()[i - 1], path.zs()[i] - path.zs()[i - 1]);
            int steps = Math.max(1, (int) Math.ceil(segLen));
            for (int s = 0; s <= steps; s++) {
                double f = (double) s / steps;
                int x = (int) Math.round(path.xs()[i - 1] + (path.xs()[i] - path.xs()[i - 1]) * f);
                int z = (int) Math.round(path.zs()[i - 1] + (path.zs()[i] - path.zs()[i - 1]) * f);
                if (x + 2 < minBlockX || x - 2 > maxBlockX || z + 2 < minBlockZ || z - 2 > maxBlockZ) {
                    continue;
                }
                int y = Math.round(path.ys()[i - 1] + (path.ys()[i] - path.ys()[i - 1]) * (float) f);
                collectGateCells(villages, gates, x, y, z);
                if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                    continue;
                }
                boolean wet = path.wet()[i - 1] || path.wet()[i];
                stampCell3Wide(region, x, y, z, wet, villages, null,
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            }
        }
        for (RoadPath.Lamp lamp : path.lamps()) {
            if (lamp.x() >= minBlockX && lamp.x() <= maxBlockX && lamp.z() >= minBlockZ && lamp.z() <= maxBlockZ
                    && !insideAnyVillage(lamp.x(), lamp.z(), villages)) {
                placeLamp(region, lamp.x(), lamp.z(), tally);
            }
        }
        // Re-anchor each end of the road onto the village's natural outer
        // street end (the blue-to-contour derivation).
        stampConnector(region, serverLevel, path, villages, gates, true,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        stampConnector(region, serverLevel, path, villages, gates, false,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
    }

    /**
     * Where a road centerline sample passes within 2 blocks of a tier-2 wall
     * cell, mark that cell as a gate cell: {distance from the centerline,
     * road surface Y}. The road is 3 wide, so distance 0-1 lies over the road
     * itself and 2 is the one-block padding — a 5-wide opening in total,
     * matching the arch profile in {@link WallBuilder}.
     */
    private static void collectGateCells(List<VillageContour> villages, Map<Long, int[]> gates, int x, int y, int z) {
        for (VillageContour village : villages) {
            if (!village.hasWall()) {
                continue;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (village.isWallCell(x + dx, z + dz)) {
                        int dist = Math.max(Math.abs(dx), Math.abs(dz));
                        gates.merge(VillageContour.pack(x + dx, z + dz), new int[]{dist, y},
                                (a, b) -> b[0] < a[0] ? b : a);
                    }
                }
            }
        }
    }

    /**
     * Where a road meets its endpoint village, route the last stretch to the
     * best-facing vanilla street end instead of stopping dead at the wrap.
     * Deterministic: depends only on the path and the village geometry.
     */
    private static void stampConnector(WorldGenLevel region, ServerLevel serverLevel, RoadPath path,
            List<VillageContour> villages, Map<Long, int[]> gates, boolean fromStart,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        int n = path.sampleCount();
        int endX = fromStart ? path.xs()[0] : path.xs()[n - 1];
        int endZ = fromStart ? path.zs()[0] : path.zs()[n - 1];

        VillageContour village = null;
        for (VillageContour candidate : villages) {
            int dx = candidate.center().getX() - endX;
            int dz = candidate.center().getZ() - endZ;
            if (dx * dx + dz * dz <= ENDPOINT_MATCH_DISTANCE * ENDPOINT_MATCH_DISTANCE) {
                village = candidate;
                break;
            }
        }
        if (village == null || village.outerNodes().isEmpty()) {
            return;
        }

        // Exit point: first path sample outside the wrap, walking outward.
        int exitX = endX;
        int exitZ = endZ;
        boolean found = false;
        for (int k = 0; k < n; k++) {
            int i = fromStart ? k : n - 1 - k;
            if (!village.contains(path.xs()[i], path.zs()[i])) {
                exitX = path.xs()[i];
                exitZ = path.zs()[i];
                found = true;
                break;
            }
        }
        if (!found) {
            return;
        }

        double exitBearing = Math.atan2(exitZ - village.center().getZ(), exitX - village.center().getX());
        BlockPos best = null;
        double bestDiff = CONNECTOR_MAX_BEARING;
        for (BlockPos node : village.outerNodes()) {
            double bearing = Math.atan2(node.getZ() - village.center().getZ(), node.getX() - village.center().getX());
            double diff = Math.abs(Math.atan2(Math.sin(bearing - exitBearing), Math.cos(bearing - exitBearing)));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = node;
            }
        }
        if (best == null) {
            return; // no street end faces this road; it terminates on the wrap
        }

        int steps = Math.max(1, (int) Math.ceil(Math.hypot(best.getX() - exitX, best.getZ() - exitZ)));
        if (steps < 3) {
            return; // the road already arrives at the street end
        }
        RoadPalette palette = village.tier() == VillageTier.TIER2 ? RoadPalettes.STONE_CITY : null;
        for (int s = 0; s <= steps; s++) {
            int x = (int) Math.round(exitX + (best.getX() - exitX) * (double) s / steps);
            int z = (int) Math.round(exitZ + (best.getZ() - exitZ) * (double) s / steps);
            if (x + 2 < minBlockX || x - 2 > maxBlockX || z + 2 < minBlockZ || z - 2 > maxBlockZ) {
                continue;
            }
            int y = terrainHeight(serverLevel, x, z);
            collectGateCells(villages, gates, x, y, z);
            if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                continue;
            }
            stampCell3Wide(region, x, y, z, false, null, palette,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        }
    }

    /**
     * Walks the precomputed wrap loop. Stretches where the vanilla street
     * surface already runs (detected from actual placed path blocks — street
     * pieces are laid down before this decoration step) are left to the
     * street, so the wrap and the streets merge into one network.
     */
    private static void stampWrap(WorldGenLevel region, ServerLevel serverLevel, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        long[] loop = village.loopPoints();
        RoadPalette palette = village.tier() == VillageTier.TIER2 ? RoadPalettes.STONE_CITY : null;
        for (int i = 0; i < loop.length; i++) {
            int x = VillageContour.pointX(loop[i]);
            int z = VillageContour.pointZ(loop[i]);
            if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                continue;
            }
            if (isStreetHere(region, x, z)) {
                continue; // the vanilla street carries the wrap here
            }
            int y = terrainHeight(serverLevel, x, z);
            stampCell3Wide(region, x, y, z, false, null, palette,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        }

        if (Config.ROAD_LAMPS.getAsBoolean()) {
            for (int corner : village.cornerIndices()) {
                int x = VillageContour.pointX(loop[corner]);
                int z = VillageContour.pointZ(loop[corner]);
                if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) {
                    continue;
                }
                if (isStreetHere(region, x, z)) {
                    continue;
                }
                // Outward normal from the loop tangent, pointed away from center.
                int before = Math.floorMod(corner - 3, loop.length);
                int after = (corner + 3) % loop.length;
                double tx = VillageContour.pointX(loop[after]) - VillageContour.pointX(loop[before]);
                double tz = VillageContour.pointZ(loop[after]) - VillageContour.pointZ(loop[before]);
                double len = Math.max(1.0, Math.hypot(tx, tz));
                double nx = -tz / len;
                double nz = tx / len;
                if (nx * (x - village.center().getX()) + nz * (z - village.center().getZ()) < 0) {
                    nx = -nx;
                    nz = -nz;
                }
                int lampX = (int) Math.round(x + nx * 3);
                int lampZ = (int) Math.round(z + nz * 3);
                if (lampX >= minBlockX && lampX <= maxBlockX && lampZ >= minBlockZ && lampZ <= maxBlockZ) {
                    placeLamp(region, lampX, lampZ, tally);
                }
            }
        }
    }

    /**
     * Places a 3-wide road patch centered on (x, z) at the planned height.
     * Cells inside {@code interiorClip} village wraps are skipped so roads
     * hand over to the wrap. Existing street surface is never overwritten
     * (checked per block in {@link #placeRoadColumn}).
     */
    private static void stampCell3Wide(WorldGenLevel region, int x, int plannedY, int z, boolean wet,
            List<VillageContour> interiorClip, RoadPalette forcedPalette,
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
                if (interiorClip != null && insideAnyVillage(bx, bz, interiorClip)) {
                    continue;
                }
                placeRoadColumn(region, bx, plannedY, bz, wet, forcedPalette, tally);
            }
        }
    }

    private static boolean insideAnyVillage(int x, int z, List<VillageContour> villages) {
        for (VillageContour village : villages) {
            if (village.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the vanilla street surface actually runs at/around (x, z) —
     * decided from placed path blocks, because street piece bounding boxes
     * include wide grass margins and are useless as a proximity signal.
     */
    private static boolean isStreetHere(WorldGenLevel region, int x, int z) {
        int pathBlocks = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int y = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) - 1;
                if (region.getBlockState(new BlockPos(x + dx, y, z + dz)).is(Blocks.DIRT_PATH)) {
                    pathBlocks++;
                }
            }
        }
        return pathBlocks >= 6;
    }

    private static void placeRoadColumn(WorldGenLevel region, int x, int y, int z, boolean wet,
            RoadPalette forcedPalette, Tally tally) {
        BlockPos top = new BlockPos(x, y, z);
        // Never overwrite existing street/road surface — vanilla streets keep
        // their blocks, and junctions merge instead of stacking.
        int surfaceY = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (region.getBlockState(new BlockPos(x, surfaceY, z)).is(Blocks.DIRT_PATH)) {
            return;
        }
        RoadPalette palette = forcedPalette != null ? forcedPalette : RoadPalettes.at(region, top);
        // 4 blocks of headroom: where the road cuts into a hillside this is
        // the height of the opening it carves.
        for (int dy = 1; dy <= 4; dy++) {
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

    /**
     * Lamps stand on the LIVE surface (heightmap), not the pre-feature noise
     * height — villages terraform their surroundings, and grounding lamps on
     * noise height regularly left them floating or skipped near villages.
     */
    private static void placeLamp(WorldGenLevel region, int x, int z, Tally tally) {
        int groundY = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos ground = new BlockPos(x, groundY, z);
        BlockState groundState = region.getBlockState(ground);
        if (groundState.isAir() || !groundState.getFluidState().isEmpty()
                || groundState.is(BlockTags.LOGS) || groundState.is(BlockTags.LEAVES)) {
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
    static int terrainHeight(ServerLevel serverLevel, int x, int z) {
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        RandomState randomState = serverLevel.getChunkSource().randomState();
        return generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, randomState);
    }
}
