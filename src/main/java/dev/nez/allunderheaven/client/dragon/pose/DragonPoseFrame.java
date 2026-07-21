package dev.nez.allunderheaven.client.dragon.pose;

import java.util.HashMap;
import java.util.Map;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.renderer.base.BoneSnapshots;

import dev.nez.allunderheaven.AllUnderHeaven;

/**
 * The smoothed output of the {@link DragonPoseSolver}, kept per-entity on the
 * client so its easing persists across frames. Holds generator-space rotation
 * deltas (degrees) per bone plus body pitch/roll and a vertical settle;
 * {@link #apply} lays them on top of the animation keyframes already baked
 * into the render-pass snapshots.
 *
 * <p>Generator rotations convert to GeckoLib's snapshot convention with
 * {@code (-x, -y, +z)} in radians — the same sign law the geometry and
 * animation exports use. The settle is a straight downward translation of the
 * body root (generator units, +Y up on both sides).
 *
 * <p>The first time a meaningfully non-zero frame is applied to real
 * snapshots, one INFO line is logged — the end-to-end liveness proof that the
 * solver's output actually reaches the renderer.
 */
public final class DragonPoseFrame {
    private static final float DEG2RAD = (float) (Math.PI / 180.0);
    private static boolean announced;

    /** Smoothed per-bone deltas, generator degrees {x,y,z}. */
    private final Map<String, float[]> current = new HashMap<>();
    private float bodyPitch;    // generator +X degrees (nose up)
    private float bodyRoll;     // generator +Z degrees (left side up)
    private float settle;       // generator units the body root drops

    /** Eases every delta toward {@code target}; bones absent from the target
     *  relax back to zero. {@code k} is the per-frame blend factor. */
    void blend(Map<String, float[]> target, float targetPitch, float targetRoll,
            float targetSettle, float k) {
        for (Map.Entry<String, float[]> e : current.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                float[] c = e.getValue();
                c[0] -= c[0] * k;
                c[1] -= c[1] * k;
                c[2] -= c[2] * k;
            }
        }
        for (Map.Entry<String, float[]> e : target.entrySet()) {
            float[] c = current.computeIfAbsent(e.getKey(), n -> new float[3]);
            float[] t = e.getValue();
            c[0] += (t[0] - c[0]) * k;
            c[1] += (t[1] - c[1]) * k;
            c[2] += (t[2] - c[2]) * k;
        }
        bodyPitch += (targetPitch - bodyPitch) * k;
        bodyRoll += (targetRoll - bodyRoll) * k;
        settle += (targetSettle - settle) * k;
    }

    /** Relaxes everything toward zero (used in flight). */
    void blendToRest(float k) {
        blend(Map.of(), 0.0F, 0.0F, 0.0F, k);
    }

    /** Adds this frame's deltas onto the post-animation bone snapshots. */
    public void apply(BoneSnapshots snapshots) {
        int touched = 0;
        float max = 0.0F;
        BoneSnapshot body = snapshots.get("body").orElse(null);
        if (body != null) {
            if (bodyPitch != 0.0F || bodyRoll != 0.0F) {
                body.setRotation(body.getRotX() - bodyPitch * DEG2RAD,
                        body.getRotY(), body.getRotZ() + bodyRoll * DEG2RAD);
            }
            if (settle != 0.0F) {
                body.setTranslateY(body.getTranslateY() - settle);
            }
            touched++;
            max = Math.max(max, Math.max(Math.abs(bodyPitch), Math.abs(bodyRoll)));
        }
        for (Map.Entry<String, float[]> e : current.entrySet()) {
            float[] d = e.getValue();
            if (Math.abs(d[0]) < 0.02F && Math.abs(d[1]) < 0.02F
                    && Math.abs(d[2]) < 0.02F) {
                continue;
            }
            BoneSnapshot s = snapshots.get(e.getKey()).orElse(null);
            if (s == null) {
                continue;
            }
            s.setRotation(s.getRotX() - d[0] * DEG2RAD,
                    s.getRotY() - d[1] * DEG2RAD,
                    s.getRotZ() + d[2] * DEG2RAD);
            touched++;
            max = Math.max(max, Math.max(Math.abs(d[0]),
                    Math.max(Math.abs(d[1]), Math.abs(d[2]))));
        }
        if (!announced && touched > 1 && (max > 1.0F || settle > 1.0F)) {
            announced = true;
            AllUnderHeaven.LOGGER.info("[All Under Heaven] dragon pose solver "
                    + "LIVE: adjusted {} bones this frame (max delta {} deg, "
                    + "body settle {} units)", touched,
                    String.format("%.1f", max), String.format("%.1f", settle));
        }
    }
}
