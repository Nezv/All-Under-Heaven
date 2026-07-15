package dev.nez.allunderheaven.client;

import java.util.ArrayList;
import java.util.List;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import dev.nez.allunderheaven.feature.roads.RoadPlanner;
import dev.nez.allunderheaven.feature.roads.RoadPlanner.VillageNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps Xaero's map labelled with the nearby cities' names. Periodically (on the
 * client) it reads the deterministic village network for the area around the
 * player straight from the local world and hands the {name, center} list to
 * {@link CityMapLabelsBridge}, which writes them into a managed Xaero waypoint
 * set. Cities are the same nodes and names the road/village features use, so the
 * map labels always agree with the in-game names.
 *
 * <p>This class deliberately references NO Xaero types — all Xaero access is
 * isolated in {@link CityMapLabelsBridge}, which is only ever classloaded after
 * the {@code xaerominimap} mod is confirmed present, so the mod runs fine
 * without Xaero installed. Because it reads the local integrated server, the
 * labels populate in singleplayer; on a multiplayer client there is no local
 * worldgen to read, so it simply does nothing (the discovery chat waypoints
 * from {@code VillageWatcher} still work there).
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID, value = Dist.CLIENT)
public final class CityMapLabels {
    private static final String XAERO_MINIMAP_ID = "xaerominimap";
    /** Refresh cadence — cheap, but no need to run every client tick. */
    private static final int UPDATE_INTERVAL_TICKS = 40;
    /** Village grid cells searched around the player each way (matches the road planner's reach). */
    private static final int SEARCH_RADIUS_CELLS = 3;

    private static int tickCounter;
    private static List<City> lastSent = List.of();
    private static Boolean xaeroPresent;
    private static boolean bridgeFailed;

    /** A city to label: center position and display name. */
    public record City(int x, int z, String name) {
    }

    private CityMapLabels() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Config.SHOW_CITY_MAP_LABELS.getAsBoolean() || bridgeFailed) {
            return;
        }
        if (xaeroPresent == null) {
            xaeroPresent = ModList.get().isLoaded(XAERO_MINIMAP_ID);
        }
        if (!xaeroPresent) {
            return;
        }
        if (++tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return; // multiplayer client: no local world to read
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }

        BlockPos pos = mc.player.blockPosition();
        RoadPlanner planner = RoadPlanner.of(overworld);
        int cellBlocks = planner.cellSizeBlocks();
        int cellX = Math.floorDiv(pos.getX(), cellBlocks);
        int cellZ = Math.floorDiv(pos.getZ(), cellBlocks);

        List<City> cities = new ArrayList<>();
        for (VillageNode node : planner.nodesAround(cellX, cellZ, SEARCH_RADIUS_CELLS)) {
            cities.add(new City(node.center().getX(), node.center().getZ(), planner.nameOf(node)));
        }
        if (cities.equals(lastSent)) {
            return; // nothing changed since the last refresh
        }
        lastSent = List.copyOf(cities);

        try {
            CityMapLabelsBridge.updateLabels(cities);
        } catch (Throwable t) {
            bridgeFailed = true; // Xaero internals differ from what we expect — disable, don't spam/crash
            AllUnderHeaven.LOGGER.warn("[All Under Heaven] Xaero city labels disabled after an integration error", t);
        }
    }
}
