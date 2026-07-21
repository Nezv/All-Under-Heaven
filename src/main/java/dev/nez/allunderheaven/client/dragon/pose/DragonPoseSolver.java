package dev.nez.allunderheaven.client.dragon.pose;

import java.util.HashMap;
import java.util.Map;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Spider-model terrain adaptation for a grounded dragon: every frame the world
 * under each limb and ahead of the head is raycast, then the skeleton bends so
 * the dragon fits the ground it is actually standing on —
 *
 * <ul>
 *   <li><b>each foot keeps its own level</b>: per-limb CCD retargets the
 *       contact point onto the terrain column under it. Hind legs hinge about
 *       X; the wing-forelimbs hinge about their frontal Z axes with the
 *       SHOULDER carrying most of the motion (the N°1 dynamic joint) so the
 *       whole tent rises and falls off the body joint like the reference;</li>
 *   <li><b>the body follows the feet</b>: it settles vertically toward the
 *       fore-knuckle contact deficit and leans gently to the support plane —
 *       the limbs absorb terrain first, the trunk averages;</li>
 *   <li><b>the head never enters a block</b>: the neck lifts until the head
 *       center clears the highest surface under and just ahead of it.</li>
 * </ul>
 *
 * All maths runs in the model's generator space (+Y up, faces -Z, 16 units per
 * block). Vertical is yaw-invariant, so the IK needs no yaw; body yaw only
 * places the raycast columns. Everything relaxes to zero in flight.
 *
 * <p>The CCD is proximal-biased (root first, distally damped step weights) so
 * small corrections look like natural joint motion instead of a folded-up
 * distal joint slammed to its clamp. Reference implementation and sign
 * validation: scratchpad {@code ik_ref.py}.
 *
 * <p>Diagnostics: rig load and first live application are logged at INFO; run
 * with {@code -Dauh.poseDebug=true} for per-second solver + rendered-bone
 * dumps.
 */
public final class DragonPoseSolver {
    private DragonPoseSolver() {
    }

    public static final boolean DEBUG = Boolean.getBoolean("auh.poseDebug");

    private static final double DEG = 180.0 / Math.PI;

    // raycast envelope (blocks)
    private static final double FOOT_PROBE_UP = 2.0;
    private static final double FOOT_PROBE_DOWN = 5.0;
    private static final double HEAD_CLEARANCE = 0.55;

    // per-chain joint limits (deg) and proximal-bias step weights
    private static final double[] HIND_LIMITS = {35.0, 40.0, 25.0};
    private static final double[] FORE_LIMITS = {40.0, 30.0, 20.0};
    private static final double[] LIMB_WEIGHTS = {1.0, 0.7, 0.4};
    private static final double NECK_SEG_LIMIT = 34.0;
    private static final double MAX_NECK_LIFT_UNITS = 46.0;

    // body: limbs absorb terrain first, the trunk follows gently
    private static final double SETTLE_MAX_UNITS = 8.0;
    private static final double TILT_GAIN = 0.35;
    private static final double PITCH_CLAMP_DEG = 12.0;
    private static final double ROLL_CLAMP_DEG = 10.0;
    private static final double AIR_FALLBACK_BLOCKS = 1.2;

    private static final float BLEND = 0.18F;
    private static final float FLIGHT_RELAX = 0.12F;

    private static long lastDebugMs;

