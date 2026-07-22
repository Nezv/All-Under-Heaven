package dev.nez.allunderheaven.feature.dragonnest;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModEntities;
import dev.nez.allunderheaven.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * An impact crater that doubles as the dragon's nest: a 5-block-radius
 * hemisphere gouged into the terrain, its scorched basalt floor studded with
 * the raw gold and stardust ore a fallen star drives into the ground, a gold
 * hoard heaped at the center, and a low ejecta rim. The resident dragon
 * spawns once (flag persisted), rolls a random variant, and is homed to the
 * crater floor - that home drives the guard radius, stroll tether and
 * fly-home behavior. Every material roll is a pure hash of world position so
 * chunk borders can't tear the pattern.
 *
 * <p>(The falling-star EVENT that opens fresh craters around players is a
 * later addition; this is the crater as worldgen structure.)
 */
public class DragonNestPiece extends StructurePiece {
    private static final int RADIUS = 5;
    private static final int HEADROOM = 5;

    private boolean spawnedDragon;

    public DragonNestPiece(BlockPos center) {
        super(ModStructures.DRAGON_NEST_PIECE.get(), 0, new BoundingBox(
                center.getX() - RADIUS - 2, center.getY() - RADIUS - 3, center.getZ() - RADIUS - 2,
                center.getX() + RADIUS + 2, center.getY() + HEADROOM + 3, center.getZ() + RADIUS + 2));
        this.setOrientation(null);
    }

    public DragonNestPiece(CompoundTag tag) {
        super(ModStructures.DRAGON_NEST_PIECE.get(), tag);
        this.spawnedDragon = tag.getBooleanOr("SpawnedDragon", false);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putBoolean("SpawnedDragon", this.spawnedDragon);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        int cx = (this.boundingBox.minX() + this.boundingBox.maxX()) / 2;
        int cz = (this.boundingBox.minZ() + this.boundingBox.maxZ()) / 2;

        int x0 = Math.max(this.boundingBox.minX(), chunkBB.minX());
        int x1 = Math.min(this.boundingBox.maxX(), chunkBB.maxX());
        int z0 = Math.max(this.boundingBox.minZ(), chunkBB.minZ());
        int z1 = Math.min(this.boundingBox.maxZ(), chunkBB.maxZ());

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int wx = x0; wx <= x1; wx++) {
            for (int wz = z0; wz <= z1; wz++) {
                double r = Math.hypot(wx - cx, wz - cz);
                if (r > RADIUS + 1.6) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, wx, wz) - 1;
                cursor.set(wx, top, wz);
                if (!level.getFluidState(cursor).isEmpty()) {
                    continue; // never gouge a crater into water
                }
                int hash = columnHash(wx, wz);

                if (r <= RADIUS) {
                    // the bowl: hemisphere depth falls off from the center
                    int depth = (int) Math.round(Math.sqrt(RADIUS * RADIUS - r * r));
                    int floorY = top - depth;
                    // hollow the open bowl (air) up to the wing headroom
                    for (int y = floorY + 1; y <= top + HEADROOM; y++) {
                        cursor.set(wx, y, wz);
                        if (chunkBB.isInside(cursor) && !level.isEmptyBlock(cursor)) {
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    // scorched floor, ore studding the impact bed
                    cursor.set(wx, floorY, wz);
                    if (chunkBB.isInside(cursor)) {
                        level.setBlock(cursor, floorBlock(hash, r), 2);
                    }
                    // a second ore seam driven one block into the bed
                    cursor.set(wx, floorY - 1, wz);
                    if (chunkBB.isInside(cursor) && (hash & 7) == 0) {
                        level.setBlock(cursor, (hash >> 3 & 1) == 0
                                ? ModBlocks.STARDUST_ORE.get().defaultBlockState()
                                : Blocks.RAW_GOLD_BLOCK.defaultBlockState(), 2);
                    }
                    // the hoard: gold heaped over the crater's heart
                    if (r < 1.8) {
                        int gold = r < 0.8 ? 2 : (hash & 3) != 0 ? 1 : 0;
                        for (int i = 1; i <= gold; i++) {
                            cursor.set(wx, floorY + i, wz);
                            if (chunkBB.isInside(cursor)) {
                                level.setBlock(cursor, (hash >> 4 & 3) == 0
                                        ? Blocks.RAW_GOLD_BLOCK.defaultBlockState()
                                        : Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                            }
                        }
                    }
                } else {
                    // ejecta rim: a low scorched lip flung up around the crater
                    int lip = (hash & 3) == 0 ? 1 : 0;
                    for (int i = 1; i <= lip; i++) {
                        cursor.set(wx, top + i, wz);
                        if (chunkBB.isInside(cursor)) {
                            level.setBlock(cursor, rimBlock(hash + i * 7), 2);
                        }
                    }
                }
            }
        }

        this.spawnDragon(level, chunkBB, cx, cz);
    }

    private void spawnDragon(WorldGenLevel level, BoundingBox chunkBB, int cx, int cz) {
        if (this.spawnedDragon) {
            return;
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz) - 1;
        int floorY = top - RADIUS;                         // the crater's heart
        BlockPos perch = new BlockPos(cx, floorY + 2, cz);
        if (!chunkBB.isInside(perch)) {
            return;
        }
        this.spawnedDragon = true;
        DragonEntity dragon = ModEntities.DRAGON.get().create(level.getLevel(), EntitySpawnReason.STRUCTURE);
        if (dragon != null) {
            dragon.setPersistenceRequired();
            dragon.snapTo(cx + 0.5, perch.getY(), cz + 0.5,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            dragon.finalizeSpawn(level, level.getCurrentDifficultyAt(perch),
                    EntitySpawnReason.STRUCTURE, null);
            dragon.setNest(new BlockPos(cx, floorY + 1, cz));
            level.addFreshEntityWithPassengers(dragon);
            AllUnderHeaven.LOGGER.info("[All Under Heaven] Dragon crater at ({}, {}, {}) — {} dragon on guard",
                    cx, perch.getY(), cz, dragon.getVariant().key);
        }
    }

    /** Pure position hash - identical from whichever chunk stamps the column. */
    private int columnHash(int wx, int wz) {
        long h = wx * 341873128712L ^ wz * 132897987541L
                ^ (long) this.boundingBox.minX() * 31 ^ this.boundingBox.minZ();
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return (int) (h >>> 32) & 0x7FFFFFFF;
    }

    /** Scorched impact bed, with ore visibly fused into the glass. */
    private static BlockState floorBlock(int hash, double r) {
        if (r < 0.9 && (hash & 1) == 0) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        return switch (hash % 12) {
            case 0, 1 -> ModBlocks.STARDUST_ORE.get().defaultBlockState();
            case 2 -> Blocks.RAW_GOLD_BLOCK.defaultBlockState();
            case 3, 4, 5 -> Blocks.BASALT.defaultBlockState();
            case 6, 7 -> Blocks.BLACKSTONE.defaultBlockState();
            case 8, 9 -> Blocks.POLISHED_BASALT.defaultBlockState();
            default -> Blocks.SMOOTH_BASALT.defaultBlockState();
        };
    }

    private static BlockState rimBlock(int hash) {
        return switch (hash % 8) {
            case 0, 1, 2 -> Blocks.BLACKSTONE.defaultBlockState();
            case 3, 4 -> Blocks.BASALT.defaultBlockState();
            case 5, 6 -> Blocks.COBBLESTONE.defaultBlockState();
            default -> Blocks.MAGMA_BLOCK.defaultBlockState();
        };
    }
}
