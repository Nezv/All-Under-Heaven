package dev.nez.allunderheaven.feature.dragon;

import java.util.EnumSet;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;

import dev.nez.allunderheaven.registry.ModParticles;
import dev.nez.allunderheaven.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The adult wyvern. It is bound to its gold nest: any player who walks into
 * the guard radius becomes a target, and a target that runs is CHASED — on
 * the ground first, then from the air. Flight is a velocity-steered state
 * machine (takeoff, cruise/pursuit, strafing fire runs, landing approach)
 * with turn-rate limits and altitude holding, so the dragon banks through
 * real arcs instead of pivoting. Fire is a sustained cone in the variant's
 * own flame color that burns victims and (mobGriefing) lights the ground.
 *
 * Animations are the GeckoLib clips exported by tools/dragon/build_dragon.py:
 * idle/walk/fire on the ground; fly/fly_vertical/glide/fly_fire in the air.
 */
public class DragonEntity extends PathfinderMob implements GeoEntity, NeutralMob {
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_BREATHING =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_FLYING =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);

    private static final UniformInt PERSISTENT_ANGER_TIME = TimeUtil.rangeOfSeconds(24, 45);

    /** Players inside this radius of the nest are treated as raiders. */
    public static final double GUARD_RADIUS = 24.0;
    /** Ground combat hands over to the wings beyond this distance. */
    private static final double AIR_CHASE_RANGE = 15.0;

    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("animation.wyvern.idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("animation.wyvern.walk");
    private static final RawAnimation ANIM_FIRE = RawAnimation.begin().thenLoop("animation.wyvern.fire");
    private static final RawAnimation ANIM_FLY = RawAnimation.begin().thenLoop("animation.wyvern.fly");
    private static final RawAnimation ANIM_FLY_UP = RawAnimation.begin().thenLoop("animation.wyvern.fly_vertical");
    private static final RawAnimation ANIM_GLIDE = RawAnimation.begin().thenLoop("animation.wyvern.glide");
    private static final RawAnimation ANIM_FLY_FIRE = RawAnimation.begin().thenLoop("animation.wyvern.fly_fire");

    private final AnimatableInstanceCache geckoCache = GeckoLibUtil.createInstanceCache(this);

    private long persistentAngerEndTime;
    private @Nullable EntityReference<LivingEntity> persistentAngerTarget;
    private boolean variantLocked;

    // client-side lean: the renderer banks into turns and pitches into
    // climbs/dives from these smoothed values (no sync needed - both are
    // derived from already-synced yaw/velocity)
    public float bankSmooth, bankSmoothO;
    public float pitchSmooth, pitchSmoothO;
    private float prevBodyYaw;

    // client-side procedural pose state (a DragonPoseFrame; held loosely so
    // this common-side class needs no client import). Its terrain-IK springs
    // persist here across render frames.
    public Object poseState;

    public DragonEntity(EntityType<? extends DragonEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 160.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.6);
    }

    // ------------------------------------------------------------- variant

    public DragonVariant getVariant() {
        return DragonVariant.byId(this.entityData.get(DATA_VARIANT));
    }

    public int getVariantId() {
        return this.entityData.get(DATA_VARIANT);
    }

    public void setVariant(DragonVariant variant) {
        this.entityData.set(DATA_VARIANT, variant.id());
        this.variantLocked = true;
    }

    public boolean isBreathingFire() {
        return this.entityData.get(DATA_BREATHING);
    }

    private void setBreathingFire(boolean breathing) {
        if (breathing && !this.isBreathingFire()) {
            this.playSound(ModSounds.DRAGON_FIRE.get(), 2.4F, 1.0F);
        }
        this.entityData.set(DATA_BREATHING, breathing);
    }

    public boolean isFlying() {
        return this.entityData.get(DATA_FLYING);
    }

    private void setFlying(boolean flying) {
        this.entityData.set(DATA_FLYING, flying);
        this.setNoGravity(flying);
    }

    /** Ties the dragon to its nest: guard trigger, stroll tether, flight
     *  home. Rides the vanilla Mob home system (persisted as home_pos). */
    public void setNest(BlockPos pos) {
        this.setHomeTo(pos.immutable(), (int) GUARD_RADIUS);
    }

    public @Nullable BlockPos getNest() {
        return this.getHomeRadius() == -1 ? null : this.getHomePosition();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_VARIANT, 0);
        entityData.define(DATA_BREATHING, false);
        entityData.define(DATA_FLYING, false);
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            EntitySpawnReason spawnReason, @Nullable SpawnGroupData groupData) {
        if (!this.variantLocked) {
            this.setVariant(DragonVariant.byId(level.getRandom().nextInt(DragonVariant.values().length)));
        }
        return super.finalizeSpawn(level, difficulty, spawnReason, groupData);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Variant", this.getVariantId());
        this.addPersistentAngerSaveData(output);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_VARIANT, input.getIntOr("Variant", 0));
        this.variantLocked = true;
        this.readPersistentAngerSaveData(this.level(), input);
    }

    // ----------------------------------------------------------------- AI

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FlightGoal());
        this.goalSelector.addGoal(2, new FireBreathGoal());
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.3, true));
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 18.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                (target, level) -> this.isThreateningNest(target)));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, this::isAngryAt));
        this.targetSelector.addGoal(4, new ResetUniversalAngerTargetGoal<>(this, false));
    }

    /** Nest defense trigger: anyone inside the guard radius of the hoard. */
    private boolean isThreateningNest(LivingEntity intruder) {
        BlockPos nest = this.getNest();
        return nest != null
                && intruder.distanceToSqr(Vec3.atCenterOf(nest)) < GUARD_RADIUS * GUARD_RADIUS;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.updateClientLean();
        } else {
            this.updatePersistentAnger((ServerLevel) this.level(), true);
            if (this.isFlying()) {
                this.fallDistance = 0.0F;
                if (this.tickCount % 42 == 0) {
                    this.playSound(ModSounds.DRAGON_FLAP.get(), 1.6F, 0.95F + this.random.nextFloat() * 0.1F);
                }
            }
            if (this.isBreathingFire() && this.tickCount % 45 == 0) {
                this.playSound(ModSounds.DRAGON_FIRE.get(), 2.4F, 1.0F); // re-arm the loop
            }
        }
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
        return false; // wings
    }

    // ---------------------------------------------------------- neutrality

    @Override
    public void startPersistentAngerTimer() {
        this.setTimeToRemainAngry(PERSISTENT_ANGER_TIME.sample(this.random));
    }

    @Override
    public void setPersistentAngerEndTime(long endTime) {
        this.persistentAngerEndTime = endTime;
    }

    @Override
    public long getPersistentAngerEndTime() {
        return this.persistentAngerEndTime;
    }

    @Override
    public void setPersistentAngerTarget(@Nullable EntityReference<LivingEntity> persistentAngerTarget) {
        this.persistentAngerTarget = persistentAngerTarget;
    }

    @Override
    public @Nullable EntityReference<LivingEntity> getPersistentAngerTarget() {
        return this.persistentAngerTarget;
    }

    // -------------------------------------------------------------- sounds

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.DRAGON_GROWL.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 240; // it rumbles when it wants to, not on a parrot schedule
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.DRAGON_ROAR.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.DRAGON_DEATH.get();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState blockState) {
        this.playSound(ModSounds.DRAGON_STEP.get(), 0.6F, 0.9F + this.random.nextFloat() * 0.2F);
    }

    /** A felled dragon bleeds — its blood fuels the Dragon-lord Forge — and
     *  sheds the star grit fused into its hide. */
    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level,
            DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        int blood = 2 + this.random.nextInt(3);              // 2..4 phials
        this.spawnAtLocation(level,
                new net.minecraft.world.item.ItemStack(dev.nez.allunderheaven.registry.ModItems.DRAGON_BLOOD.get(), blood));
        int dust = 1 + this.random.nextInt(3);               // 1..3 star dust
        this.spawnAtLocation(level,
                new net.minecraft.world.item.ItemStack(dev.nez.allunderheaven.registry.ModItems.STAR_DUST.get(), dust));
    }

    // ------------------------------------------------------------ GeckoLib

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geckoCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<DragonEntity>("base", 8, test -> {
            DragonEntity dragon = test.animatable();
            boolean flying = dragon.isFlying();
            if (dragon.isBreathingFire()) {
                return test.setAndContinue(flying ? ANIM_FLY_FIRE : ANIM_FIRE);
            }
            if (flying) {
                double vy = dragon.getDeltaMovement().y;
                if (vy > 0.12) {
                    return test.setAndContinue(ANIM_FLY_UP);
                }
                if (vy < -0.14) {
                    return test.setAndContinue(ANIM_GLIDE);
                }
                return test.setAndContinue(ANIM_FLY);
            }
            return test.setAndContinue(test.isMoving() ? ANIM_WALK : ANIM_IDLE);
        }));
    }

    /** Client-only: smooth bank (from yaw rate) and pitch (from velocity). */
    private void updateClientLean() {
        this.bankSmoothO = this.bankSmooth;
        this.pitchSmoothO = this.pitchSmooth;
        float yawDelta = Mth.degreesDifference(this.prevBodyYaw, this.yBodyRot);
        this.prevBodyYaw = this.yBodyRot;
        float targetBank = this.isFlying() ? Mth.clamp(yawDelta * 9.0F, -38.0F, 38.0F) : 0.0F;
        this.bankSmooth += (targetBank - this.bankSmooth) * 0.12F;
        Vec3 vel = this.getDeltaMovement();
        double hSpeed = Math.hypot(vel.x, vel.z);
        float targetPitch = this.isFlying()
                ? (float) Mth.clamp(-Math.toDegrees(Math.atan2(vel.y, Math.max(hSpeed, 0.15))), -32.0, 32.0)
                : 0.0F;
        this.pitchSmooth += (targetPitch - this.pitchSmooth) * 0.1F;
    }

    // ---------------------------------------------------------- fire breath

    /** Where the blast leaves the mouth: ahead of the eyes, a touch low. */
    private Vec3 mouthOrigin() {
        Vec3 look = this.getLookAngle();
        return this.getEyePosition().add(look.scale(2.6)).add(0.0, -0.4, 0.0);
    }

    /**
     * One tick of the breath cone from {@code mouth} along {@code dir}:
     * flame particles tinted with the variant's gradient (cooling core to
     * outer over distance) plus trailing smoke; every few ticks everything
     * living inside the widening cone is burned, and with mobGriefing the
     * impact zone catches real fire.
     */
    private void breatheFireTick(ServerLevel level, Vec3 mouth, Vec3 dir, double dist) {
        DragonVariant variant = this.getVariant();
        for (int i = 0; i < 5; i++) {
            double q = Math.pow(this.random.nextDouble(), 0.8);
            double d = 1.0 + q * dist;
            double spread = 0.25 + q * 1.7;
            Vec3 at = mouth.add(dir.scale(d));
            int color = q < 0.35 ? variant.coreColor : q < 0.75 ? variant.midColor : variant.outerColor;
            level.sendParticles(
                    ColorParticleOption.create(ModParticles.DRAGON_FLAME.get(), 0xFF000000 | color),
                    at.x, at.y, at.z, 2, spread * 0.5, spread * 0.4, spread * 0.5, 0.02);
        }
        if (this.random.nextInt(3) == 0) {
            double q = 0.65 + this.random.nextDouble() * 0.35;
            Vec3 at = mouth.add(dir.scale(q * dist));
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 1, 0.6, 0.5, 0.6, 0.01);
        }

        if (this.tickCount % 5 == 0) {
            AABB cone = new AABB(mouth, mouth.add(dir.scale(dist))).inflate(2.5);
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, cone)) {
                if (victim == this || victim instanceof DragonEntity) {
                    continue;
                }
                Vec3 toVictim = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0).subtract(mouth);
                double along = toVictim.dot(dir);
                if (along < 0.0 || along > dist + 2.0) {
                    continue;
                }
                if (toVictim.subtract(dir.scale(along)).length() > 1.5 + along * 0.22) {
                    continue; // outside the widening cone
                }
                victim.igniteForSeconds(6.0F);
                victim.hurtServer(level, this.damageSources().mobAttack(this), 4.0F);
            }

            if (level.getGameRules().get(GameRules.MOB_GRIEFING)) {
                Vec3 impact = mouth.add(dir.scale(dist));
                for (int i = 0; i < 2; i++) {
                    BlockPos at = BlockPos.containing(
                            impact.x + (this.random.nextDouble() - 0.5) * 3.0,
                            impact.y + this.random.nextDouble() * 1.5,
                            impact.z + (this.random.nextDouble() - 0.5) * 3.0);
                    if (level.isEmptyBlock(at) && BaseFireBlock.canBePlacedAt(level, at, this.getDirection())) {
                        level.setBlockAndUpdate(at, BaseFireBlock.getState(level, at));
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- flight

    /** Velocity steering with limited acceleration and turn rate: the dragon
     *  flies arcs, banks into corners and cannot stop on a dime. */
    private void flyToward(Vec3 waypoint, double speed, double turnRateDeg) {
        Vec3 to = waypoint.subtract(this.position());
        double dist = to.length();
        if (dist < 1.0E-3) {
            return;
        }
        Vec3 desired = to.scale(speed / dist);
        desired = new Vec3(desired.x, Mth.clamp(desired.y, -0.6, 0.45), desired.z);
        Vec3 vel = this.getDeltaMovement();
        Vec3 steer = desired.subtract(vel);
        double steerLen = steer.length();
        if (steerLen > 0.07) {
            steer = steer.scale(0.07 / steerLen);
        }
        Vec3 newVel = vel.add(steer);
        this.setDeltaMovement(newVel);
        double hSpeed = Math.hypot(newVel.x, newVel.z);
        if (hSpeed > 0.05) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(-newVel.x, newVel.z));
            float yaw = Mth.approachDegrees(this.getYRot(), targetYaw, (float) turnRateDeg);
            this.setYRot(yaw);
            this.yBodyRot = yaw;
            this.yHeadRot = yaw;
        }
    }

    private int groundHeight(double x, double z) {
        return this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(x), Mth.floor(z));
    }

    /**
     * The whole airborne life of the dragon, as a single high-priority goal
     * owning MOVE+LOOK+JUMP while active:
     *
     *   TAKEOFF -> CRUISE -> (STRAFE fire runs while hunting) -> LANDING
     *
     * CRUISE pursues the target with velocity lead and altitude holding,
     * ferries the dragon home when it has drifted, and flies an occasional
     * patrol lap around the nest. STRAFE dives past the target hosing the
     * cone. LANDING approaches a surveyed spot and flares onto it.
     */
    private class FlightGoal extends Goal {
        private static final int PATROL_TICKS = 240;

        private int phase; // 0 takeoff, 1 cruise, 2 strafe, 3 landing
        private double takeoffY;
        private int strafeTicks;
        private int strafeCooldown;
        private int patrolTicks;
        private double patrolAngle;
        private int airborneTicks;
        private @Nullable BlockPos landSpot;

        FlightGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            if (DragonEntity.this.isFlying()) {
                return true;
            }
            if (DragonEntity.this.isInWater() || DragonEntity.this.isVehicle()) {
                return false;
            }
            LivingEntity target = DragonEntity.this.getTarget();
            if (target != null && target.isAlive()) {
                // the chase takes to the air when the target pulls away or
                // stands somewhere ground pathing can't reach
                double dist = DragonEntity.this.distanceTo(target);
                boolean unreachable = DragonEntity.this.getNavigation().isDone()
                        && dist > 6.0;
                return dist > AIR_CHASE_RANGE || target.getY() > DragonEntity.this.getY() + 5.0 || unreachable;
            }
            BlockPos nest = DragonEntity.this.getNest();
            if (nest != null) {
                double distSq = DragonEntity.this.distanceToSqr(Vec3.atCenterOf(nest));
                if (distSq > 48.0 * 48.0) {
                    return true; // drifted too far - fly home
                }
                // the occasional patrol lap over the hoard
                return DragonEntity.this.getRandom().nextInt(reducedTickDelay(1400)) == 0;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return DragonEntity.this.isFlying();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            this.phase = 0;
            this.airborneTicks = 0;
            this.takeoffY = DragonEntity.this.getY();
            this.landSpot = null;
            this.patrolTicks = DragonEntity.this.getTarget() == null ? PATROL_TICKS : 0;
            DragonEntity.this.setFlying(true);
            DragonEntity.this.getNavigation().stop();
            DragonEntity.this.setDeltaMovement(DragonEntity.this.getDeltaMovement().add(0.0, 0.55, 0.0));
            DragonEntity.this.playSound(ModSounds.DRAGON_FLAP.get(), 2.2F, 0.9F);
        }

        @Override
        public void stop() {
            DragonEntity.this.setBreathingFire(false);
            DragonEntity.this.setFlying(false);
        }

        @Override
        public void tick() {
            DragonEntity dragon = DragonEntity.this;
            this.airborneTicks++;
            if (this.airborneTicks > 20 * 90 && this.phase < 3) {
                this.beginLanding(); // never circle forever
            }
            switch (this.phase) {
                case 0 -> this.tickTakeoff();
                case 1 -> this.tickCruise();
                case 2 -> this.tickStrafe();
                case 3 -> this.tickLanding();
            }
            if (dragon.isInWater()) {
                dragon.setDeltaMovement(dragon.getDeltaMovement().add(0.0, 0.08, 0.0));
            }
        }

        private void tickTakeoff() {
            DragonEntity dragon = DragonEntity.this;
            Vec3 look = dragon.getLookAngle();
            Vec3 up = dragon.position().add(look.x * 6.0, 14.0, look.z * 6.0);
            dragon.flyToward(up, 0.95, 6.0);
            int ground = dragon.groundHeight(dragon.getX(), dragon.getZ());
            if (dragon.getY() > this.takeoffY + 10.0 || dragon.getY() > ground + 12.0) {
                this.phase = 1;
            }
        }

        private void tickCruise() {
            DragonEntity dragon = DragonEntity.this;
            LivingEntity target = dragon.getTarget();
            this.strafeCooldown--;

            if (target != null && target.isAlive()) {
                double dist = dragon.distanceTo(target);
                if (this.strafeCooldown <= 0 && dist < 26.0 && dragon.hasLineOfSight(target)) {
                    this.phase = 2;
                    this.strafeTicks = 55;
                    dragon.playSound(ModSounds.DRAGON_ROAR.get(), 2.4F, 1.0F);
                    return;
                }
                // pursuit: lead the target, hold a hunting altitude above it
                Vec3 lead = target.position().add(target.getDeltaMovement().scale(12.0));
                double alt = Math.max(lead.y + 8.0, dragon.groundHeight(lead.x, lead.z) + 7.0);
                dragon.flyToward(new Vec3(lead.x, alt, lead.z), 1.2, 5.5);
                // touch down onto a cornered target: close and low -> land on it
                if (dist < 9.0 && dragon.getY() - target.getY() < 6.0
                        && target.getDeltaMovement().horizontalDistanceSqr() < 0.01) {
                    this.landSpot = target.blockPosition();
                    this.beginLanding();
                }
                return;
            }

            BlockPos home = dragon.getNest();
            if (home != null && dragon.distanceToSqr(Vec3.atCenterOf(home)) > 20.0 * 20.0) {
                Vec3 wp = Vec3.atCenterOf(home).add(0.0, 14.0, 0.0);
                dragon.flyToward(wp, 0.95, 4.5);
                return;
            }
            if (this.patrolTicks-- > 0 && home != null) {
                this.patrolAngle += 0.045;
                Vec3 wp = Vec3.atCenterOf(home).add(
                        Math.cos(this.patrolAngle) * 18.0, 12.0, Math.sin(this.patrolAngle) * 18.0);
                double minAlt = dragon.groundHeight(wp.x, wp.z) + 6.0;
                dragon.flyToward(new Vec3(wp.x, Math.max(wp.y, minAlt), wp.z), 0.85, 4.0);
                return;
            }
            this.beginLanding();
        }

        private void tickStrafe() {
            DragonEntity dragon = DragonEntity.this;
            LivingEntity target = dragon.getTarget();
            this.strafeTicks--;
            if (target == null || !target.isAlive() || this.strafeTicks <= 0) {
                dragon.setBreathingFire(false);
                this.strafeCooldown = 70 + dragon.getRandom().nextInt(50);
                this.phase = 1;
                return;
            }
            // the strafing run: fly a line over and past the target
            Vec3 past = target.position().subtract(dragon.position()).normalize();
            Vec3 wp = target.position().add(past.scale(10.0)).add(0.0, 7.0, 0.0);
            dragon.flyToward(wp, 1.05, 5.0);

            Vec3 mouth = dragon.mouthOrigin();
            Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(mouth);
            double dist = aim.length();
            Vec3 dir = aim.normalize();
            // hose while the head can plausibly bear on the target
            boolean canBear = dir.dot(dragon.getLookAngle()) > 0.35 && dist < 20.0;
            dragon.setBreathingFire(canBear);
            if (canBear) {
                dragon.breatheFireTick((ServerLevel) dragon.level(), mouth, dir, Math.min(dist, 18.0));
            }
            if (past.dot(dragon.getDeltaMovement()) < 0.0) {
                // overshot - swing round for another pass
                dragon.setBreathingFire(false);
                this.strafeCooldown = 60 + dragon.getRandom().nextInt(40);
                this.phase = 1;
            }
        }

        private void beginLanding() {
            DragonEntity dragon = DragonEntity.this;
            dragon.setBreathingFire(false);
            if (this.landSpot == null) {
                BlockPos nest = dragon.getNest();
                BlockPos base = nest != null
                        && dragon.distanceToSqr(Vec3.atCenterOf(nest)) < 40.0 * 40.0
                                ? nest
                                : dragon.blockPosition();
                this.landSpot = new BlockPos(base.getX(),
                        dragon.groundHeight(base.getX(), base.getZ()), base.getZ());
            }
            this.phase = 3;
        }

        private void tickLanding() {
            DragonEntity dragon = DragonEntity.this;
            if (this.landSpot == null) {
                this.beginLanding();
                return;
            }
            Vec3 spot = Vec3.atBottomCenterOf(this.landSpot);
            double hDist = Math.hypot(dragon.getX() - spot.x, dragon.getZ() - spot.z);
            if (hDist > 3.0) {
                // approach glide toward a point above the spot
                dragon.flyToward(spot.add(0.0, 5.0, 0.0), 0.75, 4.5);
            } else {
                // flare: kill drift, sink under control
                Vec3 vel = dragon.getDeltaMovement();
                dragon.setDeltaMovement(vel.x * 0.6, Math.max(vel.y - 0.05, -0.28), vel.z * 0.6);
            }
            if (dragon.onGround()) {
                dragon.setFlying(false); // canContinueToUse ends the goal
            }
        }
    }

    /**
     * The grounded fire attack: plant, aim, hose the cone for ~3 seconds
     * with the head raking, then cool down. (The airborne equivalent lives
     * in the flight goal's strafing run.)
     */
    private class FireBreathGoal extends Goal {
        private static final double RANGE = 22.0;
        private int aimTicks;
        private int breathTicks;
        private long nextBreathTime;

        FireBreathGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (DragonEntity.this.isFlying()) {
                return false;
            }
            LivingEntity target = DragonEntity.this.getTarget();
            return target != null && target.isAlive()
                    && DragonEntity.this.level().getGameTime() >= this.nextBreathTime
                    && DragonEntity.this.distanceToSqr(target) < RANGE * RANGE
                    && DragonEntity.this.hasLineOfSight(target);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = DragonEntity.this.getTarget();
            return !DragonEntity.this.isFlying() && this.breathTicks > 0
                    && target != null && target.isAlive()
                    && DragonEntity.this.distanceToSqr(target) < (RANGE + 8.0) * (RANGE + 8.0);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            this.aimTicks = 12;
            this.breathTicks = 64;
            DragonEntity.this.getNavigation().stop();
            DragonEntity.this.playSound(ModSounds.DRAGON_ROAR.get(), 2.4F, 1.0F);
        }

        @Override
        public void stop() {
            DragonEntity.this.setBreathingFire(false);
            this.nextBreathTime = DragonEntity.this.level().getGameTime()
                    + 90 + DragonEntity.this.random.nextInt(70);
        }

        @Override
        public void tick() {
            LivingEntity target = DragonEntity.this.getTarget();
            if (target == null) {
                this.breathTicks = 0;
                return;
            }
            DragonEntity.this.getNavigation().stop();
            DragonEntity.this.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (this.aimTicks-- > 0) {
                return;
            }
            DragonEntity.this.setBreathingFire(true);
            this.breathTicks--;

            Vec3 mouth = DragonEntity.this.mouthOrigin();
            Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(mouth);
            double dist = Math.min(aim.length(), RANGE);
            if (dist < 1.0E-3) {
                return;
            }
            DragonEntity.this.breatheFireTick((ServerLevel) DragonEntity.this.level(),
                    mouth, aim.normalize(), dist);
        }
    }
}
