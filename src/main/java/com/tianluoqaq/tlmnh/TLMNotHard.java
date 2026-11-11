package com.tianluoqaq.tlmnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.tartaricacid.touhoulittlemaid.datapack.KaomojiData;
import com.github.tartaricacid.touhoulittlemaid.event.EntityHurtEvent;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.MaidCreeperAvoidance;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatProjectileEvents;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldEvents;
import com.tianluoqaq.tlmnh.common.command.DevelopmentCommand;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.MaidBowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidEpisodeRecorder;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.progression.MaidProgressionRecipes;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.ProtectionCommand;
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.protection.RecoveryParcel;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;
import com.tianluoqaq.tlmnh.common.registry.EventBuses;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;
import com.tianluoqaq.tlmnh.common.registry.ModEntities;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLMissingMappingsEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

/** 模组主入口，统一串联双端共享生命周期，不在此直接触碰客户端渲染类 */
@Mod(
    modid = TLMNotHard.MOD_ID,
    name = TLMNotHard.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:" + KnownModIds.BATTLEGEAR2
        + ";after:"
        + KnownModIds.APPLE_CORE
        + ";after:"
        + KnownModIds.GREGTECH
        + ";after:"
        + KnownModIds.DREAMCRAFT)
public final class TLMNotHard {

    public static final String MOD_ID = "tlmnh";
    public static final String MOD_NAME = "Touhou Little Maid (Not Hard)";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.Instance(MOD_ID)
    public static TLMNotHard INSTANCE;

    @SidedProxy(
        clientSide = "com.tianluoqaq.tlmnh.client.proxy.ClientProxy",
        serverSide = "com.tianluoqaq.tlmnh.common.proxy.CommonProxy")
    public static CommonProxy PROXY;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        var side = event.getSide();
        LOG.info("Initializing {} {} on {}", MOD_NAME, Tags.VERSION, side);
        BuiltinModelCatalog.load();
        MaidAcquisitionConfig.load(event.getSuggestedConfigurationFile());
        KaomojiData.load();
        ModEntities.register(this);
        MaidLifeContent.register();
        RecoveryParcel.register();
        ProtectionEvents.register();
        MaidResurrectionContent.register();
        AnimationLab.register();
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
        LOG.info("TLMNH_BOOTSTRAP_READY side={} version={} bytecodeTarget=8", event.getSide(), Tags.VERSION);
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
        if (DevelopmentFlags.isDevCommandsEnabled()) {
            AnimationLab.validateAutoEntry();
            MaidShieldRuntimeProbe.registerForDevelopment();
            event.registerServerCommand(new DevelopmentCommand());
        }
    }

    @Mod.EventHandler
    public void missingMappings(FMLMissingMappingsEvent event) {
        for (var mapping : event.getAll()) {
            if (mapping.type == GameRegistry.Type.ITEM) ProtectedItemCodec.missing(mapping.id, mapping.name);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        Throwable failure = null;
        for (Runnable close : new Runnable[] { MaidCoreInteractionProbe::cancelPanicRuntime,
            MaidBowRuntimeProbe::cancel, MaidLifeRuntimeProbe::cancel, MaidShieldRuntimeProbe::cancel,
            MaidEpisodeRecorder::closeAll }) {
            try {
                close.run();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
