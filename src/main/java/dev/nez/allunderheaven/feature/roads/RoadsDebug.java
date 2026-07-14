package dev.nez.allunderheaven.feature.roads;

import java.util.List;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Lifecycle glue for the road system: clears the per-level plan caches on
 * shutdown and, when {@code roadsDebugLog} is enabled, prints a network
 * summary at startup and force-generates one road corridor so materialization
 * can be verified end-to-end without walking there.
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID)
public final class RoadsDebug {
    private RoadsDebug() {
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
        AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: {} villages within 3 cells of spawn, {} roads kept, {} pruned (maxRoadsPerVillage={}, s={})",
                nodes.size(), kept, pruned, Config.MAX_ROADS_PER_VILLAGE.getAsInt(), Config.ROAD_TRIANGLE_SLACK_BLOCKS.getAsInt());
        for (VillageNode node : nodes) {
            AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: village node at {} (cell {},{})",
                    node.center(), node.cellX(), node.cellZ());
        }

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
                    // Also generate the full area around both endpoint villages
                    // so their wraps, walls and corner lamps materialize completely.
                    for (VillageNode endpoint : List.of(a, b)) {
                        int centerChunkX = endpoint.center().getX() >> 4;
                        int centerChunkZ = endpoint.center().getZ() >> 4;
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                overworld.getChunk(centerChunkX + dx, centerChunkZ + dz);
                            }
                        }
                    }
                });
                break outer;
            }
        }
        AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: after corridor generation — {} road blocks, {} lamps, {} wall blocks placed",
                RoadBuilder.ROAD_BLOCKS_PLACED.get(), RoadBuilder.LAMPS_PLACED.get(),
                RoadBuilder.WALL_BLOCKS_PLACED.get());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        RoadPlanner.clearAll();
        RoadBuilder.clearCaches();
    }
}
