package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import dev.nez.allunderheaven.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * Deterministic road planning for one level.
 *
 * <p>Villages sit on the structure-set grid (one candidate cell per
 * {@code spacing×spacing} chunks). Existence of a village in a cell is
 * resolved through the non-generating {@code StructureCheck} fast path — the
 * same mechanism {@code /locate} uses — so planning NEVER forces chunk
 * generation. Node positions are the placement's locate positions
 * (start-chunk centers); exact village geometry is resolved chunk-locally by
 * {@link RoadBuilder} from already-computed structure references.
 *
 * <p>Everything derived here (nodes, kept edges, paths, lamps) is a pure
 * function of the world seed, so every chunk materializes the same plan
 * regardless of generation order.
 */
public final class RoadPlanner {
    private static final Map<ResourceKey<Level>, RoadPlanner> PLANNERS = new ConcurrentHashMap<>();

    /** Distance between path samples, in blocks. */
    static final int SAMPLE_STEP = 4;
    /** Max height change between adjacent samples (keeps every 1-block step ≤ 1 high). */
    private static final float MAX_STEP_RISE = 2.0f;
    /** A wet run this many samples long (≈ this×{@link #SAMPLE_STEP} blocks) is bridged, not causewayed. */
    private static final int BRIDGE_MIN_SAMPLES = 3;
    /** Radius (in grid cells) canonically searched for triangle-pruning candidates. */
    private static final int PRUNE_SEARCH_CELLS = 3;

    private final ServerLevel level;
    private final RandomSpreadStructurePlacement placement;
    private final Set<Structure> villageStructures;
    // Concurrent: the road feature runs on worldgen worker threads. All values
    // are pure functions of the seed, so racing computations are harmless.
    private final Map<Long, Optional<VillageNode>> nodes = new ConcurrentHashMap<>();
    private final Map<EdgeKey, Optional<RoadPath>> paths = new ConcurrentHashMap<>();
    private final Map<EdgeKey, Boolean> keptEdges = new ConcurrentHashMap<>();
    private final Map<Long, List<VillageNode>> rankedNeighbors = new ConcurrentHashMap<>();

    /** A village on the road network (position approximated by its start chunk). */
    public record VillageNode(int cellX, int cellZ, BlockPos center) {
    }

    record EdgeKey(long a, long b) {
        static EdgeKey of(VillageNode n1, VillageNode n2) {
            long k1 = ChunkPos.pack(n1.cellX(), n1.cellZ());
            long k2 = ChunkPos.pack(n2.cellX(), n2.cellZ());
            return k1 <= k2 ? new EdgeKey(k1, k2) : new EdgeKey(k2, k1);
        }
    }

    public static RoadPlanner of(ServerLevel level) {
        return PLANNERS.computeIfAbsent(level.dimension(), key -> new RoadPlanner(level));
    }

    public static void clearAll() {
        PLANNERS.clear();
    }

