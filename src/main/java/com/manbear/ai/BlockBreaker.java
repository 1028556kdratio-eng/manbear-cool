package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.entity.ManbearEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Lets the Manbear tear through whatever stands between him and his target. */
public class BlockBreaker {
    private BlockPos pos;
    private int progress;
    private int needed;

    public boolean active() { return pos != null; }

    public static boolean canGrief(ServerLevel level) {
        return ManbearConfig.BREAK_BLOCKS.get() && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    public static boolean isBreakable(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        if (st.getCollisionShape(level, p).isEmpty()) return false;
        if (st.is(BlockTags.WITHER_IMMUNE)) return false;
        float h = st.getDestroySpeed(level, p);
        return h >= 0 && h <= ManbearConfig.MAX_BREAK_HARDNESS.get();
    }

    @Nullable
    public BlockPos find(ServerLevel level, ManbearEntity m, Entity target) {
        Vec3 to = target.position().subtract(m.position());
        Direction h = Direction.getNearest(to.x, 0, to.z);
        BlockPos base = m.blockPosition();
        int hgt = Math.max(1, Mth.ceil(m.getBbHeight()));
        List<BlockPos> c = new ArrayList<>();
        for (int dy = 0; dy < hgt; dy++) c.add(base.relative(h).above(dy));
        if (to.y > 1.5) {
            c.add(base.above(hgt));
            c.add(base.relative(h).above(hgt));
        }
        if (to.y < -1.5) {
            c.add(base.below());
            c.add(base.relative(h).below());
        }
        for (int dy = 0; dy < hgt; dy++) c.add(base.relative(h, 2).above(dy));
        for (BlockPos p : c) {
            if (isBreakable(level, p)) return p;
        }
        return null;
    }

    public void start(ServerLevel level, BlockPos p) {
        this.pos = p;
        this.progress = 0;
        float hardness = level.getBlockState(p).getDestroySpeed(level, p);
        this.needed = Mth.clamp((int) (hardness * 9), 8, 140);
    }

    /** @return true while still breaking */
    public boolean tick(ServerLevel level, ManbearEntity m) {
        if (pos == null) return false;
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            cancel(level, m);
            return false;
        }
        progress++;
        level.destroyBlockProgress(m.getId(), pos, Math.min(9, progress * 10 / needed));
        if (progress % 4 == 0) {
            level.playSound(null, pos, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.2F, 0.7F);
        }
        if (progress % 12 == 1) m.triggerAttack(2);
        if (progress >= needed) {
            level.destroyBlockProgress(m.getId(), pos, -1);
            level.destroyBlock(pos, ManbearConfig.DROP_BROKEN_BLOCKS.get(), m);
            pos = null;
            return false;
        }
        return true;
    }

    public void cancel(ServerLevel level, ManbearEntity m) {
        if (pos != null) level.destroyBlockProgress(m.getId(), pos, -1);
        pos = null;
        progress = 0;
    }
}
