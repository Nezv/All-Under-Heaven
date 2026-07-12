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
import net.minecraft.world.item.Items;

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
