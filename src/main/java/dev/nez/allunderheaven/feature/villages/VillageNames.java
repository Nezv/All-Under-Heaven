package dev.nez.allunderheaven.feature.villages;

import java.util.Random;

import net.minecraft.world.level.ChunkPos;

/**
 * Deterministic village name generation. A village is identified by the chunk
 * of its structure start; the name is derived purely from (world seed, start
 * chunk), so the same village always resolves to the same name on any
 * server/client without persisting anything.
 */
public final class VillageNames {
    private static final String[] ONSET = {
            "An", "Bai", "Chen", "Dai", "Feng", "Gao", "Hua", "Jin", "Kai", "Lan",
            "Mei", "Ning", "Ping", "Qing", "Rui", "Shan", "Tai", "Wei", "Xin", "Yun", "Zhou"
    };
    private static final String[] LINK = {
            "an", "bao", "chuan", "dong", "feng", "gu", "hai", "jiang", "lin", "men",
            "ning", "qiao", "shan", "tan", "xi", "yang", "yuan", "zhen"
    };
    private static final String[] TITLE = {
            "Village", "Hamlet", "Township", "Crossing", "Rest", "Hollow",
            "Terrace", "Haven", "Gate", "Wells"
    };

    private VillageNames() {
    }

    /** Stable name for the village whose structure start sits in {@code startChunk}. */
    public static String of(long worldSeed, ChunkPos startChunk) {
        Random random = new Random(worldSeed ^ startChunk.pack() * 0x9E3779B97F4A7C15L);
        StringBuilder name = new StringBuilder(ONSET[random.nextInt(ONSET.length)]);
        name.append(LINK[random.nextInt(LINK.length)]);
        if (random.nextInt(3) == 0) {
            name.append(LINK[random.nextInt(LINK.length)]);
        }
        return name.append(' ').append(TITLE[random.nextInt(TITLE.length)]).toString();
    }
}