    /** Solves this frame's targets and eases {@code frame} toward them. */
    public static void solve(DragonPoseFrame frame, DragonEntity dragon,
            DragonRig rig) {
        if (dragon.isFlying()) {
            frame.blendToRest(FLIGHT_RELAX);
            return;
        }
        Level level = dragon.level();
        Vec3 origin = dragon.position();
        double bpu = 1.0 / rig.unitsPerBlock;              // blocks per unit
        double yaw = Math.toRadians(dragon.yBodyRot);
        double cos = Math.cos(yaw), sin = Math.sin(yaw);

        Map<String, float[]> target = new HashMap<>();

        // --- 1. ground columns under every contact point (world Y or NaN)
        double[] ground = new double[rig.legs.length];
        for (int i = 0; i < rig.legs.length; i++) {
            Vec3 eff = rig.legs[i].chain.effector;
            ground[i] = groundY(level, dragon, origin, bpu, cos, sin,
                    eff.x, eff.z, FOOT_PROBE_UP, FOOT_PROBE_DOWN);
        }

        // --- 2. body settle toward the fore-knuckle contact deficit (the
        // wing-hands' downward reach is short; the chest drops to them)
        double settle = 0;
        int foreCount = 0;
        for (int i = 0; i < rig.legs.length; i++) {
            if (!rig.legs[i].fore || Double.isNaN(ground[i])) {
                continue;
            }
            double terrainUnits = (ground[i] - origin.y) / bpu;
            settle += Math.max(0, rig.legs[i].chain.effector.y - terrainUnits);
            foreCount++;
        }
        settle = foreCount == 0 ? 0
                : Mth.clamp(settle / foreCount, 0, SETTLE_MAX_UNITS);

        // --- 3. gentle body lean toward the support plane
        double foreG = 0, hindG = 0, leftG = 0, rightG = 0;
        int fn = 0, hn = 0, ln = 0, rn = 0;
        for (int i = 0; i < rig.legs.length; i++) {
            DragonRig.Leg leg = rig.legs[i];
            double sample = Double.isNaN(ground[i])
                    ? origin.y - AIR_FALLBACK_BLOCKS : ground[i];
            if (leg.fore) {
                foreG += sample; fn++;
            } else {
                hindG += sample; hn++;
            }
            if (leg.name.endsWith("_l")) {
                leftG += sample; ln++;
            } else {
                rightG += sample; rn++;
            }
        }
        float pitch = 0, roll = 0;
        if (fn > 0 && hn > 0) {
            pitch = (float) Mth.clamp(Math.atan2(foreG / fn - hindG / hn,
                    bpu * legSpan(rig, true)) * DEG * TILT_GAIN,
                    -PITCH_CLAMP_DEG, PITCH_CLAMP_DEG);
        }
        if (ln > 0 && rn > 0) {
            roll = (float) Mth.clamp(Math.atan2(leftG / ln - rightG / rn,
                    bpu * legSpan(rig, false)) * DEG * TILT_GAIN,
                    -ROLL_CLAMP_DEG, ROLL_CLAMP_DEG);
        }

        // --- 4. per-limb CCD onto each column, against the settled body
        for (int i = 0; i < rig.legs.length; i++) {
            if (Double.isNaN(ground[i])) {
                continue;                        // over air: keep animated pose
            }
            DragonRig.Leg leg = rig.legs[i];
            double targetY = (ground[i] - origin.y) / bpu;
            double[] ang = ccdVertical(leg.chain, settle, targetY, 20.0, 16,
                    leg.fore ? FORE_LIMITS : HIND_LIMITS, LIMB_WEIGHTS);
            for (int b = 0; b < leg.bones.length; b++) {
                put(target, leg.bones[b], leg.chain.slots[b], (float) ang[b]);
            }
        }

        // --- 5. neck: lift the head center clear of terrain under & ahead
        solveNeck(target, level, dragon, rig, origin, bpu, cos, sin, settle);

        frame.blend(target, pitch, roll, (float) settle, BLEND);

        if (DEBUG) {
            long now = System.currentTimeMillis();
            if (now - lastDebugMs > 1000) {
                lastDebugMs = now;
                StringBuilder g = new StringBuilder();
                for (int i = 0; i < rig.legs.length; i++) {
                    g.append(rig.legs[i].name).append('=')
                     .append(Double.isNaN(ground[i]) ? "air"
                             : String.format("%+.2f", ground[i] - origin.y))
                     .append(' ');
                }
                AllUnderHeaven.LOGGER.info(
                        "[pose] id={} at=({},{},{}) yaw={} settle={}u pitch={} "
                        + "roll={} ground[{}] {}",
                        dragon.getId(), String.format("%.1f", origin.x),
                        String.format("%.1f", origin.y),
                        String.format("%.1f", origin.z),
                        String.format("%.0f", dragon.yBodyRot),
                        String.format("%.1f", settle),
                        String.format("%.1f", pitch), String.format("%.1f", roll),
                        g.toString().trim(), summarize(target));
            }
        }
    }

