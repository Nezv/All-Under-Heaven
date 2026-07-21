package dev.nez.allunderheaven.client.dragon.pose;

import java.io.BufferedReader;
import java.util.EnumMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Parsed skeleton sidecar (produced by {@code tools/dragon/build_dragon.py}
 * {@code export_rig}) that the {@link DragonPoseSolver} needs to do runtime
 * terrain adaptation. Everything here is GENERATOR space: +Y up, the dragon
 * faces -Z, +X is the model's left, 16 units per block, evaluated by forward
 * kinematics at the exact ground stance the game renders. One rig per variant,
 * loaded from the client resource pack and cached.
 */
public final class DragonRig {
    /** A CCD chain: rotatable {@link #joints} about their local +X {@link #axes},
     *  ending at the ground-contact {@link #effector}. */
    public static final class Chain {
        public final Vec3[] joints;
        public final Vec3[] axes;
        public final Vec3 effector;

        Chain(Vec3[] joints, Vec3[] axes, Vec3 effector) {
            this.joints = joints;
            this.axes = axes;
            this.effector = effector;
        }
    }

    public static final class Leg {
        public final String name;
        public final boolean fore;
        public final String[] bones;
        public final Chain chain;

        Leg(String name, boolean fore, String[] bones, Chain chain) {
            this.name = name;
            this.fore = fore;
            this.bones = bones;
            this.chain = chain;
        }
    }

    public static final class Neck {
        public final String[] bones;
        public final Vec3[] joints;
        public final Vec3[] axes;
        public final Vec3 headCenter;
        public final double headCounter;

        Neck(String[] bones, Vec3[] joints, Vec3[] axes, Vec3 headCenter,
                double headCounter) {
            this.bones = bones;
            this.joints = joints;
            this.axes = axes;
            this.headCenter = headCenter;
            this.headCounter = headCounter;
        }
    }

    public final double unitsPerBlock;
    public final Leg[] legs;
    public final Neck neck;

    private DragonRig(double unitsPerBlock, Leg[] legs, Neck neck) {
        this.unitsPerBlock = unitsPerBlock;
        this.legs = legs;
        this.neck = neck;
    }

    private static final Map<DragonVariant, DragonRig> CACHE =
            new EnumMap<>(DragonVariant.class);
    private static final Map<DragonVariant, Boolean> FAILED =
            new EnumMap<>(DragonVariant.class);

    /** Cached lookup; returns {@code null} (once, quietly) if the sidecar is
     *  missing or malformed so the solver simply no-ops. */
    public static DragonRig get(DragonVariant variant) {
        DragonRig cached = CACHE.get(variant);
        if (cached != null) {
            return cached;
        }
        if (FAILED.containsKey(variant)) {
            return null;
        }
        try {
            DragonRig rig = load(variant);
            CACHE.put(variant, rig);
            return rig;
        } catch (Exception e) {
            FAILED.put(variant, Boolean.TRUE);
            AllUnderHeaven.LOGGER.warn("[All Under Heaven] no pose rig for dragon "
                    + "variant {} ({}); terrain adaptation disabled for it",
                    variant.key, e.toString());
            return null;
        }
    }

    /** Drops the cache so a resource reload re-reads the sidecars. */
    public static void invalidate() {
        CACHE.clear();
        FAILED.clear();
    }

    private static DragonRig load(DragonVariant variant) throws Exception {
        Identifier id = AllUnderHeaven.id("rigs/wyvern_" + variant.key + ".json");
        JsonObject root;
        try (BufferedReader reader = Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(id).openAsReader()) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        double upb = root.has("unitsPerBlock")
                ? root.get("unitsPerBlock").getAsDouble() : 16.0;

        JsonArray legArr = root.getAsJsonArray("legs");
        Leg[] legs = new Leg[legArr.size()];
        for (int i = 0; i < legArr.size(); i++) {
            JsonObject o = legArr.get(i).getAsJsonObject();
            String name = o.get("name").getAsString();
            String[] bones = strings(o.getAsJsonArray("bones"));
            legs[i] = new Leg(name, "fore".equals(o.get("kind").getAsString()),
                    bones, chain(o));
        }

        JsonObject nk = root.getAsJsonObject("neck");
        Neck neck = new Neck(strings(nk.getAsJsonArray("bones")),
                vecs(nk.getAsJsonArray("joints")), vecs(nk.getAsJsonArray("axes")),
                vec(nk.getAsJsonArray("headCenter")),
                nk.has("headCounter") ? nk.get("headCounter").getAsDouble() : 0.85);

        return new DragonRig(upb, legs, neck);
    }

    private static Chain chain(JsonObject o) {
        return new Chain(vecs(o.getAsJsonArray("joints")),
                vecs(o.getAsJsonArray("axes")), vec(o.getAsJsonArray("effector")));
    }

    private static String[] strings(JsonArray a) {
        String[] out = new String[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i).getAsString();
        }
        return out;
    }

    private static Vec3[] vecs(JsonArray a) {
        Vec3[] out = new Vec3[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = vec(a.get(i).getAsJsonArray());
        }
        return out;
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                a.get(2).getAsDouble());
    }
}
