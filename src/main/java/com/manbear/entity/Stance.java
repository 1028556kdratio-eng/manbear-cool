package com.manbear.entity;

import net.minecraft.world.entity.EntityDimensions;

public enum Stance {
    STAND(EntityDimensions.scalable(0.95F, 2.9F)),
    CROUCH(EntityDimensions.scalable(0.95F, 1.9F)),
    CRAWL(EntityDimensions.scalable(0.9F, 0.9F));

    public final EntityDimensions dims;

    Stance(EntityDimensions dims) { this.dims = dims; }

    public static Stance byId(int id) {
        Stance[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