    private RoadPlanner(ServerLevel level) {
        this.level = level;
        StructureSet villages = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.VILLAGES)
                .value();
        this.placement = (RandomSpreadStructurePlacement) villages.placement();
        this.villageStructures = villages.structures().stream()
                .map(entry -> entry.structure().value())
                .collect(Collectors.toSet());
    }

    public Set<Structure> villageStructures() {
        return villageStructures;
    }

    public int cellSizeChunks() {
        return placement.spacing();
    }

    public int cellSizeBlocks() {
        return placement.spacing() * 16;
    }

    /**
     * Resolves the village of a grid cell, or empty if none generates there.
     * Replicates vanilla's decision purely from noise — never touches chunks:
     * the placement grid gives the candidate chunk, and a village generates
     * there iff the surface biome at the locate position suits any village
     * type (vanilla retries every entry of the weighted set, so existence is
     * exactly the biome-union check). Cached.
     */
    public Optional<VillageNode> node(int cellX, int cellZ) {
        return nodes.computeIfAbsent(ChunkPos.pack(cellX, cellZ), key -> computeNode(cellX, cellZ));
    }

    private Optional<VillageNode> computeNode(int cellX, int cellZ) {
        ChunkPos candidate = placement.getPotentialStructureChunk(level.getSeed(),
                cellX * placement.spacing(), cellZ * placement.spacing());
        BlockPos locate = placement.getLocatePos(candidate);

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int surfaceY = generator.getFirstOccupiedHeight(locate.getX(), locate.getZ(),
                Heightmap.Types.WORLD_SURFACE_WG, level, randomState) + 1;
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(locate.getX()), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(locate.getZ()),
                randomState.sampler());

        for (Structure structure : villageStructures) {
            if (structure.biomes().contains(biome)) {
                return Optional.of(new VillageNode(cellX, cellZ, locate));
            }
        }
        return Optional.empty();
    }

    /** All existing villages within {@code radiusCells} of the given cell. */
    public List<VillageNode> nodesAround(int cellX, int cellZ, int radiusCells) {
        List<VillageNode> found = new ArrayList<>();
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                node(cellX + dx, cellZ + dz).ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * A village's potential neighbors, canonically sorted by distance (ties
     * broken by cell key so every chunk computes the identical ranking).
     */
    private List<VillageNode> neighborsOf(VillageNode v) {
        return rankedNeighbors.computeIfAbsent(ChunkPos.pack(v.cellX(), v.cellZ()), key -> {
            int maxLen = Config.ROAD_MAX_LENGTH_BLOCKS.getAsInt();
            List<VillageNode> found = new ArrayList<>();
            for (VillageNode n : nodesAround(v.cellX(), v.cellZ(), PRUNE_SEARCH_CELLS)) {
                if (!(n.cellX() == v.cellX() && n.cellZ() == v.cellZ()) && dist2d(v.center(), n.center()) <= maxLen) {
                    found.add(n);
                }
            }
            found.sort((n1, n2) -> {
                int byDist = Double.compare(dist2d(v.center(), n1.center()), dist2d(v.center(), n2.center()));
                return byDist != 0 ? byDist : Long.compare(ChunkPos.pack(n1.cellX(), n1.cellZ()), ChunkPos.pack(n2.cellX(), n2.cellZ()));
            });
            return List.copyOf(found);
        });
    }

    private int rankOf(VillageNode target, List<VillageNode> ranking) {
        for (int i = 0; i < ranking.size(); i++) {
            VillageNode n = ranking.get(i);
            if (n.cellX() == target.cellX() && n.cellZ() == target.cellZ()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Edge policy, canonical per edge:
     * <ol>
     *   <li><b>Backbone:</b> every village always connects to its single nearest
     *       neighbor — an isolated village gets exactly one road.</li>
     *   <li><b>Degree cap:</b> extra roads only between mutual k-nearest
     *       neighbors (config: maxRoadsPerVillage) — kills the spider web.</li>
     *   <li><b>Triangle bias:</b> the road A—C is dropped when an in-between
     *       village B exists with {@code |AB| + |BC| < |AC| + s}
     *       (config: roadTriangleSlackBlocks; bigger prunes more).</li>
     * </ol>
     */
    public boolean edgeKept(VillageNode a, VillageNode c) {
        double direct = dist2d(a.center(), c.center());
        if (direct < 1 || direct > Config.ROAD_MAX_LENGTH_BLOCKS.getAsInt()) {
            return false;
        }
        return keptEdges.computeIfAbsent(EdgeKey.of(a, c), key -> {
            int rankAtoC = rankOf(c, neighborsOf(a));
            int rankCtoA = rankOf(a, neighborsOf(c));
            if (rankAtoC < 0 || rankCtoA < 0) {
                return false;
            }
            if (rankAtoC == 0 || rankCtoA == 0) {
                return true; // nearest-neighbor backbone is always kept
            }
            int maxDegree = Config.MAX_ROADS_PER_VILLAGE.getAsInt();
            if (rankAtoC >= maxDegree || rankCtoA >= maxDegree) {
                return false;
            }
            double slack = Config.ROAD_TRIANGLE_SLACK_BLOCKS.getAsInt();
            int midCellX = Math.floorDiv(a.cellX() + c.cellX(), 2);
            int midCellZ = Math.floorDiv(a.cellZ() + c.cellZ(), 2);
            for (VillageNode b : nodesAround(midCellX, midCellZ, PRUNE_SEARCH_CELLS)) {
                if ((b.cellX() == a.cellX() && b.cellZ() == a.cellZ())
                        || (b.cellX() == c.cellX() && b.cellZ() == c.cellZ())) {
                    continue;
                }
                if (dist2d(a.center(), b.center()) + dist2d(b.center(), c.center()) < direct + slack) {
                    return false;
                }
            }
            return true;
        });
    }

    /** The deterministic path for a kept edge. Cached; cheap (~100 height queries once). */
    public Optional<RoadPath> path(VillageNode a, VillageNode b) {
        return paths.computeIfAbsent(EdgeKey.of(a, b), key -> Optional.of(computePath(key, a, b)));
    }

    private RoadPath computePath(EdgeKey key, VillageNode a, VillageNode b) {
        Random random = new Random(level.getSeed() ^ (key.a() * 0x9E3779B97F4A7C15L) ^ Long.rotateLeft(key.b(), 31));

        // 1. Meandering polyline via midpoint displacement (efficient but never straight).
        List<double[]> polyline = new ArrayList<>();
        polyline.add(new double[]{a.center().getX(), a.center().getZ()});
        displace(polyline, a.center().getX(), a.center().getZ(), b.center().getX(), b.center().getZ(), random, 3);

        // 2. Resample at fixed spacing.
        List<double[]> pts = resample(polyline);
        int n = pts.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        float[] ys = new float[n];
        boolean[] wet = new boolean[n];

        // 3. Terrain heights from the generator (works for ungenerated chunks), water flagged.
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        for (int i = 0; i < n; i++) {
            xs[i] = (int) Math.round(pts.get(i)[0]);
            zs[i] = (int) Math.round(pts.get(i)[1]);
            int surface = generator.getFirstOccupiedHeight(xs[i], zs[i], Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            int floor = generator.getFirstOccupiedHeight(xs[i], zs[i], Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            wet[i] = surface - floor >= 2;
            ys[i] = surface;
        }

        // 4. Slope relaxation: cap rise per sample so no 1-block step ever exceeds 1.
        for (int i = 1; i < n; i++) {
            ys[i] = clamp(ys[i], ys[i - 1] - MAX_STEP_RISE, ys[i - 1] + MAX_STEP_RISE);
        }
        for (int i = n - 2; i >= 0; i--) {
            ys[i] = clamp(ys[i], ys[i + 1] - MAX_STEP_RISE, ys[i + 1] + MAX_STEP_RISE);
        }

        // 4b. Classify wet runs: a long run spans open water and becomes a
        // bridge (deck on posts); short runs stay a filled/stone causeway.
        // Purely a function of wet[], so every chunk agrees which is which.
        boolean[] bridge = new boolean[n];
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean w = i < n && wet[i];
            if (w && runStart < 0) {
                runStart = i;
            } else if (!w && runStart >= 0) {
                if (i - runStart >= BRIDGE_MIN_SAMPLES) {
                    for (int k = runStart; k < i; k++) {
                        bridge[k] = true;
                    }
                }
                runStart = -1;
            }
        }

        // 5. Lamp spots every 10–20 blocks, alternating sides, never on water.
        List<RoadPath.Lamp> lamps = new ArrayList<>();
        if (Config.ROAD_LAMPS.getAsBoolean()) {
            double untilNext = 10 + random.nextInt(11);
            boolean left = random.nextBoolean();
            for (int i = 1; i < n; i++) {
                untilNext -= SAMPLE_STEP;
                if (untilNext > 0 || wet[i]) {
                    continue;
                }
                double dx = xs[i] - xs[i - 1];
                double dz = zs[i] - zs[i - 1];
                double len = Math.max(1.0, Math.hypot(dx, dz));
                int side = left ? 1 : -1;
                int lampX = (int) Math.round(xs[i] + (-dz / len) * 2 * side);
                int lampZ = (int) Math.round(zs[i] + (dx / len) * 2 * side);
                lamps.add(new RoadPath.Lamp(lampX, lampZ));
                left = !left;
                untilNext = 10 + random.nextInt(11);
            }
        }

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxX = Math.max(maxX, xs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        return new RoadPath(xs, zs, ys, wet, bridge, List.copyOf(lamps), new RoadPath.Bounds2D(minX, minZ, maxX, maxZ));
    }

    private void displace(List<double[]> out, double x1, double z1, double x2, double z2, Random random, int depth) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        if (depth <= 0 || len < 48) {
            out.add(new double[]{x2, z2});
            return;
        }
        double midX = (x1 + x2) / 2;
        double midZ = (z1 + z2) / 2;
        double offset = (random.nextDouble() * 2 - 1) * len * 0.18;
        midX += (-(z2 - z1) / len) * offset;
        midZ += ((x2 - x1) / len) * offset;
        displace(out, x1, z1, midX, midZ, random, depth - 1);
        displace(out, midX, midZ, x2, z2, random, depth - 1);
    }

    private static List<double[]> resample(List<double[]> polyline) {
        List<double[]> out = new ArrayList<>();
        out.add(polyline.get(0));
        double carried = 0;
        for (int i = 1; i < polyline.size(); i++) {
            double[] from = polyline.get(i - 1);
            double[] to = polyline.get(i);
            double segLen = Math.hypot(to[0] - from[0], to[1] - from[1]);
            double t = SAMPLE_STEP - carried;
            while (t <= segLen) {
                double f = t / segLen;
                out.add(new double[]{from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f});
                t += SAMPLE_STEP;
            }
            carried = segLen - (t - SAMPLE_STEP);
        }
        double[] last = polyline.get(polyline.size() - 1);
        double[] tail = out.get(out.size() - 1);
        if (Math.hypot(last[0] - tail[0], last[1] - tail[1]) > 1) {
            out.add(last);
        }
        return out;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static double dist2d(BlockPos p1, BlockPos p2) {
        double dx = p1.getX() - p2.getX();
        double dz = p1.getZ() - p2.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
