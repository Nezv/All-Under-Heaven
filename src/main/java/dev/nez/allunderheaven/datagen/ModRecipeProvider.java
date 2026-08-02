package dev.nez.allunderheaven.datagen;

import java.util.concurrent.CompletableFuture;

import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Crafting recipes. Add new recipes in {@link #buildRecipes()}; the nested
 * {@link Runner} is what actually gets registered to the event.
 */
public class ModRecipeProvider extends RecipeProvider {
    protected ModRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        // 2x2 emeralds -> jade block
        ShapedRecipeBuilder.shaped(this.items, RecipeCategory.BUILDING_BLOCKS, ModBlocks.JADE_BLOCK.get())
                .pattern("EE")
                .pattern("EE")
                .define('E', Items.EMERALD)
                .unlockedBy("has_emerald", this.has(Items.EMERALD))
                .save(this.output);

        // jade block + gold ingot -> jade seal
        ShapelessRecipeBuilder.shapeless(this.items, RecipeCategory.MISC, ModItems.JADE_SEAL.get())
                .requires(ModBlocks.JADE_BLOCK.get())
                .requires(Items.GOLD_INGOT)
                .unlockedBy("has_jade_block", this.has(ModBlocks.JADE_BLOCK.get()))
                .save(this.output);

        // --- star dust -> star-forged steel: the "usual forge" is the furnace
        Ingredient dust = Ingredient.of(ModItems.STAR_DUST);
        SimpleCookingRecipeBuilder.blasting(dust, RecipeCategory.MISC, CookingBookCategory.MISC,
                        ModItems.STAR_FORGED_STEEL.get(), 0.8F, 100)
                .unlockedBy("has_star_dust", this.has(ModItems.STAR_DUST))
                .save(this.output, "star_forged_steel_from_blasting");
        SimpleCookingRecipeBuilder.smelting(dust, RecipeCategory.MISC, CookingBookCategory.MISC,
                        ModItems.STAR_FORGED_STEEL.get(), 0.8F, 200)
                .unlockedBy("has_star_dust", this.has(ModItems.STAR_DUST))
                .save(this.output, "star_forged_steel_from_smelting");

        // --- the two equipment kits (Dragon-lord steel itself comes from the
        // Dragon-lord Forge; its gear crafts once you have the ingots)
        kit(ModItems.STAR_FORGED_STEEL,
                ModItems.STAR_FORGED_SWORD, ModItems.STAR_FORGED_PICKAXE, ModItems.STAR_FORGED_AXE,
                ModItems.STAR_FORGED_SHOVEL, ModItems.STAR_FORGED_HOE, ModItems.STAR_FORGED_HELMET,
                ModItems.STAR_FORGED_CHESTPLATE, ModItems.STAR_FORGED_LEGGINGS, ModItems.STAR_FORGED_BOOTS);
        kit(ModItems.DRAGONLORD_STEEL,
                ModItems.DRAGONLORD_SWORD, ModItems.DRAGONLORD_PICKAXE, ModItems.DRAGONLORD_AXE,
                ModItems.DRAGONLORD_SHOVEL, ModItems.DRAGONLORD_HOE, ModItems.DRAGONLORD_HELMET,
                ModItems.DRAGONLORD_CHESTPLATE, ModItems.DRAGONLORD_LEGGINGS, ModItems.DRAGONLORD_BOOTS);

        // the Dragon-lord Forge: an obsidian shell around a heart of dragon blood
        ShapedRecipeBuilder.shaped(this.items, RecipeCategory.MISC, ModBlocks.DRAGONLORD_FORGE.get())
                .pattern("OOO")
                .pattern("OBO")
                .pattern("OOO")
                .define('O', Items.OBSIDIAN)
                .define('B', ModItems.DRAGON_BLOOD.get())
                .unlockedBy("has_dragon_blood", this.has(ModItems.DRAGON_BLOOD.get()))
                .save(this.output);
    }

    /** Standard tool/armour crafting patterns for one metal ingot {@code m}. */
    private void kit(DeferredItem<Item> m, DeferredItem<Item> sword, DeferredItem<Item> pick,
            DeferredItem<Item> axe, DeferredItem<Item> shovel, DeferredItem<Item> hoe,
            DeferredItem<Item> helmet, DeferredItem<Item> chest, DeferredItem<Item> legs,
            DeferredItem<Item> boots) {
        String unlock = "has_" + m.getId().getPath();
        shape(sword, m, "M", "M", "S");
        shape(pick, m, "MMM", " S ", " S ");
        shape(axe, m, "MM", "MS", " S");
        shape(shovel, m, "M", "S", "S");
        shape(hoe, m, "MM", " S", " S");
        shape(helmet, m, "MMM", "M M");
        shape(chest, m, "M M", "MMM", "MMM");
        shape(legs, m, "MMM", "M M", "M M");
        shape(boots, m, "M M", "M M");
    }

    private void shape(DeferredItem<Item> result, DeferredItem<Item> metal, String... rows) {
        ShapedRecipeBuilder b = ShapedRecipeBuilder.shaped(this.items, RecipeCategory.COMBAT, result.get())
                .define('M', metal.get())
                .unlockedBy("has_" + metal.getId().getPath(), this.has(metal.get()));
        boolean usesStick = false;
        for (String row : rows) {
            b.pattern(row);
            usesStick |= row.indexOf('S') >= 0;
        }
        if (usesStick) {
            b.define('S', Items.STICK);
        }
        b.save(this.output);
    }

    public static class Runner extends RecipeProvider.Runner {
        public Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new ModRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "All Under Heaven recipes";
        }
    }
}
