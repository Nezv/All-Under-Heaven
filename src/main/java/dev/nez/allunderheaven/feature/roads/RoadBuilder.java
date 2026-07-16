package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
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
    private static final BlockState DRAIN_FILL = Blocks.DIRT.defaultBlockState();
    private static final BlockState DRAIN_SURFACE = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState CAUSEWAY_FILL = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState BRIDGE_RAIL_BASE = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState BRIDGE_POST = Blocks.COBBLESTONE.defaultBlockState();

    /** Deepest pool of trapped water A1 fills inside the walls; deeper is left. */
    private static final int DRAIN_MAX_DEPTH = 8;
    /** Deepest water a road causeway fills solid before it just caps the top. */
    private static final int CAUSEWAY_MAX_DEPTH = 8;
    /** A bridge drops a support post to the bed every this many blocks. */
    private static final int BRIDGE_POST_EVERY = 6;
    /** How far a bridge post probes down for the bed before giving up. */
    private static final int BRIDGE_POST_MAX_DROP = 40;
    /** A tier-2 village with at least this fraction of its footprint over water gets no wall. */
    private static final double WET_SUPPRESS_FRACTION = 0.5;
    /** Spacing (blocks) of the village-footprint wetness probe grid. */
    private static final int WET_PROBE_STEP = 8;

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
        List<WallBuilder.GateArch> gateArches = new ArrayList<>();
        Tally tally = new Tally();

        // (a) Inter-village roads (with wrap clipping + outer-node connectors).
        // Each road's full centerline is scanned (unclipped, chunk-order
        // independent) for wall crossings, resolved to constant gate-arch
        // anchors the wall pass carves through instead of sealing.
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                VillageNode a = nodes.get(i);
                VillageNode b = nodes.get(j);
                if (!planner.edgeKept(a, b)) {
                    continue;
                }
                planner.path(a, b).ifPresent(path -> {
                    if (path.bounds().grow(8).intersects(minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
                        stampPath(region, serverLevel, path, villages, gateArches,
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

        // (b2) Tier-2 towns: drain open water trapped inside the walls so a
        // town over a river/pond reads as reclaimed ground. Runs before the
        // walls (their foundations then sit on the fill) and the stoning.
        for (VillageContour village : villages) {
            if (village.tier() == VillageTier.TIER2) {
                drainWalledInterior(region, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, tally);
            }
        }

        // (c) Tier-2 towns: vanilla dirt-path streets become stone.
        for (VillageContour village : villages) {
            if (village.tier() == VillageTier.TIER2) {
                stoneStreets(region, village, minBlockX, minBlockZ, maxBlockX, maxBlockZ, tally);
            }
        }

        // (d) Tier-2 towns: the plain city wall around the wrap, then the
        // constant gate arches carved through it where roads cross, then the
        // guard towers. Gates run after the wall so they overwrite it; the
        // deduped anchor set is identical in every chunk, so border-spanning
        // arches match.
        for (VillageContour village : villages) {
            tally.wallBlocks += WallBuilder.stampWalls(region, serverLevel, village,
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }
        tally.wallBlocks += WallBuilder.stampGates(region, WallBuilder.dedupeGates(gateArches),
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        for (VillageContour village : villages) {
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
     * C3 — fraction of a village's footprint that sits over water, sampled on a
     * coarse grid from the generator noise (WORLD_SURFACE_WG vs OCEAN_FLOOR_WG,
     * the same probe road heights use). No chunk loads; deterministic, cached
     * with the contour. Drives whether the town gets a wall at all.
     */
    private static double villageWetFraction(ServerLevel serverLevel, BoundingBox bounds) {
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        RandomState randomState = serverLevel.getChunkSource().randomState();
        int wet = 0;
        int total = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += WET_PROBE_STEP) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += WET_PROBE_STEP) {
                int surface = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, randomState);
                int floor = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, serverLevel, randomState);
                if (surface - floor >= 2) {
                    wet++;
                }
                total++;
            }
        }
        return total == 0 ? 0.0 : (double) wet / total;
    }

    /**
     * A1 — drains open water trapped inside a tier-2 town's walls. Each cell of
     * the walled interior that still has fluid at its live surface is filled up
     * to the water line (dirt body, grass cap) so a village straddling a river
     * or pond becomes dry reclaimed ground instead of a flooded square. Only
     * actual fluid columns are touched (buildings/roads are already solid), and
     * every read/write is inside this chunk, so it is deterministic and
     * chunk-order independent. Water deeper than {@link #DRAIN_MAX_DEPTH} is left
     * alone (a genuine pool reads better than a deep scar of fill).
     */
    private static void drainWalledInterior(WorldGenLevel region, VillageContour village,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Tally tally) {
        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                if (!village.isInsideWalls(x, z)) {
                    continue;
                }
                int topY = region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (region.getBlockState(new BlockPos(x, topY, z)).getFluidState().isEmpty()) {
                    continue; // dry surface here — nothing to drain
                }
                // Walk down to the solid bed, bounded by the max drain depth.
                int bedY = topY;
                int guard = 0;
                while (guard <= DRAIN_MAX_DEPTH) {
                    BlockState state = region.getBlockState(new BlockPos(x, bedY, z));
                    if (state.getFluidState().isEmpty() && !state.isAir()) {
                        break; // solid bed reached
                    }
                    bedY--;
                    guard++;
                }
                if (topY - bedY <= 0 || guard > DRAIN_MAX_DEPTH) {
                    continue; // no water, or too deep — leave it as a pool
                }
                for (int fy = bedY + 1; fy < topY; fy++) {
                    region.setBlock(new BlockPos(x, fy, z), DRAIN_FILL, 2);
                }
                region.setBlock(new BlockPos(x, topY, z), DRAIN_SURFACE, 2);
                tally.wallBlocks++;
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
                        // C3 — a village sitting mostly in water gets no wall (a
                        // wall ringing a lake never looks right); moderate water
                        // is handled by the drain/causeway/water-gate passes.
                        boolean wallsAllowed = tier != VillageTier.TIER2
                                || villageWetFraction(serverLevel, start.getBoundingBox()) < WET_SUPPRESS_FRACTION;
                        VillageContour contour = VillageContour.of(start,
                                Config.ROAD_WRAP_MARGIN_BLOCKS.getAsInt(), tier, serverLevel.getSeed(), wallsAllowed);
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
            List<VillageContour> villages, List<WallBuilder.GateArch> gateArches,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        // Build the full centerline (unclipped) so gate detection sees each
        // wall crossing whole and resolves it to the same anchor in every chunk.
        List<int[]> centerline = new ArrayList<>();
        int n = path.sampleCount();
        for (int i = 1; i < n; i++) {
            double segLen = Math.hypot(path.xs()[i] - path.xs()[i - 1], path.zs()[i] - path.zs()[i - 1]);
            int steps = Math.max(1, (int) Math.ceil(segLen));
            int wet = path.wet()[i - 1] || path.wet()[i] ? 1 : 0;
            int bridge = path.bridge()[i - 1] || path.bridge()[i] ? 1 : 0;
            for (int s = 0; s <= steps; s++) {
                double f = (double) s / steps;
                int x = (int) Math.round(path.xs()[i - 1] + (path.xs()[i] - path.xs()[i - 1]) * f);
                int z = (int) Math.round(path.zs()[i - 1] + (path.zs()[i] - path.zs()[i - 1]) * f);
                int y = Math.round(path.ys()[i - 1] + (path.ys()[i] - path.ys()[i - 1]) * (float) f);
                centerline.add(new int[]{x, z, y, wet, bridge});
            }
        }
        detectGates(centerline, villages, gateArches);
        for (int idx = 0; idx < centerline.size(); idx++) {
            int[] p = centerline.get(idx);
            int x = p[0];
            int z = p[1];
            if (p[4] == 1) {
                // A3 — bridge deck; posts/rails reach ±2, so use a wider margin.
                if (x + 2 < minBlockX || x - 2 > maxBlockX || z + 2 < minBlockZ || z - 2 > maxBlockZ) {
                    continue;
                }
                int a = Math.max(0, idx - 1);
                int b = Math.min(centerline.size() - 1, idx + 1);
                double dirX = centerline.get(b)[0] - centerline.get(a)[0];
                double dirZ = centerline.get(b)[1] - centerline.get(a)[1];
                stampBridge(region, x, p[2], z, dirX, dirZ, idx,
                        minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
            } else {
                if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                    continue;
                }
                stampCell3Wide(region, x, p[2], z, p[3] == 1, villages, null,
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
        stampConnector(region, serverLevel, path, villages, gateArches, true,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
        stampConnector(region, serverLevel, path, villages, gateArches, false,
                minBlockX, minBlockZ, maxBlockX, maxBlockZ, stamped, tally);
    }

    /**
     * Resolves each place a road centerline crosses a village wall into a
     * single {@link WallBuilder.GateArch} anchor. Walking the (unclipped)
     * centerline, the contiguous run of samples over the 2-course wall band is
     * found; its midpoint is the crossing center, its endpoints give the road's
     * travel direction, and the sample's Y is the road surface. Because the
     * whole centerline is scanned (never chunk-clipped) and the village geometry
     * is fixed, every chunk that stamps any part of the arch derives the same
     * anchor — the crux of keeping the arch consistent across chunk borders.
     */
    private static void detectGates(List<int[]> centerline, List<VillageContour> villages,
            List<WallBuilder.GateArch> out) {
        int n = centerline.size();
        for (VillageContour village : villages) {
            if (!village.hasWall()) {
                continue;
            }
            int runStart = -1;
            for (int k = 0; k <= n; k++) {
                boolean on = k < n && onWallBand(village, centerline.get(k)[0], centerline.get(k)[1]);
                if (on && runStart < 0) {
                    runStart = k;
                } else if (!on && runStart >= 0) {
                    int runEnd = k - 1;
                    int[] mid = centerline.get((runStart + runEnd) / 2);
                    double dirX = centerline.get(runEnd)[0] - centerline.get(runStart)[0];
                    double dirZ = centerline.get(runEnd)[1] - centerline.get(runStart)[1];
                    if (dirX == 0 && dirZ == 0) {
                        // Single-cell run: read direction from the wider neighbourhood.
                        int a = Math.max(0, runStart - 1);
                        int b = Math.min(n - 1, runEnd + 1);
                        dirX = centerline.get(b)[0] - centerline.get(a)[0];
                        dirZ = centerline.get(b)[1] - centerline.get(a)[1];
                    }
                    out.add(new WallBuilder.GateArch(mid[0], mid[1], mid[2], dirX, dirZ));
                    runStart = -1;
                }
            }
        }
    }

    /** Whether (x, z) is on or adjacent to a wall course cell (the 2-course band). */
    private static boolean onWallBand(VillageContour village, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (village.isWallCell(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Where a road meets its endpoint village, route the last stretch to the
     * best-facing vanilla street end instead of stopping dead at the wrap.
     * Deterministic: depends only on the path and the village geometry.
     */
    private static void stampConnector(WorldGenLevel region, ServerLevel serverLevel, RoadPath path,
            List<VillageContour> villages, List<WallBuilder.GateArch> gateArches, boolean fromStart,
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
        // Full connector centerline (unclipped), so its wall crossing resolves
        // to the same gate anchor as the main path does — this stretch is often
        // the one that actually threads the wall on the way to the street end.
        List<int[]> centerline = new ArrayList<>();
        for (int s = 0; s <= steps; s++) {
            int x = (int) Math.round(exitX + (best.getX() - exitX) * (double) s / steps);
            int z = (int) Math.round(exitZ + (best.getZ() - exitZ) * (double) s / steps);
            centerline.add(new int[]{x, z, terrainHeight(serverLevel, x, z), 0});
        }
        detectGates(centerline, villages, gateArches);
        for (int[] p : centerline) {
            int x = p[0];
            int z = p[1];
            if (x + 1 < minBlockX || x - 1 > maxBlockX || z + 1 < minBlockZ || z - 1 > maxBlockZ) {
                continue;
            }
            stampCell3Wide(region, x, p[2], z, false, null, palette,
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
     * A3 — a bridge cross-section over open water: a stone deck at road grade
     * (3 walkable lanes plus a rail base on each side), fence railings on the
     * outer lanes, and a cobblestone support post dropped to the bed at
     * intervals. Nothing is filled beneath the deck, so the water shows through
     * the span. The whole thing keys off the deterministic centerline
     * (position + travel direction + along-index), so chunk halves match.
     */
    private static void stampBridge(WorldGenLevel region, int cx, int deckY, int cz,
            double dirX, double dirZ, int alongIndex,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, Set<Long> stamped, Tally tally) {
        double len = Math.max(1.0, Math.hypot(dirX, dirZ));
        double px = -dirZ / len;
        double pz = dirX / len;
        RoadPalette palette = RoadPalettes.at(region, new BlockPos(cx, deckY, cz));

        for (int o = -2; o <= 2; o++) {
            int bx = (int) Math.round(cx + px * o);
            int bz = (int) Math.round(cz + pz * o);
            if (bx < minBlockX || bx > maxBlockX || bz < minBlockZ || bz > maxBlockZ) {
                continue;
            }
            long key = (bx & 0xFFFFFFFFL) | ((long) bz << 32);
            if (!stamped.add(key)) {
                continue;
            }
            for (int dy = 1; dy <= 4; dy++) {
                BlockPos above = new BlockPos(bx, deckY + dy, bz);
                if (!region.getBlockState(above).isAir()) {
                    region.setBlock(above, AIR, 2);
                }
            }
            boolean rail = o == -2 || o == 2;
            region.setBlock(new BlockPos(bx, deckY, bz),
                    rail ? BRIDGE_RAIL_BASE : RoadPalettes.stoneSurfaceAt(bx, bz), 2);
            tally.roadBlocks++;
            if (rail) {
                region.setBlock(new BlockPos(bx, deckY + 1, bz), palette.post(), 2);
                tally.roadBlocks++;
            }
        }

        // Support post under the centerline, down to the bed, at intervals.
        if (Math.floorMod(alongIndex, BRIDGE_POST_EVERY) == 0
                && cx >= minBlockX && cx <= maxBlockX && cz >= minBlockZ && cz <= maxBlockZ) {
            int y = deckY - 1;
            int guard = 0;
            while (guard < BRIDGE_POST_MAX_DROP) {
                BlockState state = region.getBlockState(new BlockPos(cx, y, cz));
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    break; // reached the bed
                }
                region.setBlock(new BlockPos(cx, y, cz), BRIDGE_POST, 2);
                tally.roadBlocks++;
                y--;
                guard++;
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
        if (wet) {
            // A2 — stone causeway: solid cobblestone from the bed up to the
            // deck, so a short water crossing reads as a tidy stone ford
            // instead of a 2-block dirt lip perched over the water.
            int bedY = y - 1;
            int guard = 0;
            while (guard < CAUSEWAY_MAX_DEPTH) {
                BlockState state = region.getBlockState(new BlockPos(x, bedY, z));
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    break; // solid bed reached
                }
                bedY--;
                guard++;
            }
            for (int fy = bedY + 1; fy <= y - 1; fy++) {
                region.setBlock(new BlockPos(x, fy, z), CAUSEWAY_FILL, 2);
            }
        } else {
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos below = top.below(dy);
                BlockState state = region.getBlockState(below);
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    region.setBlock(below, palette.fill(), 2);
                }
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

    /**
     * Deterministic solid support height at (x, z) from the generator noise: the
     * sea/river bed under water, otherwise the surface. Like {@link
     * #terrainHeight} it is a pure function of the seed (no chunk loads, immune
     * to already-placed blocks), so structures that span chunks — the towers —
     * can extend a footing to real ground identically in every chunk.
     */
    static int supportHeight(ServerLevel serverLevel, int x, int z) {
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        RandomState randomState = serverLevel.getChunkSource().randomState();
        int surface = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, randomState);
        int floor = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, serverLevel, randomState);
        return surface - floor >= 2 ? floor : surface;
    }
}
