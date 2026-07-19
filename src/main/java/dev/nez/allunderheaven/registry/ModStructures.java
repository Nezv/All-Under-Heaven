package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonnest.DragonNestPiece;
import dev.nez.allunderheaven.feature.dragonnest.DragonNestStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Worldgen structures. Placement is data-driven:
 * {@code worldgen/structure/dragon_nest.json} +
 * {@code worldgen/structure_set/dragon_nests.json} (random_spread, spacing 63
 * chunks ≈ every 1000 blocks).
 */
public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, AllUnderHeaven.MOD_ID);
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<DragonNestStructure>> DRAGON_NEST =
            STRUCTURE_TYPES.register("dragon_nest", () -> () -> DragonNestStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> DRAGON_NEST_PIECE =
            STRUCTURE_PIECES.register("dragon_nest_piece",
                    () -> (StructurePieceType) (context, tag) -> new DragonNestPiece(tag));

    private ModStructures() {
    }
}