    private static void solveNeck(Map<String, float[]> target, Level level,
            DragonEntity dragon, DragonRig rig, Vec3 origin, double bpu,
            double cos, double sin, double settle) {
        DragonRig.Neck neck = rig.neck;
        Vec3 hc = neck.headCenter;
        double probeTop = bpu * hc.y + 1.2;
        double currentWorldY = origin.y + bpu * (hc.y - settle);
        double desired = surfaceUnder(level, dragon, origin, bpu, cos, sin,
                hc.x, hc.z, probeTop);
        desired = Math.max(desired, surfaceUnder(level, dragon, origin, bpu, cos,
                sin, hc.x, hc.z - 10.0, probeTop));
        double desiredWorldY = desired + HEAD_CLEARANCE;
        if (desiredWorldY <= currentWorldY + 1e-3) {
            return;
        }
        double liftUnits = Math.min((desiredWorldY - currentWorldY) / bpu,
                MAX_NECK_LIFT_UNITS);
        char[] slots = new char[neck.joints.length];
        java.util.Arrays.fill(slots, 'x');
        DragonRig.Chain chain =
                new DragonRig.Chain(neck.joints, neck.axes, slots, hc);
        double[] limits = new double[neck.joints.length];
        double[] weights = new double[neck.joints.length];
        java.util.Arrays.fill(limits, NECK_SEG_LIMIT);
        java.util.Arrays.fill(weights, 1.0);
        double[] ang = ccdVertical(chain, settle, hc.y - settle + liftUnits,
                14.0, 24, limits, weights);
        double sum = 0;
        for (int i = 0; i < neck.bones.length; i++) {
            put(target, neck.bones[i], 'x', (float) ang[i]);
            sum += ang[i];
        }
        // counter-rotate the head so the face stays level while the neck rises
        put(target, "head", 'x', (float) (-sum * neck.headCounter));
    }

    // ---------------------------------------------------------------- CCD core
    // Proximal-biased: root-first sweeps with distally-decreasing step weights
    // find the natural "whole limb moves from its body joint" solution instead
    // of folding the last joint to its clamp. Downstream pivots AND their
    // hinge axes rotate together with the effector (validated in ik_ref.py).

    private static double[] ccdVertical(DragonRig.Chain chain, double settle,
            double targetY, double maxStepDeg, int iters, double[] limits,
            double[] weights) {
        int n = chain.joints.length;
        Vec3 drop = new Vec3(0, -settle, 0);
        Vec3[] p = new Vec3[n];
        Vec3[] ax = new Vec3[n];
        for (int i = 0; i < n; i++) {
            p[i] = chain.joints[i].add(drop);
            ax[i] = chain.axes[i];
        }
        Vec3 e = chain.effector.add(drop);
        Vec3 t = new Vec3(e.x, targetY, e.z);
        double[] ang = new double[n];
        for (int it = 0; it < iters; it++) {
            if (Math.abs(e.y - t.y) < 0.3) {
                break;
            }
            for (int i = 0; i < n; i++) {
                Vec3 piv = p[i], a = ax[i];
                Vec3 r = perp(e.subtract(piv), a);
                Vec3 rt = perp(t.subtract(piv), a);
                double lr = r.length(), lt = rt.length();
                if (lr < 1e-4 || lt < 1e-4) {
                    continue;
                }
                double ca = Mth.clamp(r.dot(rt) / (lr * lt), -1.0, 1.0);
                double step = Math.acos(ca) * 0.6;         // damped
                if (r.cross(rt).dot(a) < 0) {
                    step = -step;
                }
                double cap = Math.toRadians(maxStepDeg) * weights[i];
                step = Mth.clamp(step, -cap, cap);
                double next = Mth.clamp(ang[i] + Math.toDegrees(step),
                        -limits[i], limits[i]);
                step = Math.toRadians(next - ang[i]);
                ang[i] = next;
                for (int j = i + 1; j < n; j++) {
                    p[j] = rotateAbout(p[j], piv, a, step);
                    ax[j] = rotVec(ax[j], a, step);
                }
                e = rotateAbout(e, piv, a, step);
            }
        }
        return ang;
    }

