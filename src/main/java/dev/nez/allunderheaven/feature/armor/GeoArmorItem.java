package dev.nez.allunderheaven.feature.armor;

import java.util.function.Consumer;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.client.GeoRenderProvider;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.renderer.GeoArmorRenderer;
import com.geckolib.util.GeckoLibUtil;

import dev.nez.allunderheaven.client.armor.ModArmorRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A GeckoLib-rendered armour piece. The two kits are one class parameterised by
 * {@code tier} ({@code "star_forged"} / {@code "dragonlord"}); the tier selects
 * the geo model + texture + animation via {@link ModArmorRenderer}. The piece
 * still carries vanilla armour data components (from {@code .humanoidArmor(..)}
 * on its Properties), so defence, enchanting and the smithing-table trim slot
 * behave normally — only the WORN rendering is swapped for real 3D geometry
 * (so the trim overlay itself doesn't draw; that's the cost of custom armour).
 *
 * <p>GeckoLib's instance cache lazily invokes {@link #createGeoRenderer} on the
 * physical client, so no extra client-init registration is required.
 */
public class GeoArmorItem extends Item implements GeoItem {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private final String tier;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public GeoArmorItem(Properties properties, String tier) {
        super(properties);
        this.tier = tier;
    }

    public String tier() {
        return this.tier;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static plate: a single idle controller keeps the model in its rest pose.
        controllers.add(new AnimationController<GeoArmorItem>("idle",
                state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private ModArmorRenderer renderer;

            @Override
            public GeoArmorRenderer<?, ?> getGeoArmorRenderer(ItemStack stack, EquipmentSlot slot) {
                if (this.renderer == null) {
                    this.renderer = new ModArmorRenderer(GeoArmorItem.this.tier);
                }
                return this.renderer;
            }
        });
    }
}
