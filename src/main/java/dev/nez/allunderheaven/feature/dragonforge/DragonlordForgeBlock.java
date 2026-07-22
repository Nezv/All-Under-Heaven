package dev.nez.allunderheaven.feature.dragonforge;

import com.mojang.serialization.MapCodec;

import dev.nez.allunderheaven.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.particles.ParticleTypes;

/**
 * The Dragon-lord Forge: a furnace-shaped block that opens the forge menu and
 * runs its block entity's burn/cook loop. Faces the placer, glows and smokes
 * from its maw while lit. Crafted with the Dragon Keeper's help (recipe in
 * datagen), it is also the Keeper's job-site POI.
 */
public class DragonlordForgeBlock extends BaseEntityBlock {
    public static final MapCodec<DragonlordForgeBlock> CODEC = simpleCodec(DragonlordForgeBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public DragonlordForgeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DragonlordForgeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, ModBlockEntities.DRAGONLORD_FORGE.get(),
                        DragonlordForgeBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof DragonlordForgeBlockEntity forge) {
            player.openMenu(forge, b -> b.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.55 + random.nextDouble() * 0.25;
        double z = pos.getZ() + 0.5;
        Direction facing = state.getValue(FACING);
        double off = random.nextDouble() * 0.4 - 0.2;
        double fx = facing.getStepX() == 0 ? off : facing.getStepX() * 0.52;
        double fz = facing.getStepZ() == 0 ? off : facing.getStepZ() * 0.52;
        level.addParticle(ParticleTypes.SMOKE, x + fx, y, z + fz, 0.0, 0.0, 0.0);
        level.addParticle(ParticleTypes.FLAME, x + fx, y, z + fz, 0.0, 0.0, 0.0);
    }
}
