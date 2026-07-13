package dev.nez.allunderheaven.feature.roads;

import com.mojang.serialization.Codec;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Worldgen entry point for roads. Runs once per chunk during the
 * {@code top_layer_modification} decoration step (after trees), on the
 * worldgen worker threads — so roads appear together with the terrain as the
 * player explores, exactly like villages do, and never block the server
 * thread.
 */
public class RoadFeature extends Feature<NoneFeatureConfiguration> {
    public RoadFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!Config.ENABLE_ROADS.getAsBoolean()) {
            return false;
        }
        WorldGenLevel level = context.level();
        ServerLevel serverLevel = level.getLevel();
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(
                SectionPos.blockToSectionCoord(context.origin().getX()),
                SectionPos.blockToSectionCoord(context.origin().getZ()));
        try {
            long placed = RoadBuilder.buildForChunk(level, serverLevel, chunkPos);
            if (placed > 0 && Config.ROADS_DEBUG_LOG.getAsBoolean()) {
                AllUnderHeaven.LOGGER.info("[All Under Heaven] Roads debug: chunk {} +{} road blocks", chunkPos, placed);
            }
            return placed > 0;
        } catch (Exception e) {
            AllUnderHeaven.LOGGER.error("[All Under Heaven] Road building failed for chunk {}", chunkPos, e);
            return false;
        }
    }
}
