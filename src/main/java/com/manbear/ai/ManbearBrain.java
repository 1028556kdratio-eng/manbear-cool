package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.entity.Stance;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The decision maker. He is always in exactly one mode, but can switch at any moment:
 * stalk -> peek -> fake chase -> real chase and back, with weights shaped by what he has
 * learned about the player (see PlayerProfile).
 */
public class ManbearBrain {
    private enum Lunge { NONE, WINDUP, AIR, RECOVER }

    private final ManbearEntity m;
    private final BlockBreaker breaker = new BlockBreaker();

    private ManbearMode mode = ManbearMode.STALK;
    private int modeTime;
    private int modeDuration = 600;

    private ServerPlayer target;
    private int targetLock;
    private int ticksAlive;
    private int noTargetTicks;
    private int lastChaseEnd;

    // awareness
    private double dist;
    private boolean los;
    private boolean looking;
    private int lookedTicks;

    // learned traits (0..1)
    private PlayerProfile prof;
    private float vig = 0.5F, panic = 0.5F, aggr = 0F, camp = 0F, sprint = 0.3F;

    // movement
    private int pathCd;
    private boolean navFail;
    private int navFailCount;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuckTicks;
    private int stanceLock;
    private int unreachableTicks;

    // stalk
    private int orbitDir = 1;
    private int orbitFlip = 200;
    private double stalkRadius = 18;
    private int freezeTicks;

    // hide / peek
    private CoverFinder.Cover cover;
    private boolean atHide;
    private int waitTicks;
    private int peekPhase;
    private int peekTimer;
    private int peeksLeft;

    // fake chase
    private int fakePhase;
    private double fakeStop;
    private int fakeFreeze;

    // chase
    private int chaseTicks;
    private int lungeCd;
    private int meleeCd;
    private int pendingMelee;
    private int hitsLanded;
    private Lunge lunge = Lunge.NONE;
    private int lungeTimer;
    private boolean lungeHit;
    private boolean ambushing;

    // traps
    private BlockPos trapSite;
    private int trapPhase;
    private int trapTimer;
    private int trapCooldown = 700;

    // ambience
    private int ambientCd = 200;
    private int coverFailCd;

    public ManbearBrain(ManbearEntity m) { this.m = m; }

    private RandomSource rnd() { return m.getRandom(); }

    public ManbearMode mode() { return mode; }

    public String debugString() {
        return "mode=" + mode + " t=" + modeTime + "/" + modeDuration + " dist=" + (int) dist
                + " looking=" + looking + " vig=" + String.format("%.2f", vig) + " panic=" + String.format("%.2f", panic)
                + " traps=" + m.traps.size() + " target=" + (target == null ? "none" : target.getGameProfile().getName());
    }

    // ================================================================= main tick
    public void tick(ServerLevel level) {
        ticksAlive++;
        modeTime++;
        if (trapCooldown > 0) trapCooldown--;
        if (lungeCd > 0) lungeCd--;
        if (meleeCd > 0) meleeCd--;
        if (stanceLock > 0) stanceLock--;
        if (ambientCd > 0) ambientCd--;
        if (coverFailCd > 0) coverFailCd--;
        if (targetLock > 0) targetLock--;

        if (!m.leaving && m.lifeTicks > ManbearConfig.LIFETIME_MINUTES.get() * 1200) beginLeaving(level);

        resolveTarget(level);
        if (target == null) {
            m.setFaceTarget(null, 0F);
            m.getNavigation().stop();
            m.setClimbing(false);
            if (++noTargetTicks > 1200) m.discard();
            return;
        }
        noTargetTicks = 0;
        if (ticksAlive % 20 == 1 || prof == null) refreshTraits(level);
        sense();
        m.setFaceTarget(target, mode.isChaseLike() ? 38F : 16F);
        updateStance(level);
        ambient();

        switch (mode) {
            case STALK -> tickStalk(level);
            case HIDE -> tickHide(level);
            case PEEK -> tickPeek(level);
            case FAKE_CHASE -> tickFake(level);
            case CHASE, AMBUSH -> tickChase(level);
            case LAY_TRAP -> tickTrap(level);
            case RETREAT -> tickRetreat(level);
        }

        updateClimb();
        spontaneous(level);
        if (modeTime >= modeDuration) onModeExpired(level);
    }

