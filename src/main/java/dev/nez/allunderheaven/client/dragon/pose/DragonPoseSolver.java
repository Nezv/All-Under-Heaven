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
 *       contact point onto the terrain column under it (active while walking
 *       too — the gait animation rides on top of the per-leg offset);</li>
 *   <li><b>the body follows the feet</b>: it settles vertically toward the
 *       fore-knuckle contact deficit (the wing-forelimbs plant high, so the
 *       chest drops until they touch) and pitches/rolls to the support plane,
 *       so the trunk is not glued level to the entity origin;</li>
 *   <li><b>the head never enters a block</b>: the neck lance lifts until the
 *       head center clears the highest surface under and just ahead of it.</li>
 * </ul>
 *
 * All maths runs in the model's generator space (+Y up, faces -Z, 16 units per
 * block). Vertical is yaw-invariant, so the IK needs no yaw; body yaw only
 * places the raycast columns. Everything relaxes to zero in flight.
 *
 * <p>Diagnostics: rig load and first live application are logged at INFO; run
 * with {@code -Dauh.poseDebug=true} for a per-second solver dump.
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

    // solver limits
    private static final double FOOT_LIMIT_DEG = 55.0;
    private static final double NECK_SEG_LIMIT_DEG = 34.0;
    private static final double MAX_NECK_LIFT_UNITS = 46.0;
    private static final double SETTLE_MAX_UNITS = 12.0;   // body drop toward contact
    private static final double TILT_GAIN = 0.85;
    private static final double PITCH_CLAMP_DEG = 25.0;
    private static final double ROLL_CLAMP_DEG = 20.0;

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

        // --- 2. body settle: drop the chest toward the fore-knuckle deficit so
        // the wing-hands actually reach their columns (hind legs retract; their
        // upward range is huge). Deficit is how far a fore contact hovers above
        // its ground column.
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

        // --- 3. body tilt from the support heights (falls back to entity level
        // for limbs hanging over air so a cliff edge pitches the body forward)
        double foreG = 0, hindG = 0, leftG = 0, rightG = 0;
        int fn = 0, hn = 0, ln = 0, rn = 0;
        for (int i = 0; i < rig.legs.length; i++) {
            DragonRig.Leg leg = rig.legs[i];
            double sample = Double.isNaN(ground[i])
                    ? origin.y - FOOT_PROBE_DOWN * 0.6 : ground[i];
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

        // --- 4. per-limb CCD onto each column, solved against the SETTLED body
        // (joints ride down with the chest), always active while grounded
        for (int i = 0; i < rig.legs.length; i++) {
            if (Double.isNaN(ground[i])) {
                continue;                        // over air: keep animated pose
            }
            DragonRig.Leg leg = rig.legs[i];
            double targetY = (ground[i] - origin.y) / bpu;
            double[] ang = ccdVertical(leg.chain, settle, targetY,
                    35.0, 24, FOOT_LIMIT_DEG);
            for (int b = 0; b < leg.bones.length; b++) {
                putX(target, leg.bones[b], (float) ang[b]);
            }
        }

        // --- 5. neck: lift the head center clear of terrain under & ahead
        solveNeck(target, level, dragon, rig, origin, bpu, cos, sin, settle);

        frame.blend(target, pitch, roll, (float) settle, BLEND);

        if (DEBUG) {
            long now = System.currentTimeMillis();
            if (now - lastDebugMs > 1000) {
                lastDebugMs = now;
                AllUnderHeaven.LOGGER.info(
                        "[pose] id={} settle={}u pitch={} roll={} bones={} {}",
                        dragon.getId(), String.format("%.1f", settle),
                        String.format("%.1f", pitch), String.format("%.1f", roll),
                        target.size(), summarize(target));
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
        DragonRig.Chain chain = new DragonRig.Chain(neck.joints, neck.axes, hc);
        double[] ang = ccdVertical(chain, settle, hc.y - settle + liftUnits,
                16.0, 40, NECK_SEG_LIMIT_DEG);
        double sum = 0;
        for (int i = 0; i < neck.bones.length; i++) {
            putX(target, neck.bones[i], (float) ang[i]);
            sum += ang[i];
        }
        // counter-rotate the head so the face stays level while the neck rises
        putX(target, "head", (float) (-sum * neck.headCounter));
    }

    // ---------------------------------------------------------------- CCD core
    // Validated against the generator's FK in the scratchpad reference
    // (ik_ref.py): rotating about each joint's live local +X axis, with the
    // downstream pivots AND axes carried along, converges on the target and
    // yields zero deltas when the target is the rest pose.

    private static double[] ccdVertical(DragonRig.Chain chain, double settle,
            double targetY, double maxStepDeg, int iters, double limitDeg) {
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
                double next = Mth.clamp(ang[i] + Math.toDegrees(step),
                        -limitDeg, limitDeg);
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

    private static void putX(Map<String, float[]> target, String bone, float dx) {
        target.computeIfAbsent(bone, n -> new float[3])[0] += dx;
    }

    private static String summarize(Map<String, float[]> target) {
        StringBuilder sb = new StringBuilder();
        target.forEach((k, v) -> {
            if (Math.abs(v[0]) > 2.0F) {
                sb.append(k).append('=').append(Math.round(v[0])).append("° ");
            }
        });
        return sb.toString();
    }
}
