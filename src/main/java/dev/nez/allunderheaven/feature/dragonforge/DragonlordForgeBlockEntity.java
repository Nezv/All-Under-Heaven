package dev.nez.allunderheaven.feature.dragonforge;

import dev.nez.allunderheaven.registry.ModBlockEntities;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Dragon-lord Forge's block entity — a furnace by another name, reusing
 * the vanilla burn/cook loop shape but with a single hard-wired job: it
 * reworks Star-forged Steel (slot 0) into Dragon-lord Steel (slot 2), burning
 * Dragon Blood (slot 1) as its only fuel. One phial of blood forges one ingot.
 */
public class DragonlordForgeBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_OUTPUT = 2;
    public static final int DATA_COUNT = 4;

    private static final int COOK_TIME = 200;
    private static final int BURN_PER_BLOOD = 220;   // one phial ≈ one ingot

    private final NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
    private int litTimeRemaining;
    private int litTotalTime;
    private int cookingTimer;
    private int cookingTotalTime;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> litTimeRemaining;
                case 1 -> litTotalTime;
                case 2 -> cookingTimer;
                default -> cookingTotalTime;
            };
        }

        @Override
        public void set(int i, int v) {
            switch (i) {
                case 0 -> litTimeRemaining = v;
                case 1 -> litTotalTime = v;
                case 2 -> cookingTimer = v;
                default -> cookingTotalTime = v;
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public DragonlordForgeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRAGONLORD_FORGE.get(), pos, state);
    }

    private boolean isLit() {
        return this.litTimeRemaining > 0;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
            DragonlordForgeBlockEntity forge) {
        boolean wasLit = forge.isLit();
        boolean changed = false;
        if (forge.isLit()) {
            forge.litTimeRemaining--;
        }

        ItemStack input = forge.items.get(SLOT_INPUT);
        ItemStack fuel = forge.items.get(SLOT_FUEL);
        boolean canForge = forge.canForge(input);

        if (!forge.isLit() && canForge && fuel.is(ModItems.DRAGON_BLOOD)) {
            forge.litTimeRemaining = BURN_PER_BLOOD;
            forge.litTotalTime = BURN_PER_BLOOD;
            fuel.shrink(1);
            changed = true;
        }

        if (forge.isLit() && canForge) {
            forge.cookingTotalTime = COOK_TIME;
            if (++forge.cookingTimer >= COOK_TIME) {
                forge.cookingTimer = 0;
                forge.forgeOne(input);
                changed = true;
            }
        } else {
            forge.cookingTimer = Math.max(0, forge.cookingTimer - 2);
        }

        if (wasLit != forge.isLit()) {
            changed = true;
            state = state.setValue(DragonlordForgeBlock.LIT, forge.isLit());
            level.setBlock(pos, state, 3);
        }
        if (changed) {
            setChanged(level, pos, state);
        }
    }

    private boolean canForge(ItemStack input) {
        if (!input.is(ModItems.STAR_FORGED_STEEL)) {
            return false;
        }
        ItemStack out = this.items.get(SLOT_OUTPUT);
        return out.isEmpty()
                || (out.is(ModItems.DRAGONLORD_STEEL) && out.getCount() < out.getMaxStackSize());
    }

    private void forgeOne(ItemStack input) {
        ItemStack out = this.items.get(SLOT_OUTPUT);
        if (out.isEmpty()) {
            this.items.set(SLOT_OUTPUT, new ItemStack(ModItems.DRAGONLORD_STEEL.get()));
        } else {
            out.grow(1);
        }
        input.shrink(1);
    }

    // --------------------------------------------------------- save / load

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.items.clear();
        ContainerHelper.loadAllItems(in, this.items);
        this.litTimeRemaining = in.getIntOr("lit_time_remaining", 0);
        this.litTotalTime = in.getIntOr("lit_total_time", 0);
        this.cookingTimer = in.getIntOr("cooking_time_spent", 0);
        this.cookingTotalTime = in.getIntOr("cooking_total_time", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        ContainerHelper.saveAllItems(out, this.items);
        out.putInt("lit_time_remaining", this.litTimeRemaining);
        out.putInt("lit_total_time", this.litTotalTime);
        out.putInt("cooking_time_spent", this.cookingTimer);
        out.putInt("cooking_total_time", this.cookingTotalTime);
    }

    // ------------------------------------------------------------ Container

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public boolean isEmpty() {
        return this.items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(this.items, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        stack.limitSize(this.getMaxStackSize(stack));
        this.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    // ---------------------------------------------------------- MenuProvider

    ContainerData getContainerData() {
        return this.data;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.allunderheaven.dragonlord_forge");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new DragonlordForgeMenu(id, inv, this, this.data);
    }
}