    private static Vec3 perp(Vec3 v, Vec3 axis) {
        return v.subtract(axis.scale(axis.dot(v)));
    }

    private static Vec3 rotVec(Vec3 v, Vec3 axis, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        return v.scale(c).add(axis.cross(v).scale(s))
                .add(axis.scale(axis.dot(v) * (1 - c)));
    }

    private static Vec3 rotateAbout(Vec3 p, Vec3 pivot, Vec3 axis, double ang) {
        return pivot.add(rotVec(p.subtract(pivot), axis, ang));
    }

    // ------------------------------------------------------------ world probes

    /** Model-space xz -> world column; collision surface Y, or NaN if none. */
    private static double groundY(Level level, DragonEntity dragon, Vec3 origin,
            double bpu, double cos, double sin, double mx, double mz,
            double upBlocks, double downBlocks) {
        double wx = origin.x + bpu * (mx * cos + mz * sin);
        double wz = origin.z + bpu * (mx * sin - mz * cos);
        Vec3 from = new Vec3(wx, origin.y + upBlocks, wz);
        Vec3 to = new Vec3(wx, origin.y - downBlocks, wz);
        BlockHitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, dragon));
        return hit.getType() == HitResult.Type.MISS ? Double.NaN
                : hit.getLocation().y;
    }

    /** Highest surface under a model-space xz probing from {@code topBlocks}
     *  above the entity; the entity's own level if nothing is there. */
    private static double surfaceUnder(Level level, DragonEntity dragon,
            Vec3 origin, double bpu, double cos, double sin, double mx,
            double mz, double topBlocks) {
        double gy = groundY(level, dragon, origin, bpu, cos, sin, mx, mz,
                topBlocks, 1.0);
        return Double.isNaN(gy) ? origin.y : gy;
    }

    /** Mean front-back (or left-right) horizontal distance between contacts. */
    private static double legSpan(DragonRig rig, boolean frontBack) {
        double a = 0, b = 0;
        int an = 0, bn = 0;
        for (DragonRig.Leg leg : rig.legs) {
            boolean first = frontBack ? leg.fore : leg.name.endsWith("_l");
            double v = frontBack ? leg.chain.effector.z : leg.chain.effector.x;
            if (first) {
                a += v; an++;
            } else {
                b += v; bn++;
            }
        }
        return Math.max(1.0, Math.abs(a / Math.max(1, an) - b / Math.max(1, bn)));
    }

    private static void put(Map<String, float[]> target, String bone, char slot,
            float deg) {
        target.computeIfAbsent(bone, n -> new float[3])[slot == 'z' ? 2 : 0]
                += deg;
    }

    private static String summarize(Map<String, float[]> target) {
        StringBuilder sb = new StringBuilder();
        target.forEach((k, v) -> {
            float mag = Math.abs(v[0]) > Math.abs(v[2]) ? v[0] : v[2];
            if (Math.abs(mag) > 2.0F) {
                sb.append(k).append('=').append(Math.round(mag)).append("° ");
            }
        });
        return sb.toString();
    }
}
