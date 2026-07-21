package dev.nez.allunderheaven.client.dragon.pose;

import java.util.HashMap;
import java.util.Map;

import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime terrain adaptation for a landed dragon. Each frame it raycasts the
 * world under the feet and ahead of the head, then solves generator-space
 * rotation deltas (cyclic-coordinate descent, validated in the scratchpad
 * {@code ik_ref.py}) that:
 *
 * <ul>
 *   <li><b>keep the head out of blocks</b> — the neck lance lifts so the head
 *       center rides a clearance above whatever ground/step/tree is under it;</li>
 *   <li><b>plant the feet</b> — each limb retracts up onto higher terrain (its
 *       usable range is mostly upward; see {@code dragon-pose-solver} memo);</li>
 *   <li><b>tilt the body</b> to the slope from the four ground samples.</li>
 * </ul>
 *
 * The maths runs entirely in the model's generator space. Vertical (Y) is
 * yaw-invariant, so the limb/neck solve needs no yaw; body yaw is used only to
 * place the raycast columns in the world. Everything relaxes to zero in flight.
 */
public final class DragonPoseSolver {
    private DragonPoseSolver() {
    }

    private static final double DEG = 180.0 / Math.PI;

    // reach / raycast envelope (blocks)
    private static final double FOOT_PROBE_UP = 1.5;
    private static final double FOOT_PROBE_DOWN = 6.0;
    private static final double HEAD_CLEARANCE = 0.55;

    // solver limits (generator degrees)
    private static final double FOOT_LIMIT = 55.0;
    private static final double NECK_STEP = 16.0;
    private static final double NECK_SEG_LIMIT = 34.0;
    private static final double MAX_NECK_LIFT_UNITS = 46.0;
    private static final double TILT_GAIN = 0.6;
    private static final double PITCH_CLAMP = 22.0;
    private static final double ROLL_CLAMP = 18.0;

    private static final float BLEND = 0.22F;
    private static final float FLIGHT_RELAX = 0.12F;

    /** Solves and eases {@code frame} for this render frame. */
    public static void solve(DragonPoseFrame frame, DragonEntity dragon,
            DragonRig rig) {
        if (dragon.isFlying()) {
            frame.blend(Map.of(), 0.0F, 0.0F, FLIGHT_RELAX);
            return;
        }
        Level level = dragon.level();
        Vec3 origin = dragon.position();
        double bpu = 1.0 / rig.unitsPerBlock;              // blocks per unit
        double th = Math.toRadians(dragon.yBodyRot);
        double cos = Math.cos(th), sin = Math.sin(th);

        Map<String, float[]> target = new HashMap<>();

        // --- feet: sample ground, retract/plant each limb, gather tilt samples
        float footWeight = Mth.clamp(1.0F - dragon.walkAnimation.speed() * 2.5F,
                0.0F, 1.0F);
        double foreG = 0, hindG = 0, leftG = 0, rightG = 0;
        int foreN = 0, hindN = 0, leftN = 0, rightN = 0;
        for (DragonRig.Leg leg : rig.legs) {
            Vec3 eff = leg.chain.effector;
            double gy = groundY(level, dragon, origin, bpu, cos, sin,
                    eff.x, eff.z, FOOT_PROBE_UP, FOOT_PROBE_DOWN);
            double restWorldY = origin.y + bpu * eff.y;
            double sample = Double.isNaN(gy) ? restWorldY : gy;
            if (leg.fore) {
                foreG += sample; foreN++;
            } else {
                hindG += sample; hindN++;
            }
            if (leg.name.endsWith("_l")) {
                leftG += sample; leftN++;
            } else {
                rightG += sample; rightN++;
            }
            if (Double.isNaN(gy) || footWeight <= 0.001F) {
                continue;
            }
            double targetY = (gy - origin.y) / bpu;        // world -> model units
            double[] lo = {-FOOT_LIMIT, -FOOT_LIMIT, -FOOT_LIMIT};
            double[] hi = {FOOT_LIMIT, FOOT_LIMIT, FOOT_LIMIT};
            double[] ang = ccd(leg.chain.joints, leg.chain.axes, leg.chain.effector,
                    targetY, 35.0, 24, lo, hi);
            for (int i = 0; i < leg.bones.length; i++) {
                putX(target, leg.bones[i], (float) ang[i] * footWeight);
            }
        }

        // --- body tilt from the four support heights
        float pitch = 0.0F, roll = 0.0F;
        if (foreN > 0 && hindN > 0) {
            double fb = bpu * legZSpan(rig);               // world front-back span
            pitch = (float) Mth.clamp(
                    Math.atan2(foreG / foreN - hindG / hindN, fb) * DEG * TILT_GAIN,
                    -PITCH_CLAMP, PITCH_CLAMP);
        }
        if (leftN > 0 && rightN > 0) {
            double lr = bpu * legXSpan(rig);
            roll = (float) Mth.clamp(
                    Math.atan2(leftG / leftN - rightG / rightN, lr) * DEG * TILT_GAIN,
                    -ROLL_CLAMP, ROLL_CLAMP);
        }

        // --- neck: lift the head clear of terrain/steps/trees under & ahead
        solveNeck(target, level, dragon, rig, origin, bpu, cos, sin);

        frame.blend(target, pitch, roll, BLEND);
    }

