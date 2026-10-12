package com.manbear.registry;

import com.manbear.ManbearMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ManbearMod.MODID);

    public static final RegistryObject<SoundEvent> GROWL = reg("growl");
    public static final RegistryObject<SoundEvent> HUFF = reg("huff");
    public static final RegistryObject<SoundEvent> ROAR = reg("roar");
    public static final RegistryObject<SoundEvent> SNARL = reg("snarl");
    public static final RegistryObject<SoundEvent> HURT = reg("hurt");
    public static final RegistryObject<SoundEvent> DEATH = reg("death");
    public static final RegistryObject<SoundEvent> STEP = reg("step");

    private static RegistryObject<SoundEvent> reg(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ManbearMod.MODID, name)));
    }
}
