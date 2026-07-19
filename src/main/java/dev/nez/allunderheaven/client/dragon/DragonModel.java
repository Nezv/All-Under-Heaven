package dev.nez.allunderheaven.client.dragon;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import dev.nez.allunderheaven.feature.dragon.DragonVariant;
import net.minecraft.resources.Identifier;

/**
 * Per-variant resource switching: the three wyverns are three different
 * geometries (the white has extra neck/tail segments), so model, texture AND
 * animation file all follow the variant. The variant travels to the render
 * thread inside the GeoRenderState via {@link #VARIANT}.
 */
public class DragonModel extends GeoModel<DragonEntity> {
    public static final DataTicket<Integer> VARIANT =
            DataTicket.create("allunderheaven_dragon_variant", Integer.class);
    public static final DataTicket<Float> BANK =
            DataTicket.create("allunderheaven_dragon_bank", Float.class);
    public static final DataTicket<Float> PITCH =
            DataTicket.create("allunderheaven_dragon_pitch", Float.class);

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return AllUnderHeaven.id("entity/wyvern_" + variantKey(renderState));
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return AllUnderHeaven.id("textures/entity/wyvern_" + variantKey(renderState) + ".png");
    }

    @Override
    public Identifier getAnimationResource(DragonEntity dragon) {
        return AllUnderHeaven.id("entity/wyvern_" + dragon.getVariant().key);
    }

    private static String variantKey(GeoRenderState renderState) {
        return DragonVariant.byId(renderState.getOrDefaultGeckolibData(VARIANT, 0)).key;
    }
}
