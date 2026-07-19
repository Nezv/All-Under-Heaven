package dev.nez.allunderheaven.feature.dragonnest;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonEntity;
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
 * The nest itself, stamped column-by-column so it hugs the terrain: a raised
 * stone rim ring (bowl profile peaking mid-ring), an earthen floor cleared of
 * headroom inside, and a gold hoard at the center. Every material roll is a
 * pure hash of world position, so chunk borders can't tear the pattern. The
 * resident dragon spawns once (flag persisted), gets a random variant from
 * {@code finalizeSpawn}, and is homed to the hoard - that home drives the
 * guard radius, the stroll tether and the fly-home behavior.
 */
public class DragonNestPiece extends StructurePiece {
    private static final int RADIUS = 7;

    private boolean spawnedDragon;

    public DragonNestPiece(BlockPos center) {
        super(ModStructures.DRAGON_NEST_PIECE.get(), 0, new BoundingBox(
                center.getX() - RADIUS - 2, center.getY() - 6, center.getZ() - RADIUS - 2,
                center.getX() + RADIUS + 2, center.getY() + 14, center.getZ() + RADIUS + 2));
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
                if (r > RADIUS + 0.5) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, wx, wz) - 1;
                cursor.set(wx, top, wz);
                if (!level.getFluidState(cursor).isEmpty()) {
                    continue; // never build into water at the rim
                }
                int hash = columnHash(wx, wz);

                if (r > 4.5) {
                    // the rim: a bowl wall peaking mid-ring (2 high), tapering
                    // to 1 at both edges, in a weathered stone mix
                    int height = r > 6.4 || r < 5.1 ? 1 : 2;
                    height += (hash & 15) == 0 ? 1 : 0; // the odd snag
                    for (int i = 1; i <= height; i++) {
                        cursor.set(wx, top + i, wz);
                        if (chunkBB.isInside(cursor)) {
                            level.setBlock(cursor, rimBlock(hash + i * 7), 2);
                        }
                    }
                } else {
                    // the floor: scorched earth, headroom cleared for the wings
                    cursor.set(wx, top, wz);
                    if (chunkBB.isInside(cursor)) {
                        level.setBlock(cursor, floorBlock(hash), 2);
                    }
                    for (int i = 1; i <= 5; i++) {
                        cursor.set(wx, top + i, wz);
                        if (chunkBB.isInside(cursor) && !level.isEmptyBlock(cursor)) {
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    // the hoard: gold heaped over the center
                    if (r < 2.2) {
                        int gold = r < 0.8 ? 2 : (hash & 3) != 0 ? 1 : 0;
                        for (int i = 1; i <= gold; i++) {
                            cursor.set(wx, top + i, wz);
                            if (chunkBB.isInside(cursor)) {
                                level.setBlock(cursor, (hash >> 4 & 3) == 0
                                        ? Blocks.RAW_GOLD_BLOCK.defaultBlockState()
                                        : Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                            }
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
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz);
        BlockPos perch = new BlockPos(cx, top + 2, cz);
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
            dragon.setNest(new BlockPos(cx, top, cz));
            level.addFreshEntityWithPassengers(dragon);
            AllUnderHeaven.LOGGER.info("[All Under Heaven] Dragon nest at ({}, {}, {}) — {} dragon on guard",
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

    private static BlockState rimBlock(int hash) {
        return switch (hash % 10) {
            case 0, 1, 2, 3 -> Blocks.COBBLESTONE.defaultBlockState();
            case 4, 5, 6 -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 7, 8 -> Blocks.STONE.defaultBlockState();
            default -> Blocks.ANDESITE.defaultBlockState();
        };
    }

    private static BlockState floorBlock(int hash) {
        return switch (hash % 10) {
            case 0, 1, 2, 3, 4, 5 -> Blocks.COARSE_DIRT.defaultBlockState();
            case 6, 7 -> Blocks.PODZOL.defaultBlockState();
            default -> Blocks.ROOTED_DIRT.defaultBlockState();
        };
    }
}
