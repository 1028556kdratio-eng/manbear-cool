package com.manbear.client;

import com.manbear.entity.ManbearEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ManbearRenderer extends GeoEntityRenderer<ManbearEntity> {
    /** The model is ~4.5 blocks tall at 1.0; 0.7 makes him ~3 blocks to match his 2.9-block hitbox. Tweak freely. */
    public static final float MODEL_SCALE = 0.7F;

    public ManbearRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new ManbearModel());
        this.shadowRadius = 0.7F;
        withScale(MODEL_SCALE);
        addRenderLayer(new ManbearEyesLayer(this));
    }
}
