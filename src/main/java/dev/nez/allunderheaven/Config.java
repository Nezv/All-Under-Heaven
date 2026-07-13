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

    public static final ModConfigSpec.BooleanValue ENABLE_ROADS = BUILDER
            .comment("Generate roads: rings around villages, spokes to their streets, and connections between villages.",
                    "Roads only appear in chunks generated while this is enabled.")
            .define("enableRoads", true);

    public static final ModConfigSpec.IntValue ROAD_TRIANGLE_SLACK_BLOCKS = BUILDER
            .comment("Triangle-bias slack 's' (blocks): the road A-C is skipped when a village B exists with |AB|+|BC| < |AC|+s.",
                    "Bigger values prune more aggressively; tune to taste.")
            .defineInRange("roadTriangleSlackBlocks", 48, 0, 512);

    public static final ModConfigSpec.IntValue MAX_ROADS_PER_VILLAGE = BUILDER
            .comment("Besides its guaranteed nearest-neighbor road, a village only accepts extra roads from its",
                    "k nearest neighbors (mutually). Keeps the network sparse instead of a spider web.")
            .defineInRange("maxRoadsPerVillage", 2, 1, 8);

    public static final ModConfigSpec.IntValue ROAD_MAX_LENGTH_BLOCKS = BUILDER
            .comment("Villages further apart than this (blocks) never get a direct road.")
            .defineInRange("roadMaxLengthBlocks", 560, 128, 2048);

    public static final ModConfigSpec.BooleanValue ROAD_LAMPS = BUILDER
            .comment("Place lamp posts along roads every 10-20 blocks.")
            .define("roadLamps", true);

    public static final ModConfigSpec.BooleanValue ROADS_DEBUG_LOG = BUILDER
            .comment("Log the road network summary around spawn on server start (development aid).")
            .define("roadsDebugLog", false);

    static final ModConfigSpec SPEC = BUILDER.build();
}
