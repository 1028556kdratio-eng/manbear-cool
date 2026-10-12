package com.manbear;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ManbearConfig {
    public static final ForgeConfigSpec SPEC;

    // spawning
    public static final ForgeConfigSpec.IntValue SPAWN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue SPAWN_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    public static final ForgeConfigSpec.IntValue SPAWN_MIN_DIST;
    public static final ForgeConfigSpec.IntValue SPAWN_MAX_DIST;
    public static final ForgeConfigSpec.IntValue LIFETIME_MINUTES;
    public static final ForgeConfigSpec.IntValue RESPAWN_COOLDOWN_MINUTES;
    // behaviour
    public static final ForgeConfigSpec.IntValue GRACE_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CHASE_SPEED_BONUS_MAX;
    public static final ForgeConfigSpec.IntValue CHASE_RAMP_SECONDS;
    public static final ForgeConfigSpec.DoubleValue DAMAGE;
    public static final ForgeConfigSpec.IntValue LUNGE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue ADAPTATION;
    // blocks / traps
    public static final ForgeConfigSpec.BooleanValue BREAK_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue MAX_BREAK_HARDNESS;
    public static final ForgeConfigSpec.BooleanValue DROP_BROKEN_BLOCKS;
    public static final ForgeConfigSpec.IntValue MAX_TRAPS;
    public static final ForgeConfigSpec.DoubleValue TRAP_DAMAGE;
    public static final ForgeConfigSpec.IntValue TRAP_ROOT_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("spawning");
        SPAWN_INTERVAL_TICKS = b.comment("Ticks between spawn rolls (1200 = 1 minute).").defineInRange("spawnIntervalTicks", 1200, 20, 1_000_000);
        SPAWN_CHANCE_DENOMINATOR = b.comment("Each roll succeeds with chance 1/N. Default 25 = 1/25 per minute.").defineInRange("spawnChanceDenominator", 25, 1, 100_000);
        DIMENSIONS = b.comment("Dimensions the Manbear may spawn in.").defineList("dimensions", List.of("minecraft:overworld"), o -> o instanceof String);
        SPAWN_MIN_DIST = b.defineInRange("spawnMinDistance", 30, 16, 128);
        SPAWN_MAX_DIST = b.defineInRange("spawnMaxDistance", 46, 20, 160);
        LIFETIME_MINUTES = b.comment("After this long he loses interest and leaves (a new roll can then happen).").defineInRange("lifetimeMinutes", 25, 1, 600);
        RESPAWN_COOLDOWN_MINUTES = b.comment("Cool-down after he is killed or leaves before he can spawn again.").defineInRange("respawnCooldownMinutes", 8, 0, 6000);
        b.pop();

        b.push("behaviour");
        GRACE_SECONDS = b.comment("Seconds after spawning before he may start a REAL chase on his own.").defineInRange("graceSeconds", 75, 0, 3600);
        CHASE_SPEED_BONUS_MAX = b.comment("Extra speed (fraction) he gains over the player as a chase goes on. 0.12 = 12% faster.").defineInRange("chaseSpeedBonusMax", 0.12, 0.0, 1.0);
        CHASE_RAMP_SECONDS = b.defineInRange("chaseRampSeconds", 45, 5, 600);
        DAMAGE = b.comment("Base attack damage (hearts x2).").defineInRange("attackDamage", 6.0, 0.5, 100.0);
        LUNGE_COOLDOWN_TICKS = b.defineInRange("lungeCooldownTicks", 70, 20, 1200);
        ADAPTATION = b.comment("Learn from each player's habits and adapt.").define("adaptation", true);
        b.pop();

        b.push("blocks_and_traps");
        BREAK_BLOCKS = b.comment("Allow him to break blocks to reach you (also needs the mobGriefing gamerule).").define("breakBlocks", true);
        MAX_BREAK_HARDNESS = b.comment("Blocks harder than this are safe (obsidian = 50).").defineInRange("maxBreakHardness", 30.0, 0.0, 2000.0);
        DROP_BROKEN_BLOCKS = b.define("dropBrokenBlocks", true);
        MAX_TRAPS = b.defineInRange("maxTraps", 8, 0, 64);
        TRAP_DAMAGE = b.defineInRange("trapDamage", 4.0, 0.0, 100.0);
        TRAP_ROOT_TICKS = b.comment("How long a sprung trap holds you in place.").defineInRange("trapRootTicks", 100, 10, 2400);
        b.pop();

        SPEC = b.build();
    }
}
