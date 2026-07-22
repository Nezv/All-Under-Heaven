package dev.nez.allunderheaven.feature.dragonforge;

import dev.nez.allunderheaven.registry.ModMenus;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Dragon-lord Forge menu — three slots (star-forged steel in, dragon blood
 * fuel, dragon-lord steel out) plus the player inventory, mirroring the
 * vanilla furnace layout so the reused GUI reads instantly.
 */
public class DragonlordForgeMenu extends AbstractContainerMenu {
    private final Container forge;
    private final ContainerData data;

    /** Client factory: rebuilds from the synced block position. */
    public DragonlordForgeMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, resolve(inv, buf), new net.minecraft.world.inventory.SimpleContainerData(
                DragonlordForgeBlockEntity.DATA_COUNT));
    }

    public DragonlordForgeMenu(int id, Inventory inv, Container forge, ContainerData data) {
        super(ModMenus.DRAGONLORD_FORGE.get(), id);
        checkContainerSize(forge, 3);
        this.forge = forge;
        this.data = data;

        this.addSlot(new Slot(forge, DragonlordForgeBlockEntity.SLOT_INPUT, 56, 17) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.STAR_FORGED_STEEL);
            }
        });
        this.addSlot(new Slot(forge, DragonlordForgeBlockEntity.SLOT_FUEL, 56, 53) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.DRAGON_BLOOD);
            }
        });
        this.addSlot(new Slot(forge, DragonlordForgeBlockEntity.SLOT_OUTPUT, 116, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, 142));
        }
        this.addDataSlots(data);
    }

    private static Container resolve(Inventory inv, RegistryFriendlyByteBuf buf) {
        var be = inv.player.level().getBlockEntity(buf.readBlockPos());
        return be instanceof Container c ? c : new SimpleContainer(3);
    }

    public boolean isLit() {
        return this.data.get(0) > 0;
    }

    /** Fuel flame fill, 0..1. */
    public float burnProgress() {
        int total = this.data.get(1);
        return total == 0 ? 0.0F : this.data.get(0) / (float) total;
    }

    /** Forge arrow fill, 0..1. */
    public float cookProgress() {
        int total = this.data.get(3);
        return total == 0 ? 0.0F : this.data.get(2) / (float) total;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.forge.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < 3) {                              // forge -> player
            if (!this.moveItemStackTo(stack, 3, 39, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, result);
        } else {                                      // player -> forge / inv
            if (stack.is(ModItems.STAR_FORGED_STEEL)) {
                if (!this.moveItemStackTo(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.is(ModItems.DRAGON_BLOOD)) {
                if (!this.moveItemStackTo(stack, 1, 2, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < 30) {                  // inv -> hotbar
                if (!this.moveItemStackTo(stack, 30, 39, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 3, 30, false)) {  // hotbar -> inv
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return result;
    }
}
