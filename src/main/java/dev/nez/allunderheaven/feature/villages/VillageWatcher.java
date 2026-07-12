package dev.nez.allunderheaven.feature.villages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Villages Redesign — names and discovery.
 *
 * <ul>
 *   <li>Entering a village shows its (deterministic) name on the action bar.</li>
 *   <li>First discovery of a village announces it in chat and, optionally,
 *       sends an Xaero's Minimap waypoint-share line for its center.</li>
 *   <li>On login, the player gets a briefing about the nearest civilization.</li>
 * </ul>
 *
 * <p>All state is per-session and server-side only; names need no storage
 * because they are derived from (world seed, structure start chunk) — see
 * {@link VillageNames}.
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID)
public final class VillageWatcher {
    /** Villages the player is currently inside (structure start chunk, packed). */
    private static final Map<UUID, Long> CURRENT_VILLAGE = new HashMap<>();
    /** Villages announced to the player this session. */
    private static final Map<UUID, Set<Long>> DISCOVERED = new HashMap<>();

    /** Search radius (in chunks) for the login briefing's nearest-village scan. */
    private static final int BRIEFING_SEARCH_RADIUS_CHUNKS = 32;

    private VillageWatcher() {
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!Config.ANNOUNCE_VILLAGE_ENTRY.getAsBoolean()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % Config.VILLAGE_CHECK_INTERVAL_TICKS.getAsInt() != 0) {
            return;
        }

        StructureStart start = level.structureManager().getStructureWithPieceAt(player.blockPosition(), StructureTags.VILLAGE);
        UUID id = player.getUUID();
        if (!start.isValid()) {
            CURRENT_VILLAGE.remove(id);
            return;
        }

        long villageKey = start.getChunkPos().pack();
        Long previous = CURRENT_VILLAGE.get(id);
        if (previous != null && previous == villageKey) {
            return;
        }
        CURRENT_VILLAGE.put(id, villageKey);

        String name = VillageNames.of(level.getSeed(), start.getChunkPos());
        player.sendSystemMessage(Component.translatable("message.allunderheaven.village_entered", name), true);

        if (DISCOVERED.computeIfAbsent(id, k -> new HashSet<>()).add(villageKey)) {
            BlockPos center = start.getBoundingBox().getCenter();
            player.sendSystemMessage(Component.translatable("message.allunderheaven.village_discovered",
                    name, center.getX(), center.getZ()));
            sendWaypoint(player, name, center.getX(), center.getZ());
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.SPAWN_CIVILIZATION_BRIEFING.getAsBoolean()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        BlockPos nearest = level.findNearestMapStructure(StructureTags.VILLAGE, player.blockPosition(),
                BRIEFING_SEARCH_RADIUS_CHUNKS, false);
        if (nearest == null) {
            player.sendSystemMessage(Component.translatable("message.allunderheaven.no_civilization"));
            return;
        }

        String name = VillageNames.of(level.getSeed(),
                new ChunkPos(SectionPos.blockToSectionCoord(nearest.getX()), SectionPos.blockToSectionCoord(nearest.getZ())));
        int distance = (int) Math.sqrt(nearest.distSqr(player.blockPosition()));
        player.sendSystemMessage(Component.translatable("message.allunderheaven.nearest_civilization", name, distance));
        sendWaypoint(player, name, nearest.getX(), nearest.getZ());
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        CURRENT_VILLAGE.remove(id);
        DISCOVERED.remove(id);
    }

    private static void sendWaypoint(ServerPlayer player, String name, int x, int z) {
        if (Config.SEND_XAERO_WAYPOINTS.getAsBoolean()) {
            player.sendSystemMessage(Component.literal(XaeroWaypoints.shareString(name, x, z)));
        }
    }
}
