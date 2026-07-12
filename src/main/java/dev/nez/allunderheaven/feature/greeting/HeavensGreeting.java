package dev.nez.allunderheaven.feature.greeting;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.Config;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Minimal example of an event-driven feature: greets the log when a server
 * (integrated or dedicated) starts. Use this package as the template for real
 * features — self-contained, auto-subscribed, config-gated.
 */
@EventBusSubscriber(modid = AllUnderHeaven.MOD_ID)
public final class HeavensGreeting {
    private HeavensGreeting() {
    }

    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent event) {
        if (Config.ENABLE_SERVER_GREETING.getAsBoolean()) {
            AllUnderHeaven.LOGGER.info("[All Under Heaven] The realm awakens — all under heaven is one world.");
        }
    }
}
