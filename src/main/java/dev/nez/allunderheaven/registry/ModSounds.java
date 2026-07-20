package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events. Every dragon sample is ORIGINAL, synthesized from scratch
 * by {@code tools/dragon/build_sounds.py} (HotD-inspired sound design -
 * falling-pitch roars, chest subharmonics, furnace breath - but our own
 * audio, no sourced material).
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_GROWL = register("entity.dragon.growl");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_ROAR = register("entity.dragon.roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_DEATH = register("entity.dragon.death");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_FLAP = register("entity.dragon.flap");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_FIRE = register("entity.dragon.fire");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAGON_STEP = register("entity.dragon.step");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(AllUnderHeaven.id(name)));
    }

    private ModSounds() {
    }
}
