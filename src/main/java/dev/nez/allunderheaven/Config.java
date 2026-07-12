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

    static final ModConfigSpec SPEC = BUILDER.build();
}
