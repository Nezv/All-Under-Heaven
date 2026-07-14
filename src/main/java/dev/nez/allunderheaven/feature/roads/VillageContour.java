package dev.nez.allunderheaven.feature.roads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.nez.allunderheaven.feature.villages.VillageTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * The wrapping geometry of one village, derived from its actual buildings.
 *
 * <p>Built once per village (deterministic, cacheable): building footprints
 * (street and tiny decor pieces excluded) are rasterized onto a local grid,
 * morphologically closed so nearby buildings merge into one cluster
 * silhouette, dilated by the wrap margin, and the boundary of the largest
 * region is traced and simplified into a concave loop that hugs the buildings
 * a couple of blocks out. Loop points that run close to a vanilla street are
 * snapped onto that street, so the street itself carries the wrap there
 * instead of a parallel road.
 */
public final class VillageContour {
    /** Gap (blocks) up to which separate buildings merge into one wrapped cluster. */
    private static final int GAP_BRIDGE = 8;
    /** Douglas-Peucker tolerance for straightening the traced boundary. */
    private static final double SIMPLIFY_TOLERANCE = 2.5;
    /** Building pieces smaller than this (either axis) are decor and ignored. */
    private static final int MIN_BUILDING_SIZE = 3;
    /** Ground (blocks) between the wrap road's outer edge and the wall's inner face. */
    private static final int WALL_GAP = 2;
    /** Wrap road half-width: the road spans centerline ± this. */
    private static final int ROAD_HALF_WIDTH = 1;

    private final BlockPos center;
    private final VillageTier tier;
    private final BoundingBox structureBounds;
    private final int gridMinX;
    private final int gridMinZ;
    private final int gridW;
    private final int gridH;
    private final boolean[] interior;
    /** The wrap loop at block resolution: packed (x | z<<32) points, closed. */
    private final long[] loopPoints;
    /** Indices into loopPoints marking the simplified polygon's corners. */
    private final int[] cornerIndices;
    /** Tier-2 wall courses (packed block coords): inner ring and outer ring. */
    private final long[] wallInner;
    private final long[] wallOuter;
    private final List<BoundingBox> streetBoxes;
    private final List<BlockPos> outerNodes;

    private VillageContour(BlockPos center, VillageTier tier, BoundingBox structureBounds,
            int gridMinX, int gridMinZ, int gridW, int gridH,
            boolean[] interior, long[] loopPoints, int[] cornerIndices,
            long[] wallInner, long[] wallOuter,
            List<BoundingBox> streetBoxes, List<BlockPos> outerNodes) {
        this.center = center;
        this.tier = tier;
        this.structureBounds = structureBounds;
        this.gridMinX = gridMinX;
        this.gridMinZ = gridMinZ;
        this.gridW = gridW;
        this.gridH = gridH;
        this.interior = interior;
        this.loopPoints = loopPoints;
        this.cornerIndices = cornerIndices;
        this.wallInner = wallInner;
        this.wallOuter = wallOuter;
        this.streetBoxes = streetBoxes;
        this.outerNodes = outerNodes;
    }

    public BlockPos center() {
        return center;
    }

    public VillageTier tier() {
        return tier;
    }

    public BoundingBox structureBounds() {
        return structureBounds;
    }

    public long[] loopPoints() {
        return loopPoints;
    }

    /** Tier-2 wall, inner course (packed block coords); empty for other tiers. */
    public long[] wallInner() {
        return wallInner;
    }

    /** Tier-2 wall, outer course (packed block coords); empty for other tiers. */
    public long[] wallOuter() {
        return wallOuter;
    }

    public int[] cornerIndices() {
        return cornerIndices;
    }

    public List<BoundingBox> streetBoxes() {
        return streetBoxes;
    }

    public List<BlockPos> outerNodes() {
        return outerNodes;
    }

    public static int pointX(long packed) {
        return (int) packed;
    }

    public static int pointZ(long packed) {
        return (int) (packed >> 32);
    }

    private static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    /** Whether (x, z) lies inside the wrapped area (buildings + margin). */
    public boolean contains(int x, int z) {
        int gx = x - gridMinX;
        int gz = z - gridMinZ;
        return gx >= 0 && gz >= 0 && gx < gridW && gz < gridH && interior[gz * gridW + gx];
    }

