package com.manbear.entity;

import com.manbear.ManbearConfig;
import com.manbear.ai.ManbearBrain;
import com.manbear.ai.ManbearMoveControl;
import com.manbear.data.ManbearData;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ManbearEntity extends Monster implements GeoEntity {
    private static final EntityDataAccessor<Integer> DATA_STANCE = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_CLIMBING = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_TICKS = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_VARIANT = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);

    public static final int ATTACK_ANIM_TICKS = 14;

    // ---- animations (names come from manbear.animation.json) ----
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation HUNT = RawAnimation.begin().thenLoop("hunt");
    private static final RawAnimation CROUCH_WALK = RawAnimation.begin().thenLoop("crouch_walk");
    private static final RawAnimation CRAWL = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run2");
    private static final RawAnimation FAKE_RUN = RawAnimation.begin().thenLoop("fake_run");
    private static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("climb");
    private static final RawAnimation[] ATTACKS = {
            RawAnimation.begin().thenPlay("attack_1"),
            RawAnimation.begin().thenPlay("attack_2"),
            RawAnimation.begin().thenPlay("attack_3")
    };

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ManbearBrain ai;

    private int stanceCache = 0;
    private Entity faceTarget;
    private boolean facingMode;
    private float turnSpeed = 20F;

    public int lifeTicks;
    public boolean leaving;
    public final List<Long> traps = new ArrayList<>();

    public ManbearEntity(EntityType<? extends ManbearEntity> type, Level level) {
        super(type, level);
        this.setMaxUpStep(1.0F);
        this.xpReward = 40;
        this.noCulling = true;
        this.moveControl = new ManbearMoveControl(this);
        this.setPersistenceRequired();
        this.ai = new ManbearBrain(this);
        if (!level.isClientSide) {
            this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(ManbearConfig.DAMAGE.get());
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 160.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 1.2D)
                .add(Attributes.FOLLOW_RANGE, 80.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75D)
                .add(Attributes.ARMOR, 6.0D);
    }

    public ManbearBrain ai() { return ai; }

    // ------------------------------------------------------------------ setup
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WallClimberNavigation(this, level);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        final ManbearEntity self = this;
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                self.yBodyRot = self.getYRot();
            }
        };
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STANCE, 0);
        this.entityData.define(DATA_MODE, 0);
        this.entityData.define(DATA_CLIMBING, false);
        this.entityData.define(DATA_ATTACK_TICKS, 0);
        this.entityData.define(DATA_ATTACK_VARIANT, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_STANCE.equals(key)) {
            this.stanceCache = this.entityData.get(DATA_STANCE);
            this.refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return Stance.byId(stanceCache).dims;
    }

    // ------------------------------------------------------------------ state for the brain / client
    public Stance getStance() { return Stance.byId(stanceCache); }

    public void setStance(Stance s) {
        if (s.ordinal() != stanceCache) {
            this.stanceCache = s.ordinal();
            this.entityData.set(DATA_STANCE, s.ordinal());
            this.refreshDimensions();
        }
    }

    public ManbearMode getMode() { return ManbearMode.byId(this.entityData.get(DATA_MODE)); }
    public void setSyncedMode(ManbearMode m) { this.entityData.set(DATA_MODE, m.ordinal()); }

    public boolean isClimbingFlag() { return this.entityData.get(DATA_CLIMBING); }
    public void setClimbing(boolean b) { if (b != isClimbingFlag()) this.entityData.set(DATA_CLIMBING, b); }

    public int getAttackTicks() { return this.entityData.get(DATA_ATTACK_TICKS); }
    public int getAttackVariant() { return this.entityData.get(DATA_ATTACK_VARIANT); }

    /** variant 0..2 -> attack_1..attack_3 */
    public void triggerAttack(int variant) {
        this.entityData.set(DATA_ATTACK_VARIANT, Mth.clamp(variant, 0, 2));
        this.entityData.set(DATA_ATTACK_TICKS, ATTACK_ANIM_TICKS);
    }

    public void setFaceTarget(Entity e, float turnDegPerTick) {
        this.faceTarget = e;
        this.facingMode = e != null;
        this.turnSpeed = turnDegPerTick;
    }

    public boolean isFacingMode() { return facingMode; }

    public void impulse(Vec3 v) {
        this.setDeltaMovement(v);
        this.hasImpulse = true;
    }

    public void playBear(SoundEvent ev, float volume, float pitch) {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ev, SoundSource.HOSTILE, volume, pitch);
    }

    // ------------------------------------------------------------------ ticking
    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            int a = getAttackTicks();
            if (a > 0) this.entityData.set(DATA_ATTACK_TICKS, a - 1);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();

        // there can only ever be one
        if (this.tickCount % 20 == 1) {
            ManbearData data = ManbearData.get(server);
            if (!data.claim(this.getUUID(), server.overworld().getGameTime())) {
                this.discard();
                return;
            }
        }
        lifeTicks++;
        ai.tick(sl);
        applyFacing();
    }

    private void applyFacing() {
        if (faceTarget == null || !faceTarget.isAlive()) return;
        double dx = faceTarget.getX() - this.getX();
        double dz = faceTarget.getZ() - this.getZ();
        float want = (float) (Mth.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float diff = Mth.wrapDegrees(want - this.getYRot());
        float yaw = this.getYRot() + Mth.clamp(diff, -turnSpeed, turnSpeed);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.getLookControl().setLookAt(faceTarget, 40.0F, 40.0F);
    }

    // ------------------------------------------------------------------ climbing
    @Override
    public boolean onClimbable() {
        return isClimbingFlag();
    }

    // ------------------------------------------------------------------ damage
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source) || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FALL);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && this.level() instanceof ServerLevel sl) {
            ai.onHurt(sl, source.getEntity());
        }
        return result;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        releaseSlot();
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (reason.shouldDestroy()) releaseSlot();
        super.remove(reason);
    }

    private void releaseSlot() {
        if (this.level() instanceof ServerLevel sl) {
            MinecraftServer server = sl.getServer();
            long cd = ManbearConfig.RESPAWN_COOLDOWN_MINUTES.get() * 1200L;
            ManbearData.get(server).release(this.getUUID(), cd, server.overworld().getGameTime());
        }
    }

    public void onKilledPlayer(ServerPlayer p) {
        if (this.level() instanceof ServerLevel sl) {
            ManbearData.get(sl.getServer()).profile(p.getUUID()).deaths++;
            ai.beginLeaving(sl);
        }
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    // ------------------------------------------------------------------ sounds
    @Override
    protected SoundEvent getAmbientSound() { return null; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return ModSounds.HURT.get(); }

    @Override
    protected SoundEvent getDeathSound() { return ModSounds.DEATH.get(); }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        Stance s = getStance();
        if (s == Stance.CRAWL) return;
        float vol = s == Stance.CROUCH ? 0.25F : 0.9F;
        this.playSound(ModSounds.STEP.get(), vol, 0.85F + this.random.nextFloat() * 0.2F);
    }

    // ------------------------------------------------------------------ persistence
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("LifeTicks", lifeTicks);
        tag.putBoolean("Leaving", leaving);
        long[] arr = new long[traps.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = traps.get(i);
        tag.putLongArray("Traps", arr);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        lifeTicks = tag.getInt("LifeTicks");
        leaving = tag.getBoolean("Leaving");
        traps.clear();
        for (long l : tag.getLongArray("Traps")) traps.add(l);
    }

    // ------------------------------------------------------------------ trap hook
    public static void onTrapSprung(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ManbearData data = ManbearData.get(server);
        data.profile(player.getUUID()).trapsSprung++;
        data.setDirty();
        UUID id = data.activeId();
        if (id == null) return;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) {
                m.ai.ambush(player, pos);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ GeckoLib
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 6, this::predicate));
    }

    private PlayState predicate(AnimationState<ManbearEntity> state) {
        if (getAttackTicks() > 0) {
            return state.setAndContinue(ATTACKS[Mth.clamp(getAttackVariant(), 0, 2)]);
        }
        if (isClimbingFlag()) return state.setAndContinue(CLIMB);
        boolean moving = state.isMoving();
        Stance stance = getStance();
        ManbearMode mode = getMode();
        if (stance == Stance.CRAWL) return state.setAndContinue(moving ? CRAWL : HUNT);
        if (mode.isChaseLike() && moving) return state.setAndContinue(RUN);
        if (mode == ManbearMode.FAKE_CHASE && moving) return state.setAndContinue(FAKE_RUN);
        if (stance == Stance.CROUCH) return state.setAndContinue(moving ? CROUCH_WALK : HUNT);
        return state.setAndContinue(moving ? WALK : IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
