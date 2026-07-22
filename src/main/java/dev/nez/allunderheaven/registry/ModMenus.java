package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonforge.DragonlordForgeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Container menu types of the mod. */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<DragonlordForgeMenu>>
            DRAGONLORD_FORGE = MENUS.register("dragonlord_forge",
                    () -> IMenuTypeExtension.create(DragonlordForgeMenu::new));

    private ModMenus() {
    }
}