    private static void solveNeck(Map<String, float[]> target, Level level,
            DragonEntity dragon, DragonRig rig, Vec3 origin, double bpu,
            double cos, double sin) {
        DragonRig.Neck neck = rig.neck;
        Vec3 hc = neck.headCenter;
        double headTopProbe = bpu * hc.y + 1.0;
        double currentWorldY = origin.y + bpu * hc.y;
        // highest ground surface under the head column and a step forward of it
        double desired = surfaceUnder(level, dragon, origin, bpu, cos, sin,
                hc.x, hc.z, headTopProbe);
        double aheadZ = hc.z - 8.0;                        // one probe further out
        desired = Math.max(desired, surfaceUnder(level, dragon, origin, bpu, cos,
                sin, hc.x, aheadZ, headTopProbe));
        double desiredWorldY = desired + HEAD_CLEARANCE;
        if (desiredWorldY <= currentWorldY + 1e-3) {
            return;                                        // head already clears
        }
        double liftUnits = Math.min((desiredWorldY - currentWorldY) / bpu,
                MAX_NECK_LIFT_UNITS);
        double targetY = hc.y + liftUnits;
        double[] lo = new double[neck.joints.length];
        double[] hi = new double[neck.joints.length];
        for (int i = 0; i < lo.length; i++) {
            lo[i] = -NECK_SEG_LIMIT;
            hi[i] = NECK_SEG_LIMIT;
        }
        double[] ang = ccd(neck.joints, neck.axes, hc, targetY, NECK_STEP, 40,
                lo, hi);
        double sum = 0;
        for (int i = 0; i < neck.bones.length; i++) {
            putX(target, neck.bones[i], (float) ang[i]);
            sum += ang[i];
        }
        // counter-level the face so it stays forward instead of pitching up
        putX(target, "head", (float) (-sum * neck.headCounter));
    }

    // ------------------------------------------------------------ CCD (see ik_ref)

    private static double[] ccd(Vec3[] joints, Vec3[] axes, Vec3 effector,
            double targetY, double maxStepDeg, int iters, double[] lo, double[] hi) {
        int n = joints.length;
        Vec3[] p = joints.clone();
        Vec3[] ax = axes.clone();
        Vec3 e = effector;
        double[] ang = new double[n];
        Vec3 t = new Vec3(e.x, targetY, e.z);
        double maxStep = Math.toRadians(maxStepDeg);
        for (int it = 0; it < iters; it++) {
            for (int i = n - 1; i >= 0; i--) {
                Vec3 piv = p[i], a = ax[i];
                Vec3 r = perp(e.subtract(piv), a);
                Vec3 rt = perp(t.subtract(piv), a);
                double lr = r.length(), lt = rt.length();
                if (lr < 1e-4 || lt < 1e-4) {
                    continue;
                }
                double ca = Mth.clamp(r.dot(rt) / (lr * lt), -1.0, 1.0);
                double step = Math.acos(ca);
                if (r.cross(rt).dot(a) < 0) {
                    step = -step;
                }
                step = Mth.clamp(step, -maxStep, maxStep);
                double nw = Mth.clamp(ang[i] + Math.toDegrees(step), lo[i], hi[i]);
                step = Math.toRadians(nw - ang[i]);
                ang[i] = nw;
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

    // -------------------------------------------------------------- world probes

    /** Model-space xz -> world column, returns the collision-surface Y or NaN. */
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

    /** Highest collision surface under a model-space xz, probing from
     *  {@code topBlocks} above the entity down past its feet; entity Y if none. */
    private static double surfaceUnder(Level level, DragonEntity dragon,
            Vec3 origin, double bpu, double cos, double sin, double mx, double mz,
            double topBlocks) {
        double gy = groundY(level, dragon, origin, bpu, cos, sin, mx, mz,
                topBlocks, 1.0);
        return Double.isNaN(gy) ? origin.y : gy;
    }

    private static double legZSpan(DragonRig rig) {
        double fore = 0, hind = 0;
        int fn = 0, hn = 0;
        for (DragonRig.Leg leg : rig.legs) {
            if (leg.fore) {
                fore += leg.chain.effector.z; fn++;
            } else {
                hind += leg.chain.effector.z; hn++;
            }
        }
        return Math.abs(hind / Math.max(1, hn) - fore / Math.max(1, fn));
    }

    private static double legXSpan(DragonRig rig) {
        double left = 0, right = 0;
        int ln = 0, rn = 0;
        for (DragonRig.Leg leg : rig.legs) {
            if (leg.name.endsWith("_l")) {
                left += leg.chain.effector.x; ln++;
            } else {
                right += leg.chain.effector.x; rn++;
            }
        }
        return Math.abs(left / Math.max(1, ln) - right / Math.max(1, rn));
    }

    private static void putX(Map<String, float[]> target, String bone, float dx) {
        target.computeIfAbsent(bone, n -> new float[3])[0] += dx;
    }
}
