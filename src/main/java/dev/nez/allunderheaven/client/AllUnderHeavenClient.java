package dev.nez.allunderheaven.client;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entrypoint. This class is never loaded on dedicated servers, so
 * client-side classes (rendering, screens, input) are safe to touch from here.
 */
@Mod(value = AllUnderHeaven.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID, value = Dist.CLIENT)
public class AllUnderHeavenClient {
    public AllUnderHeavenClient(ModContainer container) {
        // Gives the mod an auto-generated config screen (Mods screen > All Under Heaven > Config).
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        AllUnderHeaven.LOGGER.info("[All Under Heaven] client setup complete");
    }
}