    /**
     * @param wrapMargin distance (blocks) between the wrap road centerline and
     *                   the building walls; larger values give a roomier ring.
     * @param tier       city tier; TIER2 additionally gets the wall bands.
     */
    public static VillageContour of(StructureStart start, int wrapMargin, VillageTier tier) {
        BoundingBox bounds = start.getBoundingBox();
        BlockPos center = bounds.getCenter();
        // Room for the closing, the margin, and (tier 2) the wall band that
        // sits up to ~5 blocks outside the loop, plus simplification drift.
        int pad = GAP_BRIDGE + wrapMargin + 12;
        int minX = bounds.minX() - pad;
        int minZ = bounds.minZ() - pad;
        int w = bounds.maxX() - bounds.minX() + 1 + 2 * pad;
        int h = bounds.maxZ() - bounds.minZ() + 1 + 2 * pad;

        // 1. Split pieces and rasterize building footprints.
        List<BoundingBox> streets = new ArrayList<>();
        List<BoundingBox> buildings = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (piece instanceof PoolElementStructurePiece pool) {
                String element = pool.getElement().toString();
                if (element.contains("streets") || element.contains("terminators")) {
                    streets.add(box);
                    continue;
                }
            }
            if (box.maxX() - box.minX() + 1 < MIN_BUILDING_SIZE || box.maxZ() - box.minZ() + 1 < MIN_BUILDING_SIZE) {
                continue; // decor pieces (lamp posts etc.) do not shape the wrap
            }
            buildings.add(box);
        }

        boolean[] grid = new boolean[w * h];
        for (BoundingBox box : buildings) {
            for (int z = Math.max(box.minZ(), minZ); z <= Math.min(box.maxZ(), minZ + h - 1); z++) {
                int row = (z - minZ) * w;
                for (int x = Math.max(box.minX(), minX); x <= Math.min(box.maxX(), minX + w - 1); x++) {
                    grid[row + (x - minX)] = true;
                }
            }
        }

        // 2. Morphological closing (merge nearby buildings), then wrap margin.
        boolean[] closed = erode(dilate(grid, w, h, GAP_BRIDGE), w, h, GAP_BRIDGE);
        boolean[] interior = dilate(closed, w, h, wrapMargin);

        // 3. Trace the boundary of the largest connected region.
        List<long[]> boundary = traceLargestBoundary(interior, w, h);
        if (boundary.isEmpty()) {
            return new VillageContour(center, tier, bounds, minX, minZ, w, h, interior,
                    new long[0], new int[0], new long[0], new long[0],
                    List.copyOf(streets), List.of());
        }

        // 4. Straighten with Douglas-Peucker (closed loop: two half-arcs).
        List<long[]> vertices = simplifyClosed(boundary);

