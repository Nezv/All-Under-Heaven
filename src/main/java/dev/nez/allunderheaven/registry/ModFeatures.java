package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.roads.RoadFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Worldgen features. Wired into biomes through data files:
 * {@code worldgen/configured_feature/roads.json}, {@code worldgen/placed_feature/roads.json}
 * and {@code neoforge/biome_modifier/roads.json}.
 */
public final class ModFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, AllUnderHeaven.MOD_ID);

    /** Runs once per chunk at the top_layer_modification step — roads generate with the terrain. */
    public static final DeferredHolder<Feature<?>, RoadFeature> ROADS =
            FEATURES.register("roads", () -> new RoadFeature(NoneFeatureConfiguration.CODEC));

    private ModFeatures() {
    }
}
