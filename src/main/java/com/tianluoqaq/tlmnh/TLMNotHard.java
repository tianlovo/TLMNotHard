package com.tianluoqaq.tlmnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldEvents;
import com.tianluoqaq.tlmnh.common.command.DevelopmentCommand;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.MaidBowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidEpisodeRecorder;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;
import com.tianluoqaq.tlmnh.common.registry.ModEntities;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;

/**
 * TLMNotHard 的公共启动入口，禁止在这里加载客户端渲染类型
 */
@Mod(
    modid = TLMNotHard.MOD_ID,
    name = TLMNotHard.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:battlegear2;after:AppleCore;after:gregtech;after:dreamcraft")
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

    /**
     * 记录实际两端环境，后续注册和配置仍由各自的职责模块负责
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        var side = event.getSide();
        LOG.info("Initializing {} {} on {}", MOD_NAME, Tags.VERSION, side);
        BuiltinModelCatalog.load();
        MaidAcquisitionConfig.load(event.getSuggestedConfigurationFile());
        com.github.tartaricacid.touhoulittlemaid.datapack.KaomojiData.load();
        ModEntities.register(this);
        MaidLifeContent.register();
        com.tianluoqaq.tlmnh.common.protection.RecoveryParcel.register();
        com.tianluoqaq.tlmnh.common.protection.ProtectionEvents.register();
        MaidResurrectionContent.register();
        AnimationLab.register();
        MaidBrainLifecycle.register();
        MaidShieldEvents.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .register(new com.github.tartaricacid.touhoulittlemaid.event.EntityHurtEvent());
    }

    /**
     * 提供与构建版本绑定的加载证据，不将空入口记录成玩法完成
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        NetworkRegistry.INSTANCE.registerGuiHandler(this, PROXY);
        PROXY.init();
        MaidResurrectionContent.registerSpawns();
        LOG.info("TLMNH_BOOTSTRAP_READY side={} version={} bytecodeTarget=8", event.getSide(), Tags.VERSION);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        com.tianluoqaq.tlmnh.common.progression.MaidProgressionRecipes.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new com.tianluoqaq.tlmnh.common.protection.ProtectionCommand());
        if (Boolean.getBoolean("tlmnh.devCommands")) {
            AnimationLab.validateAutoEntry();
            MaidShieldRuntimeProbe.registerForDevelopment();
            event.registerServerCommand(new DevelopmentCommand());
        }
    }

    @Mod.EventHandler
    public void missingMappings(cpw.mods.fml.common.event.FMLMissingMappingsEvent event) {
        for (var mapping : event.getAll()) if (mapping.type == cpw.mods.fml.common.registry.GameRegistry.Type.ITEM)
            com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.missing(mapping.id, mapping.name);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        Throwable failure = null;
        for (Runnable close : new Runnable[] {
            com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe::cancelPanicRuntime,
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
