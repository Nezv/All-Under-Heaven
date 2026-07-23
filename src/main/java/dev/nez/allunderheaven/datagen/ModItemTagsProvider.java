package dev.nez.allunderheaven.datagen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.registry.ModItems;
import dev.nez.allunderheaven.registry.ModMaterials;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagCopyingItemTagProvider;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Item tags. Beyond the repair-ingredient tags, this wires both mod kits into
 * the vanilla {@code enchantable/*} and {@code trimmable_armor} tags so the
 * gear behaves like ordinary Minecraft gear: it takes the right enchantments at
 * a table/anvil, and armour can be trimmed at a smithing table. Tags are built
 * by path so they stay correct regardless of yarn/mojmap field names.
 */
public class ModItemTagsProvider extends BlockTagCopyingItemTagProvider {
    public ModItemTagsProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            CompletableFuture<TagsProvider.TagLookup<Block>> blockTags) {
        super(output, lookupProvider, blockTags, AllUnderHeaven.MOD_ID);
    }

    private static TagKey<Item> mc(String path) {
        return TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace(path));
    }

    @SafeVarargs
    private static List<DeferredItem<Item>> cat(List<DeferredItem<Item>>... lists) {
        List<DeferredItem<Item>> out = new ArrayList<>();
        for (List<DeferredItem<Item>> l : lists) {
            out.addAll(l);
        }
        return out;
    }

    private void addAll(TagKey<Item> tag, List<DeferredItem<Item>> items) {
        var appender = this.tag(tag);
        for (DeferredItem<Item> it : items) {
            appender.add(it.getKey());
        }
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // repair-ingredient tags for the two mod materials
        this.tag(ModMaterials.STAR_FORGED_INGOTS).add(ModItems.STAR_FORGED_STEEL.getKey());
        this.tag(ModMaterials.DRAGONLORD_INGOTS).add(ModItems.DRAGONLORD_STEEL.getKey());

        List<DeferredItem<Item>> swords = List.of(ModItems.STAR_FORGED_SWORD, ModItems.DRAGONLORD_SWORD);
        List<DeferredItem<Item>> pickaxes = List.of(ModItems.STAR_FORGED_PICKAXE, ModItems.DRAGONLORD_PICKAXE);
        List<DeferredItem<Item>> axes = List.of(ModItems.STAR_FORGED_AXE, ModItems.DRAGONLORD_AXE);
        List<DeferredItem<Item>> shovels = List.of(ModItems.STAR_FORGED_SHOVEL, ModItems.DRAGONLORD_SHOVEL);
        List<DeferredItem<Item>> hoes = List.of(ModItems.STAR_FORGED_HOE, ModItems.DRAGONLORD_HOE);
        List<DeferredItem<Item>> helmets = List.of(ModItems.STAR_FORGED_HELMET, ModItems.DRAGONLORD_HELMET);
        List<DeferredItem<Item>> chestplates = List.of(ModItems.STAR_FORGED_CHESTPLATE, ModItems.DRAGONLORD_CHESTPLATE);
        List<DeferredItem<Item>> leggings = List.of(ModItems.STAR_FORGED_LEGGINGS, ModItems.DRAGONLORD_LEGGINGS);
        List<DeferredItem<Item>> boots = List.of(ModItems.STAR_FORGED_BOOTS, ModItems.DRAGONLORD_BOOTS);

        List<DeferredItem<Item>> mining = cat(pickaxes, shovels, axes, hoes);   // Efficiency/Fortune/Silk
        List<DeferredItem<Item>> armor = cat(helmets, chestplates, leggings, boots);
        List<DeferredItem<Item>> everything = cat(swords, mining, armor);

        // --- weapons ---
        addAll(mc("enchantable/sword"), swords);
        addAll(mc("enchantable/fire_aspect"), swords);
        addAll(mc("enchantable/weapon"), cat(swords, axes));        // Sharpness/Smite/Bane

        // --- mining tools ---
        addAll(mc("enchantable/mining"), mining);                   // Efficiency
        addAll(mc("enchantable/mining_loot"), mining);              // Fortune / Silk Touch

        // --- armour ---
        addAll(mc("enchantable/armor"), armor);                     // Protection family, Thorns
        addAll(mc("enchantable/equippable"), armor);                // Curse of Binding
        addAll(mc("enchantable/head_armor"), helmets);             // Respiration, Aqua Affinity
        addAll(mc("enchantable/chest_armor"), chestplates);
        addAll(mc("enchantable/leg_armor"), leggings);              // Swift Sneak
        addAll(mc("enchantable/foot_armor"), boots);               // Feather Falling, Depth Strider, Soul Speed

        // --- shared: Unbreaking/Mending + Curse of Vanishing on all of it ---
        addAll(mc("enchantable/durability"), everything);
        addAll(mc("enchantable/vanishing"), everything);

        // --- smithing-table armour trims ---
        addAll(mc("trimmable_armor"), armor);
    }
}
