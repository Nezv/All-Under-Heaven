package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonforge.DragonlordForgeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity types of the mod. */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DragonlordForgeBlockEntity>>
            DRAGONLORD_FORGE = BLOCK_ENTITIES.register("dragonlord_forge",
                    () -> new BlockEntityType<>(DragonlordForgeBlockEntity::new,
                            ModBlocks.DRAGONLORD_FORGE.get()));

    private ModBlockEntities() {
    }
}
