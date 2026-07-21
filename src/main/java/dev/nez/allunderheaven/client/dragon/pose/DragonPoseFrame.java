package dev.nez.allunderheaven.client.dragon.pose;

import java.util.HashMap;
import java.util.Map;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.renderer.base.BoneSnapshots;

/**
 * The smoothed result of the {@link DragonPoseSolver}, held per-entity on the
 * client so its springs persist across frames. Stores generator-space rotation
 * deltas (degrees) per bone plus a body tilt; {@link #apply} lays them on top
 * of the animation keyframes already baked into the render snapshots.
 *
 * <p>Generator space converts to GeckoLib's snapshot convention with
 * {@code (-x, -y, +z)} in radians — the same sign law the geometry and
 * animation exports use.
 */
public final class DragonPoseFrame {
    private static final float DEG2RAD = (float) (Math.PI / 180.0);

    /** Current (smoothed) per-bone deltas, generator degrees {x,y,z}. */
    private final Map<String, float[]> current = new HashMap<>();
    private float bodyPitch;   // generator +X degrees (nose up/down)
    private float bodyRoll;    // generator +Z degrees (bank)

    /** Eases every current delta toward {@code target}; bones absent from the
     *  target relax back to zero. {@code k} is the per-tick blend factor. */
    void blend(Map<String, float[]> target, float targetPitch, float targetRoll,
            float k) {
        for (Map.Entry<String, float[]> e : current.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                float[] c = e.getValue();
                c[0] += (0 - c[0]) * k;
                c[1] += (0 - c[1]) * k;
                c[2] += (0 - c[2]) * k;
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
    }

    /** Adds this frame's deltas onto the post-animation bone snapshots. */
    public void apply(BoneSnapshots snapshots) {
        addRot(snapshots, "body", bodyPitch, 0.0F, bodyRoll);
        for (Map.Entry<String, float[]> e : current.entrySet()) {
            float[] d = e.getValue();
            addRot(snapshots, e.getKey(), d[0], d[1], d[2]);
        }
    }

    private static void addRot(BoneSnapshots snapshots, String bone,
            float dxDeg, float dyDeg, float dzDeg) {
        if (dxDeg == 0.0F && dyDeg == 0.0F && dzDeg == 0.0F) {
            return;
        }
        BoneSnapshot s = snapshots.get(bone).orElse(null);
        if (s == null) {
            return;
        }
        // generator (x,y,z) deg -> GeckoLib snapshot (-x,-y,+z) rad, additive
        s.setRotation(s.getRotX() - dxDeg * DEG2RAD,
                s.getRotY() - dyDeg * DEG2RAD,
                s.getRotZ() + dzDeg * DEG2RAD);
    }
}
