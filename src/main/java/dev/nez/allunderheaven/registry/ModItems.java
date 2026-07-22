package dev.nez.allunderheaven.registry;

import java.util.function.Function;
import java.util.function.Supplier;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All items of the mod, including {@code BlockItem}s for blocks declared in
 * {@link ModBlocks}. Remember to add new items to a tab in {@link ModCreativeTabs}.
 *
 * <p>The two mod tiers each ship a full kit — ingot, sword, four tools and
 * four armour pieces — sharing a {@link ModMaterials} material. Star-forged is
 * the meteoric mid-endgame tier; Dragon-lord is the fire-forged top tier
 * (fire-immune, deep crimson).
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AllUnderHeaven.MOD_ID);

    /** Emblem of the realm — example of a plain item with tweaked properties. */
    public static final DeferredItem<Item> JADE_SEAL = ITEMS.registerSimpleItem("jade_seal",
            p -> p.stacksTo(1).rarity(Rarity.UNCOMMON));

    /** Block item for {@link ModBlocks#JADE_BLOCK}. */
    public static final DeferredItem<BlockItem> JADE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("jade_block", ModBlocks.JADE_BLOCK);

    /** Spawns a dragon with a random variant (dev/testing convenience).
     *  Safe ordering: entity types register before items in registry order. */
    public static final DeferredItem<Item> DRAGON_SPAWN_EGG =
            ITEMS.registerItem("dragon_spawn_egg", net.minecraft.world.item.SpawnEggItem::new,
                    () -> new Item.Properties().spawnEgg(ModEntities.DRAGON.get()).rarity(Rarity.EPIC));

    // ------------------------------------------------------------ materials

    /** Mined from a fallen star / crater (diamond pickaxe), the raw grit. */
    public static final DeferredItem<Item> STAR_DUST = ITEMS.registerSimpleItem("star_dust",
            p -> p.rarity(Rarity.UNCOMMON));

    /** Smelted from star dust in a blast furnace — the mid-endgame ingot. */
    public static final DeferredItem<Item> STAR_FORGED_STEEL =
            ITEMS.registerSimpleItem("star_forged_steel", p -> p.rarity(Rarity.RARE));

    /** Star-forged Steel reworked in the Dragon-lord Forge on dragon blood. */
    public static final DeferredItem<Item> DRAGONLORD_STEEL =
            ITEMS.registerSimpleItem("dragonlord_steel", p -> p.rarity(Rarity.EPIC).fireResistant());

    /** Bled from a slain dragon — the Dragon-lord Forge's fuel. */
    public static final DeferredItem<Item> DRAGON_BLOOD = ITEMS.registerSimpleItem("dragon_blood",
            p -> p.rarity(Rarity.RARE));

    /** Block item for the stardust ore. */
    public static final DeferredItem<BlockItem> STARDUST_ORE_ITEM =
            ITEMS.registerSimpleBlockItem("stardust_ore", ModBlocks.STARDUST_ORE);

    // ------------------------------------------------------- star-forged kit

    public static final DeferredItem<Item> STAR_FORGED_SWORD =
            sword("star_forged_sword", ModMaterials.STAR_FORGED_TOOL, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_PICKAXE =
            pickaxe("star_forged_pickaxe", ModMaterials.STAR_FORGED_TOOL, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_AXE =
            axe("star_forged_axe", ModMaterials.STAR_FORGED_TOOL, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_SHOVEL =
            shovel("star_forged_shovel", ModMaterials.STAR_FORGED_TOOL, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_HOE =
            hoe("star_forged_hoe", ModMaterials.STAR_FORGED_TOOL, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_HELMET =
            armor("star_forged_helmet", ModMaterials.STAR_FORGED_ARMOR, ArmorType.HELMET, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_CHESTPLATE =
            armor("star_forged_chestplate", ModMaterials.STAR_FORGED_ARMOR, ArmorType.CHESTPLATE, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_LEGGINGS =
            armor("star_forged_leggings", ModMaterials.STAR_FORGED_ARMOR, ArmorType.LEGGINGS, Rarity.RARE, false);
    public static final DeferredItem<Item> STAR_FORGED_BOOTS =
            armor("star_forged_boots", ModMaterials.STAR_FORGED_ARMOR, ArmorType.BOOTS, Rarity.RARE, false);

    // ------------------------------------------------------ dragon-lord kit
    // (fire-resistant: won't burn in lava, fitting the fire-forged theme)

    public static final DeferredItem<Item> DRAGONLORD_SWORD =
            sword("dragonlord_sword", ModMaterials.DRAGONLORD_TOOL, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_PICKAXE =
            pickaxe("dragonlord_pickaxe", ModMaterials.DRAGONLORD_TOOL, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_AXE =
            axe("dragonlord_axe", ModMaterials.DRAGONLORD_TOOL, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_SHOVEL =
            shovel("dragonlord_shovel", ModMaterials.DRAGONLORD_TOOL, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_HOE =
            hoe("dragonlord_hoe", ModMaterials.DRAGONLORD_TOOL, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_HELMET =
            armor("dragonlord_helmet", ModMaterials.DRAGONLORD_ARMOR, ArmorType.HELMET, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_CHESTPLATE =
            armor("dragonlord_chestplate", ModMaterials.DRAGONLORD_ARMOR, ArmorType.CHESTPLATE, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_LEGGINGS =
            armor("dragonlord_leggings", ModMaterials.DRAGONLORD_ARMOR, ArmorType.LEGGINGS, Rarity.EPIC, true);
    public static final DeferredItem<Item> DRAGONLORD_BOOTS =
            armor("dragonlord_boots", ModMaterials.DRAGONLORD_ARMOR, ArmorType.BOOTS, Rarity.EPIC, true);

    // -------------------------------------------------------------- helpers

    private static Supplier<Item.Properties> props(Rarity rarity, boolean fireproof) {
        return () -> {
            Item.Properties p = new Item.Properties().rarity(rarity);
            return fireproof ? p.fireResistant() : p;
        };
    }

    private static DeferredItem<Item> sword(String name, ToolMaterial mat, Rarity r, boolean fp) {
        return ITEMS.registerItem(name, Item::new,
                () -> apply(new Item.Properties().sword(mat, 3.0F, -2.4F), r, fp));
    }

    private static DeferredItem<Item> pickaxe(String name, ToolMaterial mat, Rarity r, boolean fp) {
        return ITEMS.registerItem(name, Item::new,
                () -> apply(new Item.Properties().pickaxe(mat, 1.0F, -2.8F), r, fp));
    }

    private static DeferredItem<Item> shovel(String name, ToolMaterial mat, Rarity r, boolean fp) {
        return tool(name, p -> new ShovelItem(mat, 1.5F, -3.0F, p), r, fp);
    }

    private static DeferredItem<Item> axe(String name, ToolMaterial mat, Rarity r, boolean fp) {
        return tool(name, p -> new AxeItem(mat, 5.0F, -3.0F, p), r, fp);
    }

    private static DeferredItem<Item> hoe(String name, ToolMaterial mat, Rarity r, boolean fp) {
        return tool(name, p -> new HoeItem(mat, -3.0F, 0.0F, p), r, fp);
    }

    private static DeferredItem<Item> armor(String name, ArmorMaterial mat, ArmorType type, Rarity r, boolean fp) {
        return ITEMS.registerItem(name, Item::new,
                () -> apply(new Item.Properties().humanoidArmor(mat, type), r, fp));
    }

    private static DeferredItem<Item> tool(String name, Function<Item.Properties, ? extends Item> factory,
            Rarity r, boolean fp) {
        return ITEMS.registerItem(name, factory, props(r, fp));
    }

    private static Item.Properties apply(Item.Properties p, Rarity r, boolean fp) {
        p = p.rarity(r);
        return fp ? p.fireResistant() : p;
    }

    private ModItems() {
    }
}
