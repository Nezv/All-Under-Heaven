package dev.nez.allunderheaven.feature.dragonkeeper;

import java.util.Optional;

import com.mojang.serialization.MapCodec;

import dev.nez.allunderheaven.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

/**
 * The Dragon Keeper's Hut: a small ashen-timber dwelling that comes with a
 * Dragon-lord Forge (the keeper's workstation) and its resident Dragon Keeper.
 * Same water veto as the dragon nest so it never generates in a lake.
 */
public class DragonKeeperHutStructure extends Structure {
    public static final MapCodec<DragonKeeperHutStructure> CODEC =
            simpleCodec(DragonKeeperHutStructure::new);

    public DragonKeeperHutStructure(Structure.StructureSettings settings) {
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
            return Optional.empty(); // open water under the hut center
        }
        BlockPos center = new BlockPos(x, surface, z);
        return onTopOfChunkCenter(context, Heightmap.Types.WORLD_SURFACE_WG,
                builder -> builder.addPiece(new DragonKeeperHutPiece(center)));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.DRAGON_KEEPER_HUT.get();
    }
}
