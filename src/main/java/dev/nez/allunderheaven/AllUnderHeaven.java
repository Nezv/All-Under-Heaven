package dev.nez.allunderheaven;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModCreativeTabs;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Mod entrypoint. Keep this class slim: it only wires registries, config and
 * lifecycle listeners. Actual content lives in {@code registry/} and gameplay
 * logic in {@code feature/}.
 */
@Mod(AllUnderHeaven.MOD_ID)
public class AllUnderHeaven {
    public static final String MOD_ID = "allunderheaven";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AllUnderHeaven(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (Config.LOG_REGISTRY_SUMMARY.getAsBoolean()) {
            LOGGER.info("[All Under Heaven] loaded with {} item(s) and {} block(s)",
                    ModItems.ITEMS.getEntries().size(),
                    ModBlocks.BLOCKS.getEntries().size());
        }
    }

    /** Creates an {@link Identifier} in this mod's namespace. */
    public static Identifier id(String path) {
        return Identifier.parse(MOD_ID + ":" + path);
    }
}
