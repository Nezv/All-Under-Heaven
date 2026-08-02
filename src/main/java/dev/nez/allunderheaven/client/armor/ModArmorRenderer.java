package dev.nez.allunderheaven.client.armor;

import com.geckolib.renderer.GeoArmorRenderer;

import dev.nez.allunderheaven.feature.armor.GeoArmorItem;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * GeckoLib armour renderer for a {@link GeoArmorItem}. GeckoLib's
 * {@code HumanoidArmorLayerMixin} maps each {@code armor*} bone onto the
 * matching body part of the wearer, so this is a thin binding of the tier's
 * {@link ModArmorModel}.
 */
public class ModArmorRenderer extends GeoArmorRenderer<GeoArmorItem, HumanoidRenderState> {
    public ModArmorRenderer(String tier) {
        super(new ModArmorModel(tier));
    }
}
