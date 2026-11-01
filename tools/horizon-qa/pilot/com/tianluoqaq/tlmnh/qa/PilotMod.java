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
        if (Boolean.getBoolean("tlmnh.qaLifecycleFreeze")) net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .register(new LifecycleUpgradeTests.PersistenceEnvironment());
    }
}
