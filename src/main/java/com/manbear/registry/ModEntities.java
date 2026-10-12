package com.manbear.registry;

import com.manbear.ManbearMod;
import com.manbear.entity.ManbearEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ManbearMod.MODID);

    public static final RegistryObject<EntityType<ManbearEntity>> MANBEAR = ENTITIES.register("manbear",
            () -> EntityType.Builder.of(ManbearEntity::new, MobCategory.MONSTER)
                    .sized(0.95F, 2.9F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build("manbear"));
}
