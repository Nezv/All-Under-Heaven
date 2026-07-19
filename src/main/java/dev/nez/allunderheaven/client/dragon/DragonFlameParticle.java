package dev.nez.allunderheaven.client.dragon;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.RisingParticle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.util.RandomSource;

/**
 * The dragon's breath particle: the grayscale flame sprite tinted with the
 * color the server chose (the variant's fire gradient), rendered fullbright
 * and swelling briefly before it gutters out. Client-only by residence in
 * the client package (only ever referenced from the client entrypoint).
 */
public class DragonFlameParticle extends RisingParticle {
    protected DragonFlameParticle(ClientLevel level, double x, double y, double z,
            double xd, double yd, double zd, TextureAtlasSprite sprite, ColorParticleOption options) {
        super(level, x, y, z, xd, yd, zd, sprite);
        this.setColor(options.getRed(), options.getGreen(), options.getBlue());
        this.quadSize *= 2.6F + this.random.nextFloat() * 0.8F;
        this.lifetime = 12 + this.random.nextInt(10);
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.OPAQUE;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float life = (this.age + partialTick) / this.lifetime;
        return this.quadSize * (1.0F + 0.6F * life - 1.3F * life * life);
    }

    @Override
    public int getLightCoords(float partialTick) {
        return LightCoordsUtil.FULL_BRIGHT; // fire lights itself
    }

    public static class Provider implements ParticleProvider<ColorParticleOption> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(ColorParticleOption options, ClientLevel level,
                double x, double y, double z, double xd, double yd, double zd, RandomSource random) {
            return new DragonFlameParticle(level, x, y, z, xd, yd, zd, this.sprites.get(random), options);
        }
    }
}
