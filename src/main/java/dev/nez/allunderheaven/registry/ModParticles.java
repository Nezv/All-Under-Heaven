package dev.nez.allunderheaven.registry;

import com.mojang.serialization.MapCodec;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Particles. {@code dragon_flame} carries an RGB color per particle
 * ({@link ColorParticleOption}), so each dragon variant breathes its own
 * fire — the sprite ships grayscale and the client tints it.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, ParticleType<ColorParticleOption>> DRAGON_FLAME =
            PARTICLE_TYPES.register("dragon_flame", () -> new ParticleType<ColorParticleOption>(false) {
                @Override
                public MapCodec<ColorParticleOption> codec() {
                    return ColorParticleOption.codec(this);
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, ColorParticleOption> streamCodec() {
                    return ColorParticleOption.streamCodec(this);
                }
            });

    private ModParticles() {
    }
}
