package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.command.ManbearCommand;
import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.Stance;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ServerEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        if (now % 10 == 0) sampleProfiles(server, now);
        if (now > 0 && now % ManbearConfig.SPAWN_INTERVAL_TICKS.get() == 0) spawnRoll(server, now);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        ManbearCommand.register(e.getDispatcher());
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getSource().getEntity() instanceof ManbearEntity m) {
            m.onKilledPlayer(p);
        }
    }

    // ------------------------------------------------------------------ profiles
    private void sampleProfiles(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        Entity bear = findActive(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            data.profile(p.getUUID()).sample(p, now, bear);
        }
        if (now % 600 == 0) data.setDirty();
    }

    public static ManbearEntity findActive(MinecraftServer server) {
        UUID id = ManbearData.get(server).activeId();
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ spawning (1/25 per minute, only one ever)
    private void spawnRoll(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        if (data.isActive(now) || data.onCooldown(now)) return;
        if (server.overworld().getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource rnd = server.overworld().random;
        if (rnd.nextInt(ManbearConfig.SPAWN_CHANCE_DENOMINATOR.get()) != 0) return;

        List<? extends String> dims = ManbearConfig.DIMENSIONS.get();
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (!dims.contains(p.level().dimension().location().toString())) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return;
        spawnNear(server, candidates.get(rnd.nextInt(candidates.size())));
    }

    /** Spawns him out of sight, 30-46 blocks from the player. Returns null if no good spot was found. */
    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        RandomSource rnd = level.random;
        int min = ManbearConfig.SPAWN_MIN_DIST.get();
        int max = Math.max(min + 1, ManbearConfig.SPAWN_MAX_DIST.get());
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;

            BlockPos g;
            if (level.canSeeSky(p.blockPosition())) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                g = new BlockPos(x, y, z);
                if (!CoverFinder.standable(level, g, 3)) continue;
            } else {
                g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), 3);
                if (g == null) continue;
            }
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;

            ManbearEntity e = ModEntities.MANBEAR.get().create(level);
            if (e == null) return null;
            e.moveTo(v.x, v.y, v.z, rnd.nextFloat() * 360F, 0F);
            level.addFreshEntity(e);
            ManbearData.get(server).claim(e.getUUID(), server.overworld().getGameTime());
            return e;
        }
        return null;
    }

    private static boolean seenByAnyPlayer(ServerLevel level, Vec3 v) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(v) < 60 * 60 && CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 1.5, 0), p)) return true;
        }
        return false;
    }
}
