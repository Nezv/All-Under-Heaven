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
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
