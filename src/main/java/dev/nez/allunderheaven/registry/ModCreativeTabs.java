package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative-mode tabs. The mod ships one tab holding all of its content; add
 * new items/blocks to {@code displayItems} below when you register them.
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + AllUnderHeaven.MOD_ID))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.JADE_SEAL.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.JADE_SEAL.get());
                        output.accept(ModItems.JADE_BLOCK_ITEM.get());
                        output.accept(ModItems.DRAGON_SPAWN_EGG.get());
                        // materials
                        output.accept(ModItems.STARDUST_ORE_ITEM.get());
                        output.accept(ModItems.STAR_DUST.get());
                        output.accept(ModItems.STAR_FORGED_STEEL.get());
                        output.accept(ModItems.DRAGON_BLOOD.get());
                        output.accept(ModItems.DRAGONLORD_STEEL.get());
                        // star-forged kit
                        output.accept(ModItems.STAR_FORGED_SWORD.get());
                        output.accept(ModItems.STAR_FORGED_PICKAXE.get());
                        output.accept(ModItems.STAR_FORGED_AXE.get());
                        output.accept(ModItems.STAR_FORGED_SHOVEL.get());
                        output.accept(ModItems.STAR_FORGED_HOE.get());
                        output.accept(ModItems.STAR_FORGED_HELMET.get());
                        output.accept(ModItems.STAR_FORGED_CHESTPLATE.get());
                        output.accept(ModItems.STAR_FORGED_LEGGINGS.get());
                        output.accept(ModItems.STAR_FORGED_BOOTS.get());
                        // dragon-lord kit
                        output.accept(ModItems.DRAGONLORD_SWORD.get());
                        output.accept(ModItems.DRAGONLORD_PICKAXE.get());
                        output.accept(ModItems.DRAGONLORD_AXE.get());
                        output.accept(ModItems.DRAGONLORD_SHOVEL.get());
                        output.accept(ModItems.DRAGONLORD_HOE.get());
                        output.accept(ModItems.DRAGONLORD_HELMET.get());
                        output.accept(ModItems.DRAGONLORD_CHESTPLATE.get());
                        output.accept(ModItems.DRAGONLORD_LEGGINGS.get());
                        output.accept(ModItems.DRAGONLORD_BOOTS.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
