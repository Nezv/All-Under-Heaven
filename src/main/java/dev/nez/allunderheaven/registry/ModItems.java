package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All items of the mod, including {@code BlockItem}s for blocks declared in
 * {@link ModBlocks}. Remember to add new items to a tab in {@link ModCreativeTabs}.
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AllUnderHeaven.MOD_ID);

    /** Emblem of the realm — example of a plain item with tweaked properties. */
    public static final DeferredItem<Item> JADE_SEAL = ITEMS.registerSimpleItem("jade_seal",
            p -> p.stacksTo(1).rarity(Rarity.UNCOMMON));

    /** Block item for {@link ModBlocks#JADE_BLOCK}. */
    public static final DeferredItem<BlockItem> JADE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("jade_block", ModBlocks.JADE_BLOCK);

    private ModItems() {
    }
}
