package dev.nez.allunderheaven.datagen;

import java.util.List;
import java.util.Set;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Entry point for data generation. Run {@code .\gradlew runData} — output is
 * written to {@code src/generated/resources} (commit it, never hand-edit it).
 *
 * <p>All providers are registered on {@link GatherDataEvent.Client} so a single
 * task generates both assets and data, matching the MDK's {@code data} run.
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID)
public final class DataGen {
    private DataGen() {
    }

    @SubscribeEvent
    static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(ModModelProvider::new);
        event.createProvider(ModRecipeProvider.Runner::new);
        event.createProvider((output, lookupProvider) -> new LootTableProvider(
                output,
                Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(ModBlockLootProvider::new, LootContextParamSets.BLOCK)),
                lookupProvider));
        event.createBlockAndItemTags(ModBlockTagsProvider::new, ModItemTagsProvider::new);
    }
}
