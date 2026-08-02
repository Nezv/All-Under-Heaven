package dev.nez.allunderheaven.client.armor;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.armor.GeoArmorItem;
import net.minecraft.resources.Identifier;

/**
 * Resolves the geo model / texture / animation for one armour tier. One model
 * per SET covers all four slots (GeckoLib renders only the equipped slot's
 * bones). Resources follow GeckoLib 5 layout:
 *   geckolib/models/armor/&lt;tier&gt;.geo.json
 *   geckolib/animations/armor/&lt;tier&gt;.animation.json
 *   textures/entity/armor/&lt;tier&gt;.png
 */
public class ModArmorModel extends GeoModel<GeoArmorItem> {
    private final String tier;

    public ModArmorModel(String tier) {
        this.tier = tier;
    }

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return AllUnderHeaven.id("armor/" + this.tier);
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return AllUnderHeaven.id("textures/entity/armor/" + this.tier + ".png");
    }

    @Override
    public Identifier getAnimationResource(GeoArmorItem animatable) {
        return AllUnderHeaven.id("armor/" + this.tier);
    }
}
