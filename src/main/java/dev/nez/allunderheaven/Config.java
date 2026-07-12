package dev.nez.allunderheaven;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common (both sides) configuration. Values are editable in-game through the
 * Mods screen (see {@code client/AllUnderHeavenClient}) or in
 * {@code config/allunderheaven-common.toml}.
 *
 * <p>Add new options here and a matching translation key in
 * {@code assets/allunderheaven/lang/en_us.json}
 * ({@code allunderheaven.configuration.<optionName>}).
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_REGISTRY_SUMMARY = BUILDER
            .comment("Log a summary of registered content during common setup.")
            .define("logRegistrySummary", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SERVER_GREETING = BUILDER
            .comment("Write a greeting to the log when a server starts.")
            .define("enableServerGreeting", true);

    public static final ModConfigSpec.BooleanValue ANNOUNCE_VILLAGE_ENTRY = BUILDER
            .comment("Show the village name on screen (action bar) when a player enters a village.")
            .define("announceVillageEntry", true);

    public static final ModConfigSpec.BooleanValue SEND_XAERO_WAYPOINTS = BUILDER
            .comment("When a village is discovered, also send an Xaero's Minimap waypoint-share line in chat.",
                    "Players running Xaero's Minimap get a clickable [Add] button that puts the village on their map;",
                    "players without the mod see the raw share string.")
            .define("sendXaeroWaypoints", true);

    public static final ModConfigSpec.BooleanValue SPAWN_CIVILIZATION_BRIEFING = BUILDER
            .comment("On login, tell the player about the nearest civilization (name, distance, map waypoint).")
            .define("spawnCivilizationBriefing", true);

    public static final ModConfigSpec.IntValue VILLAGE_CHECK_INTERVAL_TICKS = BUILDER
            .comment("How often (in ticks) to check whether players entered or left a village.")
            .defineInRange("villageCheckIntervalTicks", 20, 1, 200);

    static final ModConfigSpec SPEC = BUILDER.build();
}
