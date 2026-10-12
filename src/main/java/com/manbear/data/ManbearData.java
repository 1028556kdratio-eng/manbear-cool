package com.manbear.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** World-wide state: which Manbear is the one-and-only, cool-downs and every player's profile. */
public class ManbearData extends SavedData {
    private static final String NAME = "manbear_data";
    /** if the active Manbear has not "beaten its heart" for this long, he is considered gone */
    private static final long STALE_TICKS = 6000L;

    private UUID activeId;
    private long lastBeat;
    private long cooldownUntil;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    public static ManbearData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ManbearData::load, ManbearData::new, NAME);
    }

    public UUID activeId() { return activeId; }

    public boolean isActive(long now) {
        return activeId != null && now - lastBeat < STALE_TICKS;
    }

    /** Returns true if this Manbear is (or just became) the active one. */
    public boolean claim(UUID id, long now) {
        if (activeId == null || activeId.equals(id) || now - lastBeat >= STALE_TICKS) {
            activeId = id;
            lastBeat = now;
            setDirty();
            return true;
        }
        return false;
    }

    public void release(UUID id, long cooldownTicks, long now) {
        if (id.equals(activeId)) {
            activeId = null;
            cooldownUntil = Math.max(cooldownUntil, now + cooldownTicks);
            setDirty();
        }
    }

    public boolean onCooldown(long now) { return now < cooldownUntil; }

    public void clearAll() {
        activeId = null; cooldownUntil = 0; setDirty();
    }

    public PlayerProfile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new PlayerProfile());
    }

    public void resetProfiles() { profiles.clear(); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (activeId != null) tag.putUUID("active", activeId);
        tag.putLong("beat", lastBeat);
        tag.putLong("cooldown", cooldownUntil);
        CompoundTag pt = new CompoundTag();
        for (Map.Entry<UUID, PlayerProfile> e : profiles.entrySet()) {
            pt.put(e.getKey().toString(), e.getValue().save());
        }
        tag.put("profiles", pt);
        return tag;
    }

    public static ManbearData load(CompoundTag tag) {
        ManbearData d = new ManbearData();
        if (tag.hasUUID("active")) d.activeId = tag.getUUID("active");
        d.lastBeat = tag.getLong("beat");
        d.cooldownUntil = tag.getLong("cooldown");
        CompoundTag pt = tag.getCompound("profiles");
        for (String key : pt.getAllKeys()) {
            try {
                d.profiles.put(UUID.fromString(key), PlayerProfile.load(pt.getCompound(key)));
            } catch (IllegalArgumentException ignored) { }
        }
        return d;
    }
}
