package com.tianluoqaq.tlmnh.qa;

import cpw.mods.fml.common.Mod;

@Mod(
    modid = "tlmnhqapilot",
    name = "TLMNH Horizon-QA Pilot",
    version = "1",
    dependencies = "required-after:tlmnh;required-after:horizonqa",
    acceptableRemoteVersions = "*")
public final class PilotMod {

    public static final ProtectionCanary PROTECTION_CANARY = new ProtectionCanary();

    @Mod.EventHandler
    public void preInit(cpw.mods.fml.common.event.FMLPreInitializationEvent event) {
        cpw.mods.fml.common.registry.GameRegistry.registerItem(PROTECTION_CANARY, "protection_canary");
        EffectEmptyWorldFixture.register();
        if (Boolean.getBoolean("tlmnh.qaLifecycleFreeze")) net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .register(new LifecycleUpgradeTests.PersistenceEnvironment());
    }

    @Mod.EventHandler
    public void aboutToStart(cpw.mods.fml.common.event.FMLServerAboutToStartEvent event) {
        EffectEmptyWorldFixture.aboutToStart();
    }

    @Mod.EventHandler
    public void stopping(cpw.mods.fml.common.event.FMLServerStoppingEvent event) {
        EffectEmptyWorldFixture.stopped();
    }
}
