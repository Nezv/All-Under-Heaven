package dev.nez.allunderheaven.feature.dragon;

/**
 * The three wyvern builds produced by {@code tools/dragon/build_dragon.py}.
 * The id is the synced/saved form; {@link #key} names the geo/animation/
 * texture assets ({@code wyvern_<key>}); the fire colors mirror the
 * generator's {@code Variant.fire} gradient (core → mid → outer) and tint
 * the breath particles so each dragon burns its own color.
 */
public enum DragonVariant {
    RED("red", 0xFFEC96, 0xFF942A, 0xD04012),
    BLACK("black", 0xFF8A42, 0xC62E1C, 0x601E1A),
    WHITE("white", 0xE4F6FF, 0x82BEFF, 0x386EE1);

    private static final DragonVariant[] BY_ID = values();

    public final String key;
    public final int coreColor;
    public final int midColor;
    public final int outerColor;

    DragonVariant(String key, int coreColor, int midColor, int outerColor) {
        this.key = key;
        this.coreColor = coreColor;
        this.midColor = midColor;
        this.outerColor = outerColor;
    }

    public int id() {
        return this.ordinal();
    }

    public static DragonVariant byId(int id) {
        return BY_ID[Math.floorMod(id, BY_ID.length)];
    }
}