    // ================================================================= target / senses / traits
    private void resolveTarget(ServerLevel level) {
        if (target != null && !validTarget(target)) target = null;
        if (target == null || (targetLock <= 0 && ticksAlive % 100 == 0)) {
            ServerPlayer best = null;
            double bd = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                if (!validTarget(p)) continue;
                double d = p.distanceToSqr(m);
                if (p == target) d -= 24 * 24;
                if (d < bd) { bd = d; best = p; }
            }
            target = best;
        }
    }

    private boolean validTarget(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == m.level() && m.distanceToSqr(p) < 110 * 110;
    }

    private void sense() {
        dist = m.distanceTo(target);
        los = m.hasLineOfSight(target);
        Vec3 look = target.getViewVector(1F);
        Vec3 to = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        looking = los && dist < 70 && look.dot(to) > 0.72;
        lookedTicks = looking ? lookedTicks + 1 : Math.max(0, lookedTicks - 2);
    }

    private void refreshTraits(ServerLevel level) {
        ManbearData data = ManbearData.get(level.getServer());
        prof = data.profile(target.getUUID());
        if (ManbearConfig.ADAPTATION.get()) {
            vig = prof.vigilance; panic = prof.panic; aggr = prof.aggression;
            camp = prof.camping; sprint = prof.sprintiness;
        } else {
            vig = 0.5F; panic = 0.5F; aggr = 0F; camp = 0F; sprint = 0.3F;
        }
        if (ticksAlive % 600 == 1) data.setDirty();
    }

    // ================================================================= mode switching
    private void setMode(ServerLevel level, ManbearMode nm) {
        if (nm == mode && modeTime < 5) return;
        exitMode(level);
        mode = nm;
        modeTime = 0;
        m.setSyncedMode(nm);
        navFail = false;
        navFailCount = 0;
        unreachableTicks = 0;
        stuckTicks = 0;
        pathCd = 0;
        m.getNavigation().stop();
        enterMode(level);
    }

    private void exitMode(ServerLevel level) {
        breaker.cancel(level, m);
        lunge = Lunge.NONE;
        if (mode.isChaseLike()) ambushing = false;
    }

    private void enterMode(ServerLevel level) {
        RandomSource r = rnd();
        switch (mode) {
            case STALK -> {
                modeDuration = 400 + r.nextInt(700);
                stalkRadius = Mth.clamp(14 + r.nextInt(14) - 5 * (1 - vig) + 5 * vig - 3 * camp, 9, 32);
                orbitDir = r.nextBoolean() ? 1 : -1;
                orbitFlip = 120 + r.nextInt(240);
                freezeTicks = 0;
            }
            case HIDE -> {
                modeDuration = 700;
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, 11, 24);
                if (cover == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                atHide = false;
                waitTicks = 80 + r.nextInt(140);
            }
            case PEEK -> {
                modeDuration = 900;
                if (cover == null || cover.peek() == null) cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, 11, 24);
                if (cover == null || cover.peek() == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                peekPhase = 0;
                peeksLeft = 1 + r.nextInt(3);
            }
            case FAKE_CHASE -> {
                modeDuration = 220;
                fakePhase = 0;
                fakeStop = 7 + r.nextInt(5);
                if (r.nextFloat() < 0.55F) m.playBear(ModSounds.ROAR.get(), 3.5F, 0.85F + r.nextFloat() * 0.2F);
            }
            case CHASE, AMBUSH -> {
                float panicScale = 0.8F + 0.6F * panic;
                modeDuration = (int) ((600 + r.nextInt(900)) * panicScale);
                if (mode == ManbearMode.AMBUSH) modeDuration = 700;
                chaseTicks = 0;
                hitsLanded = 0;
                lunge = Lunge.NONE;
                pendingMelee = 0;
                lungeCd = ambushing ? 0 : 30 + r.nextInt(30);
                freezeTicks = 0;
                if (prof != null) prof.chases++;
                // unpredictable start: sometimes he roars first, sometimes he just stares for a breath, sometimes silence
                float roll = r.nextFloat();
                if (ambushing || roll < 0.45F) m.playBear(ModSounds.ROAR.get(), 4.5F, 0.8F + r.nextFloat() * 0.2F);
                if (!ambushing && roll > 0.7F) freezeTicks = 10 + r.nextInt(14);
            }
            case LAY_TRAP -> {
                modeDuration = 900;
                trapSite = null;
                if (trapCooldown <= 0 && m.traps.size() < Integer.MAX_VALUE && ManbearConfig.MAX_TRAPS.get() > 0) {
                    trapSite = CoverFinder.findTrapSite(level, target, prof, r);
                }
                if (trapSite == null) { trapCooldown = 300; setMode(level, ManbearMode.STALK); return; }
                trapPhase = 0;
            }
            case RETREAT -> modeDuration = m.leaving ? 1_000_000 : 100 + r.nextInt(120);
        }
    }

    private void onModeExpired(ServerLevel level) {
        if (m.leaving) { modeTime = 0; return; }
        switch (mode) {
            case CHASE, AMBUSH -> endChase(level);
            case FAKE_CHASE -> setMode(level, ManbearMode.RETREAT);
            case RETREAT -> setMode(level, rnd().nextFloat() < 0.55F ? ManbearMode.HIDE : ManbearMode.STALK);
            default -> setMode(level, pickNextMode(false));
        }
    }

    private ManbearMode pickNextMode(boolean spontaneous) {
        boolean canChase = ticksAlive >= ManbearConfig.GRACE_SECONDS.get() * 20;
        double tension = Mth.clamp(0.25 + (ticksAlive - lastChaseEnd) / 6000.0, 0.25, 2.5);
        double[] w = new double[ManbearMode.values().length];
        w[ManbearMode.STALK.ordinal()] = spontaneous ? 0 : 3.0;
        w[ManbearMode.HIDE.ordinal()] = coverFailCd > 0 ? 0 : 1.0 + 2.2 * vig;
        w[ManbearMode.PEEK.ordinal()] = coverFailCd > 0 ? 0 : 1.0 + 1.6 * vig;
        w[ManbearMode.FAKE_CHASE.ordinal()] = ticksAlive > 600 ? 0.7 + 1.8 * vig * (1 - aggr) + 0.8 * panic : 0;
        w[ManbearMode.CHASE.ordinal()] = canChase ? (0.5 + 2.2 * panic + 1.2 * (1 - vig) + 1.2 * camp + 0.8 * sprint) * tension : 0;
        w[ManbearMode.LAY_TRAP.ordinal()] = (trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) ? 0.6 + 2.0 * aggr + 1.0 * sprint + 1.0 * camp : 0;
        w[ManbearMode.AMBUSH.ordinal()] = 0;
        w[ManbearMode.RETREAT.ordinal()] = 0;
        w[mode.ordinal()] = 0;
        double total = 0;
        for (double d : w) total += d;
        if (total <= 0) return ManbearMode.STALK;
        double pick = rnd().nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            pick -= w[i];
            if (pick <= 0) return ManbearMode.byId(i);
        }
        return ManbearMode.STALK;
    }

    /** once a second: random, unpredictable switches */
    private void spontaneous(ServerLevel level) {
        if (m.leaving || ticksAlive % 20 != 0) return;
        boolean calm = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || mode == ManbearMode.LAY_TRAP;
        if (!calm) return;
        boolean canChase = ticksAlive >= ManbearConfig.GRACE_SECONDS.get() * 20;

        // you wandered too close while he was lurking: pounce
        if (dist < 8.5 && modeTime > 60 && canChase && rnd().nextFloat() < 0.22F) {
            setMode(level, ManbearMode.CHASE);
            return;
        }
        // stared at for too long: duck out of sight
        if (mode == ManbearMode.STALK && lookedTicks > 120 && vig > 0.45F && coverFailCd <= 0) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        float p = 0.02F + 0.035F * (1F - vig);
        if (rnd().nextFloat() < p) setMode(level, pickNextMode(true));
    }

    // ================================================================= events
    public void onHurt(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp)) return;
        ManbearData data = ManbearData.get(level.getServer());
        data.profile(sp.getUUID()).onHitManbear();
        data.setDirty();
        target = sp;
        targetLock = 400;
        if (m.leaving) return;
        if (m.getHealth() < m.getMaxHealth() * 0.3F) { beginLeaving(level); return; }
        if (!mode.isChaseLike()) {
            float r = rnd().nextFloat();
            if (r < 0.55F) setMode(level, ManbearMode.CHASE);
            else if (r < 0.8F) setMode(level, ManbearMode.RETREAT);
            else if (trapCooldown <= 0) setMode(level, ManbearMode.LAY_TRAP);
        }
    }

    public void beginLeaving(ServerLevel level) {
        m.leaving = true;
        setMode(level, ManbearMode.RETREAT);
        modeDuration = 1_000_000;
    }

    /** a trap he laid has just been sprung */
    public void ambush(ServerPlayer p, BlockPos pos) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        target = p;
        targetLock = 800;
        ambushing = true;
        setMode(level, ManbearMode.AMBUSH);
        refreshTraits(level);
    }

    public void forceMode(ServerLevel level, ManbearMode mm) {
        ambushing = false;
        modeTime = 100;
        setMode(level, mm);
    }

    // ================================================================= movement helpers
    private boolean canFit(ServerLevel level, Stance s) {
        AABB box = s.dims.makeBoundingBox(m.position()).deflate(1.0E-7D);
        return level.noCollision(m, box);
    }

    private void updateStance(ServerLevel level) {
        if (ticksAlive % 5 != 0) return;
        Stance cur = m.getStance();
        Stance want;
        if (mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE) want = Stance.STAND;
        else want = dist < 36 ? Stance.CROUCH : Stance.STAND;
        if (stanceLock > 0 && want.ordinal() < cur.ordinal()) want = cur;
        while (want != Stance.CRAWL && !canFit(level, want)) want = Stance.byId(want.ordinal() + 1);
        if (want != cur) m.setStance(want);
    }

    /** Paths to the goal; if the current body is too big for the route, crouches or crawls to fit. */
    private boolean moveToSmart(ServerLevel level, double x, double y, double z, double speed) {
        PathNavigation nav = m.getNavigation();
        Path best = nav.createPath(x, y, z, 1);
        if (best != null && best.canReach()) {
            nav.moveTo(best, speed);
            return true;
        }
        Stance cur = m.getStance();
        for (Stance s : Stance.values()) {
            if (s.ordinal() <= cur.ordinal() || !canFit(level, s)) continue;
            m.setStance(s);
            Path p = nav.createPath(x, y, z, 1);
            if (p != null && p.canReach()) {
                nav.moveTo(p, speed);
                stanceLock = 60;
                return true;
            }
        }
        m.setStance(cur);
        if (best != null) nav.moveTo(best, speed);
        return false;
    }

    private void navTo(ServerLevel level, double x, double y, double z, double speed, int every) {
        if (pathCd > 0 && !m.getNavigation().isDone()) {
            pathCd--;
            m.getNavigation().setSpeedModifier(speed);
            return;
        }
        pathCd = every;
        navFail = !moveToSmart(level, x, y, z, speed);
        navFailCount = navFail ? navFailCount + 1 : 0;
    }

    private boolean reached(BlockPos p, double sqr) {
        return m.position().distanceToSqr(Vec3.atBottomCenterOf(p)) < sqr;
    }

    /** the player's current top speed as a speed modifier for us (1.0 = walk, 1.3 = sprint) */
    private double refSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double ps = target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!target.isSprinting()) ps *= 1.3D;
        return Mth.clamp(ps / base, 1.0D, 1.9D);
    }

    private void updateClimb() {
        boolean chasing = mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE;
        boolean want = m.horizontalCollision
                && ((chasing && (target.getY() > m.getY() + 0.8 || stuckTicks > 8)) || target.getY() > m.getY() + 2.5);
        m.setClimbing(want && lunge == Lunge.NONE);
    }

    // ================================================================= STALK
    private void tickStalk(ServerLevel level) {
        if (--orbitFlip <= 0) {
            orbitDir = -orbitDir;
            orbitFlip = 120 + rnd().nextInt(240);
        }
        // freeze like a statue while you stare at him
        if (looking && dist < 45) freezeTicks = Math.max(freezeTicks, 10);
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        Vec3 p = target.position();
        Vec3 me = m.position();
        Vec3 r = new Vec3(me.x - p.x, 0, me.z - p.z);
        double rl = r.length();
        if (rl < 0.01) { r = new Vec3(1, 0, 0); rl = 1; }
        Vec3 u = r.scale(1.0 / rl);
        Vec3 t = new Vec3(-u.z, 0, u.x).scale(orbitDir);
        double radial = Mth.clamp(stalkRadius - rl, -6, 6);
        Vec3 goal = p.add(u.scale(rl + radial)).add(t.scale(4.0));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 2);
        if (g == null) {
            orbitDir = -orbitDir;
            if (++navFailCount > 3 && coverFailCd <= 0) setMode(level, ManbearMode.HIDE);
            return;
        }
        boolean crouched = m.getStance() != Stance.STAND;
        double speed = crouched ? 0.62 : 0.85;
        if (!looking && rl > stalkRadius + 3) speed = 0.95; // closes in while you look away
        navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, speed, 15);
    }

    // ================================================================= HIDE
    private void tickHide(ServerLevel level) {
        if (cover == null) { setMode(level, ManbearMode.STALK); return; }
        if (!atHide) {
            if (reached(cover.hide(), 1.6)) {
                atHide = true;
                m.getNavigation().stop();
            } else {
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                navTo(level, h.x, h.y, h.z, looking ? 1.15 : 0.9, 10);
                if (navFailCount > 4) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
                return;
            }
        }
        m.getNavigation().stop();
        if (--waitTicks <= 0) {
            setMode(level, cover.peek() != null && rnd().nextFloat() < 0.65F ? ManbearMode.PEEK : ManbearMode.STALK);
        }
    }

    // ================================================================= PEEK
    private void tickPeek(ServerLevel level) {
        if (cover == null || cover.peek() == null) { setMode(level, ManbearMode.STALK); return; }
        Vec3 h = Vec3.atBottomCenterOf(cover.hide());
        Vec3 pk = Vec3.atBottomCenterOf(cover.peek());
        switch (peekPhase) {
            case 0 -> { // get behind the tree
                if (reached(cover.hide(), 1.6)) { peekPhase = 1; m.getNavigation().stop(); }
                else navTo(level, h.x, h.y, h.z, 0.95, 10);
            }
            case 1 -> { // lean out
                if (reached(cover.peek(), 1.4)) {
                    peekPhase = 2;
                    peekTimer = Math.max(16, (int) (45 + rnd().nextInt(50) - 35 * vig));
                    m.getNavigation().stop();
                } else navTo(level, pk.x, pk.y, pk.z, 0.6, 10);
            }
            case 2 -> { // stare
                m.getNavigation().stop();
                if (--peekTimer <= 0) peekPhase = 3;
            }
            case 3 -> { // slip back
                if (reached(cover.hide(), 1.6)) {
                    peeksLeft--;
                    peekPhase = 4;
                    peekTimer = 30 + rnd().nextInt(60);
                    m.getNavigation().stop();
                } else navTo(level, h.x, h.y, h.z, 1.0, 10);
            }
            default -> {
                if (--peekTimer <= 0) {
                    if (peeksLeft > 0) peekPhase = 1;
                    else setMode(level, rnd().nextFloat() < 0.5F ? ManbearMode.HIDE : ManbearMode.STALK);
                }
            }
        }
        if (navFailCount > 5) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
    }

    // ================================================================= FAKE CHASE
    private void tickFake(ServerLevel level) {
        if (fakePhase == 0) {
            navTo(level, target.getX(), target.getY(), target.getZ(), refSpeedMod() * 1.05, 8);
            if (dist <= fakeStop || modeTime > 110) {
                fakePhase = 1;
                fakeFreeze = 25 + rnd().nextInt(25);
                m.getNavigation().stop();
                if (rnd().nextFloat() < 0.4F) m.playBear(ModSounds.SNARL.get(), 3.0F, 0.8F);
            }
        } else {
            m.getNavigation().stop();
            if (--fakeFreeze <= 0 || dist < 3.5) {
                float r = rnd().nextFloat();
                boolean canChase = ticksAlive >= ManbearConfig.GRACE_SECONDS.get() * 20;
                if (canChase && r < 0.10F) setMode(level, ManbearMode.CHASE);   // the "fake" was real
                else if (r < 0.75F) setMode(level, ManbearMode.RETREAT);
                else setMode(level, ManbearMode.HIDE);
            }
        }
    }

    // ================================================================= CHASE
    private void tickChase(ServerLevel level) {
        chaseTicks++;
        if (ticksAlive % 20 == 0) samplePanic();

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if (m.distanceTo(target) < 3.9 && m.doHurtTarget(target)) hitsLanded++;
        }
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        if (lunge != Lunge.NONE) { tickLunge(level); return; }

        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }

        double ramp = Math.min(1.0, chaseTicks / (ManbearConfig.CHASE_RAMP_SECONDS.get() * 20.0));
        double bonus = ManbearConfig.CHASE_SPEED_BONUS_MAX.get() * ramp * (0.85 + 0.3 * sprint);
        double speed = refSpeedMod() * (1.0 + bonus);
        if (mode == ManbearMode.AMBUSH && chaseTicks < 100) speed *= 1.2;

        // melee
        if (dist < 3.0 && meleeCd == 0 && pendingMelee == 0) {
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 24;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }
        // lunge
        if (lungeCd == 0 && dist >= 3.2 && dist <= 7.5 && los && m.onGround()) {
            lunge = Lunge.WINDUP;
            lungeTimer = 7;
            lungeHit = false;
            m.getNavigation().stop();
            m.triggerAttack(rnd().nextInt(3));
            m.playBear(ModSounds.SNARL.get(), 3.5F, 0.75F + rnd().nextFloat() * 0.2F);
            return;
        }

        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 6);

        // stuck behind something: tear through it
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if (stuckTicks > 12 && dist > 2.4 && BlockBreaker.canGrief(level)) {
            BlockPos b = breaker.find(level, m, target);
            if (b != null) { breaker.start(level, b); unreachableTicks = 0; stuckTicks = 0; }
            else unreachableTicks += 2;
        }
        if (navFail) unreachableTicks++; else unreachableTicks = Math.max(0, unreachableTicks - 2);
        if (unreachableTicks > 160) {
            setMode(level, trapCooldown <= 0 ? ManbearMode.LAY_TRAP : ManbearMode.STALK);
            return;
        }

        if (hitsLanded >= (aggr > 0.5F ? 2 : 4) || dist > 85) endChase(level);
    }

    private void tickLunge(ServerLevel level) {
        lungeTimer--;
        switch (lunge) {
            case WINDUP -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    Vec3 d = target.position().subtract(m.position());
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    h = h.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : h.normalize();
                    m.impulse(new Vec3(h.x * 0.95, 0.38, h.z * 0.95));
                    lunge = Lunge.AIR;
                    lungeTimer = 16;
                }
            }
            case AIR -> {
                if (!lungeHit && m.getBoundingBox().inflate(0.8, 0.4, 0.8).intersects(target.getBoundingBox())) {
                    lungeHit = true;
                    if (m.doHurtTarget(target)) hitsLanded++;
                }
                if (lungeTimer <= 0 || (lungeTimer < 11 && m.onGround())) {
                    lunge = Lunge.RECOVER;
                    lungeTimer = 14;
                }
            }
            case RECOVER -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    lunge = Lunge.NONE;
                    lungeCd = ManbearConfig.LUNGE_COOLDOWN_TICKS.get() + rnd().nextInt(30) - (aggr > 0.5F ? 20 : 0);
                    if (lungeHit && (aggr > 0.5F || rnd().nextFloat() < 0.2F)) endChase(level);
                }
            }
            default -> { }
        }
    }

    private void samplePanic() {
        if (prof == null) return;
        Vec3 away = target.position().subtract(m.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) return;
        away = away.normalize();
        boolean fleeing = prof.velocity().dot(away) > 0.5;
        if (fleeing) prof.panic = PlayerProfile.ema(prof.panic, 1F, 0.08F);
        else if (dist < 10) prof.panic = PlayerProfile.ema(prof.panic, 0F, 0.08F);
    }

    private void endChase(ServerLevel level) {
        lastChaseEnd = ticksAlive;
        float r = rnd().nextFloat();
        setMode(level, r < 0.6F ? ManbearMode.RETREAT : r < 0.85F ? ManbearMode.HIDE : ManbearMode.STALK);
    }

    // ================================================================= LAY TRAP
    private void tickTrap(ServerLevel level) {
        if (trapSite == null) { setMode(level, ManbearMode.STALK); return; }
        if (trapPhase == 0) {
            Vec3 s = Vec3.atBottomCenterOf(trapSite);
            navTo(level, s.x, s.y, s.z, 0.8, 15);
            if (dist < 11 && los) { // caught red-handed
                boolean canChase = ticksAlive >= ManbearConfig.GRACE_SECONDS.get() * 20;
                setMode(level, canChase && rnd().nextFloat() < 0.5F ? ManbearMode.CHASE : ManbearMode.RETREAT);
                return;
            }
            if (reached(trapSite, 5.0)) {
                trapPhase = 1;
                trapTimer = 35;
                m.getNavigation().stop();
                m.triggerAttack(2);
            }
        } else {
            m.getNavigation().stop();
            if (--trapTimer <= 0) {
                placeTrap(level);
                trapCooldown = 900 + rnd().nextInt(1800);
                setMode(level, rnd().nextFloat() < 0.6F ? ManbearMode.HIDE : ManbearMode.STALK);
            }
        }
    }

    private void placeTrap(ServerLevel level) {
        BlockState st = level.getBlockState(trapSite);
        if (!(st.isAir() || st.canBeReplaced())) return;
        BlockState trap = ModBlocks.BEAR_TRAP.get().defaultBlockState()
                .setValue(com.manbear.block.BearTrapBlock.HUNTER_SET, true);
        if (!trap.canSurvive(level, trapSite)) return;
        level.setBlock(trapSite, trap, 3);
        m.traps.add(trapSite.asLong());
        int max = ManbearConfig.MAX_TRAPS.get();
        while (m.traps.size() > max) {
            BlockPos old = BlockPos.of(m.traps.remove(0));
            if (level.getBlockState(old).is(ModBlocks.BEAR_TRAP.get())) level.removeBlock(old, false);
        }
    }

    // ================================================================= RETREAT
    private void tickRetreat(ServerLevel level) {
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 goal = me.add(away.scale(14));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, m.getBlockY(), 2);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, m.leaving ? 1.1 : 0.9, 15);
        else m.getNavigation().stop();

        if (m.leaving) {
            if (!los && dist > 38) m.discard();
            return;
        }
        if ((!los && dist > 18) || dist > 34) {
            setMode(level, rnd().nextFloat() < 0.55F && coverFailCd <= 0 ? ManbearMode.HIDE : ManbearMode.STALK);
        }
    }

    // ================================================================= ambience
    private void ambient() {
        if (ambientCd > 0) return;
        RandomSource r = rnd();
        ambientCd = 300 + r.nextInt(600);
        switch (mode) {
            case STALK -> {
                if (r.nextFloat() < 0.7F) m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F + r.nextFloat() * 0.3F);
                else m.playBear(ModSounds.HUFF.get(), 1.6F, 0.9F);
                if (r.nextFloat() < 0.05F) m.playBear(ModSounds.ROAR.get(), 5.5F, 0.8F);
            }
            case HIDE, PEEK -> { if (dist < 22) m.playBear(ModSounds.HUFF.get(), 1.1F, 0.9F + r.nextFloat() * 0.2F); }
            case CHASE, AMBUSH -> m.playBear(ModSounds.GROWL.get(), 2.5F, 0.9F);
            case RETREAT -> m.playBear(ModSounds.GROWL.get(), 1.5F, 0.7F);
            default -> { }
        }
    }

}