        // 5. Rasterize the simplified loop back to block steps. (Whether a
        //    stretch is carried by a vanilla street is decided at stamp time
        //    from the actual placed path blocks.)
        List<Long> points = new ArrayList<>();
        List<Integer> corners = new ArrayList<>();
        for (int v = 0; v < vertices.size(); v++) {
            long[] from = vertices.get(v);
            long[] to = vertices.get((v + 1) % vertices.size());
            corners.add(points.size());
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1])));
            for (int s = 0; s < steps; s++) {
                double f = (double) s / steps;
                int x = (int) Math.round(from[0] + (to[0] - from[0]) * f) + minX;
                int z = (int) Math.round(from[1] + (to[1] - from[1]) * f) + minZ;
                long packed = pack(x, z);
                if (points.isEmpty() || points.get(points.size() - 1) != packed) {
                    points.add(packed);
                }
            }
        }
        long[] loop = new long[points.size()];
        for (int i = 0; i < points.size(); i++) {
            loop[i] = points.get(i);
        }
        int[] cornerIndices = corners.stream().mapToInt(Integer::intValue)
                .filter(i -> i < loop.length).toArray();

        // 6. Tier-2 city wall: two 1-block courses concentric with the ACTUAL
        //    wrap road (the rasterized loop), not the raw traced boundary the
        //    simplification drifted from. The loop polygon is filled, then the
        //    courses are the cells at Chebyshev distance (road edge + gap + 1)
        //    and (road edge + gap + 2) from it.
        long[] wallInner = new long[0];
        long[] wallOuter = new long[0];
        if (tier == VillageTier.TIER2 && loop.length > 0) {
            boolean[] roadMask = new boolean[w * h];
            for (long p : loop) {
                int gx = pointX(p) - minX;
                int gz = pointZ(p) - minZ;
                if (gx >= 0 && gz >= 0 && gx < w && gz < h) {
                    roadMask[gz * w + gx] = true;
                }
            }
            boolean[] fill = fillLoop(roadMask, w, h);
            int gapEdge = ROAD_HALF_WIDTH + WALL_GAP;
            boolean[] toGap = dilate(fill, w, h, gapEdge);
            boolean[] toInner = dilate(fill, w, h, gapEdge + 1);
            boolean[] toOuter = dilate(fill, w, h, gapEdge + 2);
            wallInner = bandToPoints(toInner, toGap, w, h, minX, minZ);
            wallOuter = bandToPoints(toOuter, toInner, w, h, minX, minZ);
        }

        // 7. Natural outer nodes: street tips that reach beyond the wrap.
        List<BlockPos> outer = new ArrayList<>();
        for (BoundingBox street : streets) {
            BlockPos tip = farEdgeCenter(street, center);
            int gx = tip.getX() - minX;
            int gz = tip.getZ() - minZ;
            boolean outside = gx < 0 || gz < 0 || gx >= w || gz >= h || !interior[gz * w + gx];
            if (outside && outer.stream().allMatch(o -> distSqr(o, tip) > 20 * 20)) {
                outer.add(tip);
            }
        }
        outer.sort((p1, p2) -> Integer.compare(distSqr(p2, center), distSqr(p1, center)));
        if (outer.size() > 6) {
            outer = new ArrayList<>(outer.subList(0, 6));
        }

        return new VillageContour(center, tier, bounds, minX, minZ, w, h, interior,
                loop, cornerIndices, wallInner, wallOuter,
                List.copyOf(streets), List.copyOf(outer));
    }

    /**
     * The loop cells plus everything they enclose. Outside is flood-filled
     * from the grid border with 4-connectivity — an 8-connected closed curve
     * (which line rasterization produces) cannot be leaked through
     * 4-connectedly, so the interior stays sealed.
     */
    private static boolean[] fillLoop(boolean[] loopMask, int w, int h) {
        boolean[] outside = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            enqueueOutside(loopMask, outside, queue, x);
            enqueueOutside(loopMask, outside, queue, (h - 1) * w + x);
        }
        for (int z = 0; z < h; z++) {
            enqueueOutside(loopMask, outside, queue, z * w);
            enqueueOutside(loopMask, outside, queue, z * w + w - 1);
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int x = idx % w;
            int z = idx / w;
            if (x > 0) {
                enqueueOutside(loopMask, outside, queue, idx - 1);
            }
            if (x < w - 1) {
                enqueueOutside(loopMask, outside, queue, idx + 1);
            }
            if (z > 0) {
                enqueueOutside(loopMask, outside, queue, idx - w);
            }
            if (z < h - 1) {
                enqueueOutside(loopMask, outside, queue, idx + w);
            }
        }
        boolean[] fill = new boolean[w * h];
        for (int i = 0; i < fill.length; i++) {
            fill[i] = !outside[i];
        }
        return fill;
    }

    private static void enqueueOutside(boolean[] loopMask, boolean[] outside, ArrayDeque<Integer> queue, int idx) {
        if (!loopMask[idx] && !outside[idx]) {
            outside[idx] = true;
            queue.add(idx);
        }
    }

    /** Cells in {@code larger} but not {@code smaller}, as packed absolute coords. */
    private static long[] bandToPoints(boolean[] larger, boolean[] smaller, int w, int h, int minX, int minZ) {
        List<Long> points = new ArrayList<>();
        for (int z = 0; z < h; z++) {
            int row = z * w;
            for (int x = 0; x < w; x++) {
                if (larger[row + x] && !smaller[row + x]) {
                    points.add(pack(x + minX, z + minZ));
                }
            }
        }
        long[] out = new long[points.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = points.get(i);
        }
        return out;
    }

    // --- morphology (square structuring element, separable) ---

    private static boolean[] dilate(boolean[] grid, int w, int h, int r) {
        boolean[] horizontal = new boolean[w * h];
        for (int z = 0; z < h; z++) {
            int row = z * w;
            for (int x = 0; x < w; x++) {
                for (int d = -r; d <= r; d++) {
                    int nx = x + d;
                    if (nx >= 0 && nx < w && grid[row + nx]) {
                        horizontal[row + x] = true;
                        break;
                    }
                }
            }
        }
        boolean[] out = new boolean[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                for (int d = -r; d <= r; d++) {
                    int nz = z + d;
                    if (nz >= 0 && nz < h && horizontal[nz * w + x]) {
                        out[z * w + x] = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean[] erode(boolean[] grid, int w, int h, int r) {
        boolean[] inverted = new boolean[w * h];
        for (int i = 0; i < grid.length; i++) {
            inverted[i] = !grid[i];
        }
        boolean[] dilatedInverse = dilate(inverted, w, h, r);
        boolean[] out = new boolean[w * h];
        for (int i = 0; i < grid.length; i++) {
            out[i] = !dilatedInverse[i];
        }
        return out;
    }

    // --- boundary tracing (Moore neighborhood, largest component) ---

    private static final int[][] MOORE = {
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}
    };

    private static List<long[]> traceLargestBoundary(boolean[] grid, int w, int h) {
        // Label components, find the largest.
        int[] labels = new int[w * h];
        int bestLabel = 0;
        int bestSize = 0;
        int nextLabel = 1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < grid.length; i++) {
            if (!grid[i] || labels[i] != 0) {
                continue;
            }
            int size = 0;
            labels[i] = nextLabel;
            queue.add(i);
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                size++;
                int x = idx % w;
                int z = idx / w;
                for (int[] d : MOORE) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    if (nx >= 0 && nz >= 0 && nx < w && nz < h) {
                        int n = nz * w + nx;
                        if (grid[n] && labels[n] == 0) {
                            labels[n] = nextLabel;
                            queue.add(n);
                        }
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size;
                bestLabel = nextLabel;
            }
            nextLabel++;
        }
        if (bestLabel == 0) {
            return List.of();
        }

        // First cell (row-major) of the largest component = trace start.
        int startIdx = -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == bestLabel) {
                startIdx = i;
                break;
            }
        }
        int startX = startIdx % w;
        int startZ = startIdx / w;

        List<long[]> boundary = new ArrayList<>();
        int curX = startX;
        int curZ = startZ;
        int backtrack = 0; // came from the west
        int guard = 4 * w * h;
        do {
            boundary.add(new long[]{curX, curZ});
            int found = -1;
            for (int i = 0; i < 8; i++) {
                int dir = (backtrack + 1 + i) % 8;
                int nx = curX + MOORE[dir][0];
                int nz = curZ + MOORE[dir][1];
                if (nx >= 0 && nz >= 0 && nx < w && nz < h && labels[nz * w + nx] == bestLabel) {
                    found = dir;
                    break;
                }
            }
            if (found < 0) {
                break; // single isolated cell
            }
            curX += MOORE[found][0];
            curZ += MOORE[found][1];
            backtrack = (found + 4) % 8;
        } while ((curX != startX || curZ != startZ) && --guard > 0);
        return boundary;
    }

    // --- simplification ---

    private static List<long[]> simplifyClosed(List<long[]> loop) {
        if (loop.size() < 8) {
            return loop;
        }
        int half = loop.size() / 2;
        List<long[]> first = douglasPeucker(loop.subList(0, half + 1));
        List<long[]> second = douglasPeucker(loop.subList(half, loop.size()));
        List<long[]> out = new ArrayList<>(first);
        out.remove(out.size() - 1);
        out.addAll(second);
        out.remove(out.size() - 1);
        return out;
    }

    private static List<long[]> douglasPeucker(List<long[]> pts) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        double maxDist = -1;
        int maxIdx = -1;
        long[] a = pts.get(0);
        long[] b = pts.get(pts.size() - 1);
        for (int i = 1; i < pts.size() - 1; i++) {
            double d = pointSegmentDistance(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }
        if (maxDist <= SIMPLIFY_TOLERANCE) {
            List<long[]> out = new ArrayList<>();
            out.add(a);
            out.add(b);
            return out;
        }
        List<long[]> left = douglasPeucker(pts.subList(0, maxIdx + 1));
        List<long[]> right = douglasPeucker(pts.subList(maxIdx, pts.size()));
        List<long[]> out = new ArrayList<>(left);
        out.remove(out.size() - 1);
        out.addAll(right);
        return out;
    }

    private static double pointSegmentDistance(long[] p, long[] a, long[] b) {
        double vx = b[0] - a[0];
        double vz = b[1] - a[1];
        double wx = p[0] - a[0];
        double wz = p[1] - a[1];
        double lenSq = vx * vx + vz * vz;
        double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1, (wx * vx + wz * vz) / lenSq));
        double dx = wx - t * vx;
        double dz = wz - t * vz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // --- helpers ---

    /** Center of the box edge facing away from the village center. */
    private static BlockPos farEdgeCenter(BoundingBox box, BlockPos center) {
        int midX = (box.minX() + box.maxX()) / 2;
        int midZ = (box.minZ() + box.maxZ()) / 2;
        int dx = midX - center.getX();
        int dz = midZ - center.getZ();
        if (Math.abs(dx) * (box.maxZ() - box.minZ() + 1) > Math.abs(dz) * (box.maxX() - box.minX() + 1)) {
            return new BlockPos(dx > 0 ? box.maxX() : box.minX(), 0, midZ);
        }
        return new BlockPos(midX, 0, dz > 0 ? box.maxZ() : box.minZ());
    }

    private static int distSqr(BlockPos p1, BlockPos p2) {
        int dx = p1.getX() - p2.getX();
        int dz = p1.getZ() - p2.getZ();
        return dx * dx + dz * dz;
    }
}
