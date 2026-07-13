package dev.nez.allunderheaven.feature.roads;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Biome-flavored road materials. The core surface is always shovel-treated
 * earth (dirt path); the accent, foundation and lamp post wood shift with the
 * biome so roads feel local to the village culture they serve.
 */
public final class RoadPalettes {
    public record RoadPalette(BlockState surface, BlockState accent, BlockState wetSurface,
            BlockState fill, BlockState post) {
    }

    private static final RoadPalette DEFAULT = new RoadPalette(
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState());

    private static final RoadPalette DESERT = new RoadPalette(
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
            Blocks.SANDSTONE.defaultBlockState(),
            Blocks.SANDSTONE.defaultBlockState(),
            Blocks.SANDSTONE_WALL.defaultBlockState());

    private static final RoadPalette SAVANNA = new RoadPalette(
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.ACACIA_FENCE.defaultBlockState());

    private static final RoadPalette TAIGA = new RoadPalette(
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.SPRUCE_FENCE.defaultBlockState());

    private static final RoadPalette SNOWY = new RoadPalette(
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.SPRUCE_FENCE.defaultBlockState());

    private RoadPalettes() {
    }

    public static RoadPalette at(LevelReader level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (biome.is(Biomes.DESERT)) {
            return DESERT;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return SAVANNA;
        }
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.SNOWY_SLOPES)) {
            return SNOWY;
        }
        if (biome.is(BiomeTags.IS_TAIGA)) {
            return TAIGA;
        }
        return DEFAULT;
    }

    /** Deterministic surface pick: mostly path blocks with scattered biome accents. */
    public static BlockState surfaceAt(RoadPalette palette, int x, int z, boolean wet) {
        if (wet) {
            return palette.wetSurface();
        }
        long hash = x * 341873128712L + z * 132897987541L;
        hash = hash * 0x27D4EB2F165667C5L + 0x9E3779B97F4A7C15L;
        return Math.floorMod(hash >> 17, 100) < 22 ? palette.accent() : palette.surface();
    }
}
