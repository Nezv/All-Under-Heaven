package dev.nez.allunderheaven.client.dragon;

import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * GeckoLib renderer with flight body language layered on top: the model
 * banks into turns (roll from the smoothed client yaw rate) and pitches
 * into climbs and dives (from velocity), so aerial arcs read as real
 * flying instead of a statue sliding through the sky.
 */
public class DragonRenderer extends GeoEntityRenderer<DragonEntity, EntityRenderState> {
    public DragonRenderer(EntityRendererProvider.Context context) {
        super(context, new DragonModel());
        this.shadowRadius = 2.6F;
    }

    /** Cull box padded out to the wingspan so wings never pop at screen edges. */
    @Override
    protected AABB getBoundingBoxForCulling(DragonEntity dragon) {
        return super.getBoundingBoxForCulling(dragon).inflate(5.0);
    }

    @Override
    public void addRenderData(DragonEntity dragon, Void relatedObject,
            EntityRenderState renderState, float partialTick) {
        GeoRenderState geoState = (GeoRenderState) renderState;
        geoState.addGeckolibData(DragonModel.VARIANT, dragon.getVariantId());
        geoState.addGeckolibData(DragonModel.BANK,
                Mth.lerp(partialTick, dragon.bankSmoothO, dragon.bankSmooth));
        geoState.addGeckolibData(DragonModel.PITCH,
                Mth.lerp(partialTick, dragon.pitchSmoothO, dragon.pitchSmooth));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected void applyRotations(RenderPassInfo pass, PoseStack poseStack, float nativeScale) {
        super.applyRotations(pass, poseStack, nativeScale);
        float pitch = (Float) pass.getGeckolibData(DragonModel.PITCH);
        float bank = (Float) pass.getGeckolibData(DragonModel.BANK);
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(bank));
    }
}
