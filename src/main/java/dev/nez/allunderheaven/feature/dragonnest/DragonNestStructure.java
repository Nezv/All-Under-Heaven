package dev.nez.allunderheaven.feature.dragonnest;

import java.util.Optional;

import com.mojang.serialization.MapCodec;

import dev.nez.allunderheaven.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

/**
 * A dragon's gold hoard in the open: a stone nest bowl with a gold pile and
 * one resident dragon (random variant) bound to it. Placement comes from the
 * {@code dragon_nests} structure set (~every 1000 blocks); this class only
 * vetoes water — the surface probe is the same WORLD_SURFACE vs OCEAN_FLOOR
 * trick the roads use, so lakes/oceans/rivers never host a nest.
 */
public class DragonNestStructure extends Structure {
    public static final MapCodec<DragonNestStructure> CODEC = simpleCodec(DragonNestStructure::new);

    public DragonNestStructure(Structure.StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
        int x = context.chunkPos().getBlockX(8);
        int z = context.chunkPos().getBlockZ(8);
        int surface = context.chunkGenerator().getFirstOccupiedHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        int floor = context.chunkGenerator().getFirstOccupiedHeight(
                x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
        if (surface - floor >= 2) {
            return Optional.empty(); // open water at the nest center
        }
        BlockPos center = new BlockPos(x, surface, z);
        return onTopOfChunkCenter(context, Heightmap.Types.WORLD_SURFACE_WG,
                builder -> generatePieces(builder, center));
    }

    private static void generatePieces(StructurePiecesBuilder builder, BlockPos center) {
        builder.addPiece(new DragonNestPiece(center));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.DRAGON_NEST.get();
    }
}
