package dev.nez.allunderheaven.client;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.client.dragon.DragonFlameParticle;
import dev.nez.allunderheaven.client.dragon.DragonRenderer;
import dev.nez.allunderheaven.client.dragon.pose.DragonRig;
import dev.nez.allunderheaven.registry.ModEntities;
import dev.nez.allunderheaven.registry.ModParticles;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
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

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DRAGON.get(), DragonRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.DRAGON_FLAME.get(), DragonFlameParticle.Provider::new);
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        // Preloads the dragon pose rigs on every resource (re)load, so rig
        // status is logged at startup and F3+T without a dragon on screen.
        event.addListener(AllUnderHeaven.id("dragon_rigs"),
                (ResourceManagerReloadListener) DragonRig::preload);
    }
}
