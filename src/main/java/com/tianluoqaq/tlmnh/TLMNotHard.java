package com.tianluoqaq.tlmnh;

import org.apache.logging.log4j.Logger;

import com.github.tartaricacid.touhoulittlemaid.datapack.KaomojiData;
import com.github.tartaricacid.touhoulittlemaid.event.EntityHurtEvent;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.combat.MaidCreeperAvoidance;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatProjectileEvents;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldEvents;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.foundation.ModIds;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.network.MaidClientPackets;
import com.tianluoqaq.tlmnh.common.progression.MaidProgressionRecipes;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryParcel;
import com.tianluoqaq.tlmnh.common.protection.runtime.ProtectionCommand;
import com.tianluoqaq.tlmnh.common.protection.runtime.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;
import com.tianluoqaq.tlmnh.common.registry.EventBuses;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;
import com.tianluoqaq.tlmnh.common.registry.ModEntities;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLMissingMappingsEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

/** 模组主入口，统一串联双端共享生命周期，不在此直接触碰客户端渲染类 */
@Mod(
    modid = TLMNotHard.MOD_ID,
    name = TLMNotHard.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = ModIds.ACCEPTED_MC_VERSIONS,
    dependencies = ModIds.MOD_DEPENDENCIES)
public final class TLMNotHard {

    public static final String MOD_ID = ModIds.MOD_ID;
    public static final String MOD_NAME = ModIds.MOD_NAME;
    public static final Logger LOG = ModContext.LOG;

    @Mod.Instance(MOD_ID)
    public static TLMNotHard INSTANCE;

    @SidedProxy(clientSide = ModIds.CLIENT_PROXY_CLASS, serverSide = ModIds.SERVER_PROXY_CLASS)
    public static CommonProxy PROXY;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModContext.bindModInstance(this);
        MaidClientPackets.bind(PROXY);
        CompatServices.preInitAndFreeze();
        var side = event.getSide();
        LOG.info(ModBootstrapDiagnostics.MSG_INITIALIZING_ON, MOD_NAME, Tags.VERSION, side);
        BuiltinModelCatalog.load();
        MaidAcquisitionConfig.load(event.getSuggestedConfigurationFile());
        KaomojiData.load();
        ModEntities.register(this);
        MaidLifeContent.register();
        MaidWorkItems.register(CompatServices::cropsNhBreeding);
        RecoveryParcel.register();
        ProtectionEvents.register();
        MaidResurrectionContent.register();
        MaidBrainLifecycle.register();
        CombatProjectileEvents.register();
        MaidShieldEvents.register();
        MaidCreeperAvoidance.register();
        EventBuses.registerForge(new EntityHurtEvent());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        NetworkRegistry.INSTANCE.registerGuiHandler(this, PROXY);
        PROXY.init();
        MaidResurrectionContent.registerSpawns();
        LOG.info(ModBootstrapDiagnostics.MSG_TLMNH_BOOTSTRAP_READY_SIDE_VERSION, event.getSide(), Tags.VERSION);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        MaidProgressionRecipes.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // 开服阶段顺手预热 GT 依赖指纹，省得第一场战斗在实体 tick 里临时读整包 JAR
        DependencyFingerprint.mod(KnownModIds.GREGTECH);
        event.registerServerCommand(new ProtectionCommand());
    }

    @Mod.EventHandler
    public void missingMappings(FMLMissingMappingsEvent event) {
        for (var mapping : event.getAll()) {
            if (mapping.type == GameRegistry.Type.ITEM) ProtectedItemCodec.missing(mapping.id, mapping.name);
        }
    }

}
