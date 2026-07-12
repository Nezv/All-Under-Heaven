package dev.nez.allunderheaven.feature.roads;

import java.util.List;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Roads — wires the deterministic road network into world generation.
 *
 * <p>Every newly generated overworld chunk asks the {@link RoadPlanner} which
 * inter-village roads, village ring roads and street spokes cross it, and the
 * {@link RoadBuilder} stamps exactly the blocks inside that chunk. Because the
 * whole plan is a pure function of the world seed, roads join up seamlessly
 * across chunks no matter the order in which they generate.
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID)
public final class RoadsFeature {
    private RoadsFeature() {
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !Config.ENABLE_ROADS.getAsBoolean()) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        try {
            long before = RoadBuilder.ROAD_BLOCKS_PLACED.get();
            RoadBuilder.buildForChunk(level, chunk);
            long placed = RoadBuilder.ROAD_BLOCKS_PLACED.get() - before;
            if (placed > 0 && Config.ROADS_DEBUG_LOG.getAsBoolean()) {
                AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: chunk {} +{} road blocks", chunk.getPos(), placed);
            }
        } catch (Exception e) {
            AllUnderHeaven.LOGGER.error("[All Under Heaven] Road building failed for chunk {}", chunk.getPos(), e);
        }
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Config.ROADS_DEBUG_LOG.getAsBoolean() || !Config.ENABLE_ROADS.getAsBoolean()) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        RoadPlanner planner = RoadPlanner.of(overworld);
        ChunkPos spawnChunk = new ChunkPos(
                overworld.getRespawnData().pos().getX() >> 4,
                overworld.getRespawnData().pos().getZ() >> 4);
        int cellX = Math.floorDiv(spawnChunk.x(), planner.cellSizeChunks());
        int cellZ = Math.floorDiv(spawnChunk.z(), planner.cellSizeChunks());

        List<VillageNode> nodes = planner.nodesAround(cellX, cellZ, 3);
        int kept = 0;
        int pruned = 0;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                double dist = RoadPlanner.dist2d(nodes.get(i).center(), nodes.get(j).center());
                if (dist > Config.ROAD_MAX_LENGTH_BLOCKS.getAsInt()) {
                    continue;
                }
                if (planner.edgeKept(nodes.get(i), nodes.get(j))) {
                    kept++;
                } else {
                    pruned++;
                }
            }
        }
        AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: {} villages within 3 cells of spawn, {} roads kept, {} pruned by triangle rule (s={})",
                nodes.size(), kept, pruned, Config.ROAD_TRIANGLE_SLACK_BLOCKS.getAsInt());
        for (VillageNode node : nodes) {
            AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: village node at {} (cell {},{})",
                    node.center(), node.cellX(), node.cellZ());
        }
        AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: {} road blocks and {} lamps placed so far",
                RoadBuilder.ROAD_BLOCKS_PLACED.get(), RoadBuilder.LAMPS_PLACED.get());

        // Force-generate the chunks along the first kept edge so materialization
        // is exercised end-to-end even though vanilla pregenerates almost nothing.
        outer:
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                VillageNode a = nodes.get(i);
                VillageNode b = nodes.get(j);
                if (RoadPlanner.dist2d(a.center(), b.center()) > Config.ROAD_MAX_LENGTH_BLOCKS.getAsInt()
                        || !planner.edgeKept(a, b)) {
                    continue;
                }
                planner.path(a, b).ifPresent(path -> {
                    AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: generating corridor {} -> {} ({} samples, {} lamps planned)",
                            a.center(), b.center(), path.sampleCount(), path.lamps().size());
                    for (int s = 0; s < path.sampleCount(); s += 8) {
                        overworld.getChunk(path.xs()[s] >> 4, path.zs()[s] >> 4);
                    }
                });
                break outer;
            }
        }
        AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: after corridor generation — {} road blocks, {} lamps placed",
                RoadBuilder.ROAD_BLOCKS_PLACED.get(), RoadBuilder.LAMPS_PLACED.get());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        RoadPlanner.clearAll();
    }
}
