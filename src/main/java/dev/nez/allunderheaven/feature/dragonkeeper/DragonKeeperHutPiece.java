package dev.nez.allunderheaven.feature.dragonkeeper;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonforge.DragonlordForgeBlock;
import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Stamps the keeper's dwelling column-by-column (terrain-hugging, chunk-border
 * safe): a 7x7 ashen house — cobbled-deepslate walls with basalt corner posts,
 * a spruce floor and roof, a south doorway — housing a Dragon-lord Forge on
 * the back wall (the keeper's workstation) and one adult Dragon Keeper who
 * claims it. The floor sits at the hut center's surface height, foundations
 * filled to the ground beneath.
 */
public class DragonKeeperHutPiece extends StructurePiece {
    private static final int HALF = 3;         // 7x7 interior footprint
    private static final int WALL_H = 4;

    private final int floorY;
    private boolean spawnedKeeper;

    public DragonKeeperHutPiece(BlockPos center) {
        super(ModStructures.DRAGON_KEEPER_HUT_PIECE.get(), 0, new BoundingBox(
                center.getX() - HALF - 1, center.getY() - 5, center.getZ() - HALF - 1,
                center.getX() + HALF + 1, center.getY() + WALL_H + 2, center.getZ() + HALF + 1));
        this.floorY = center.getY();
        this.setOrientation(null);
    }

    public DragonKeeperHutPiece(CompoundTag tag) {
        super(ModStructures.DRAGON_KEEPER_HUT_PIECE.get(), tag);
        this.floorY = tag.getIntOr("FloorY", 0);
        this.spawnedKeeper = tag.getBooleanOr("SpawnedKeeper", false);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("FloorY", this.floorY);
        tag.putBoolean("SpawnedKeeper", this.spawnedKeeper);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        int cx = (this.boundingBox.minX() + this.boundingBox.maxX()) / 2;
        int cz = (this.boundingBox.minZ() + this.boundingBox.maxZ()) / 2;
        int y = this.floorY;

        int x0 = Math.max(this.boundingBox.minX(), chunkBB.minX());
        int x1 = Math.min(this.boundingBox.maxX(), chunkBB.maxX());
        int z0 = Math.max(this.boundingBox.minZ(), chunkBB.minZ());
        int z1 = Math.min(this.boundingBox.maxZ(), chunkBB.maxZ());
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();

        for (int wx = x0; wx <= x1; wx++) {
            for (int wz = z0; wz <= z1; wz++) {
                int dx = wx - cx;
                int dz = wz - cz;
                boolean roofArea = Math.abs(dx) <= HALF + 1 && Math.abs(dz) <= HALF + 1;
                boolean house = Math.abs(dx) <= HALF && Math.abs(dz) <= HALF;
                boolean wall = house && (Math.abs(dx) == HALF || Math.abs(dz) == HALF);
                boolean corner = Math.abs(dx) == HALF && Math.abs(dz) == HALF;
                int hash = columnHash(wx, wz);

                if (house) {
                    // foundation down to the ground + the floor
                    int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, wx, wz) - 1;
                    for (int fy = Math.min(y - 1, ground); fy <= y - 1; fy++) {
                        set(level, chunkBB, c, wx, fy, wz, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
                    }
                    set(level, chunkBB, c, wx, y, wz, floorBlock(hash));
                    // clear the interior headroom
                    for (int iy = 1; iy <= WALL_H + 1; iy++) {
                        set(level, chunkBB, c, wx, y + iy, wz, Blocks.AIR.defaultBlockState());
                    }
                }
                if (wall) {
                    boolean doorway = dz == HALF && dx == 0;   // south doorway
                    for (int iy = 1; iy <= WALL_H; iy++) {
                        if (doorway && iy <= 2) {
                            continue;
                        }
                        BlockState w = corner ? Blocks.POLISHED_BASALT.defaultBlockState()
                                : (iy == WALL_H ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                                        : wallBlock(hash + iy));
                        set(level, chunkBB, c, wx, y + iy, wz, w);
                    }
                    // a lantern-lit window slit on the non-door walls
                    if (!corner && !doorway && (Math.abs(dx) == HALF ? dz == 0 : dx == 0)) {
                        set(level, chunkBB, c, wx, y + 2, wz, Blocks.SPRUCE_FENCE.defaultBlockState());
                    }
                }
                if (roofArea) {
                    set(level, chunkBB, c, wx, y + WALL_H + 1, wz, roofBlock(hash));
                }
            }
        }

        placeDoor(level, chunkBB, c, cx, y + 1, cz + HALF);
        placeForge(level, chunkBB, c, cx, y + 1, cz - HALF + 1);
        placeFurnishings(level, chunkBB, c, cx, y, cz);
        spawnKeeper(level, chunkBB, cx, y, cz);
    }

    private void placeDoor(WorldGenLevel level, BoundingBox bb, BlockPos.MutableBlockPos c,
            int x, int y, int z) {
        BlockState lower = Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        set(level, bb, c, x, y, z, lower);
        set(level, bb, c, x, y + 1, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private void placeForge(WorldGenLevel level, BoundingBox bb, BlockPos.MutableBlockPos c,
            int x, int y, int z) {
        set(level, bb, c, x, y, z, ModBlocks.DRAGONLORD_FORGE.get().defaultBlockState()
                .setValue(DragonlordForgeBlock.FACING, Direction.SOUTH));
    }

    private void placeFurnishings(WorldGenLevel level, BoundingBox bb, BlockPos.MutableBlockPos c,
            int cx, int y, int cz) {
        set(level, bb, c, cx - 2, y + 1, cz + 2, Blocks.CRAFTING_TABLE.defaultBlockState());
        set(level, bb, c, cx + 2, y + 3, cz, Blocks.LANTERN.defaultBlockState());
        set(level, bb, c, cx + 2, y + 1, cz + 2, Blocks.SMITHING_TABLE.defaultBlockState());
    }

    private void spawnKeeper(WorldGenLevel level, BoundingBox chunkBB, int cx, int y, int cz) {
        if (this.spawnedKeeper) {
            return;
        }
        BlockPos spot = new BlockPos(cx, y + 1, cz);
        if (!chunkBB.isInside(spot)) {
            return;
        }
        this.spawnedKeeper = true;
        Villager keeper = EntityTypes.VILLAGER.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
        if (keeper != null) {
            keeper.setPersistenceRequired();
            keeper.snapTo(cx + 0.5, y + 1, cz + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
            keeper.finalizeSpawn(level, level.getCurrentDifficultyAt(spot),
                    EntitySpawnReason.STRUCTURE, null);
            level.addFreshEntityWithPassengers(keeper);
            AllUnderHeaven.LOGGER.info("[All Under Heaven] Dragon Keeper's hut at ({}, {}, {})",
                    cx, y, cz);
        }
    }

    private static void set(WorldGenLevel level, BoundingBox bb, BlockPos.MutableBlockPos c,
            int x, int y, int z, BlockState state) {
        c.set(x, y, z);
        if (bb.isInside(c)) {
            level.setBlock(c, state, 2);
        }
    }

    private int columnHash(int wx, int wz) {
        long h = wx * 341873128712L ^ wz * 132897987541L
                ^ (long) this.boundingBox.minX() * 31 ^ this.boundingBox.minZ();
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return (int) (h >>> 32) & 0x7FFFFFFF;
    }

    private static BlockState wallBlock(int hash) {
        return switch (hash % 8) {
            case 0, 1, 2 -> Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            case 3, 4 -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            case 5, 6 -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            default -> Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        };
    }

    private static BlockState floorBlock(int hash) {
        return (hash & 3) == 0 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                : Blocks.DARK_OAK_PLANKS.defaultBlockState();
    }

    private static BlockState roofBlock(int hash) {
        return (hash & 3) == 0 ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
                : Blocks.SPRUCE_PLANKS.defaultBlockState();
    }
}
