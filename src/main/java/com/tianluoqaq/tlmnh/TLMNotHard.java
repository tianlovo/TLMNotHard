package com.tianluoqaq.tlmnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * TLMNotHard 的公共启动入口，禁止在这里加载客户端渲染类型
 */
@Mod(
    modid = TLMNotHard.MOD_ID,
    name = TLMNotHard.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]")
public final class TLMNotHard {

    public static final String MOD_ID = "tlmnh";
    public static final String MOD_NAME = "Touhou Little Maid (Not Hard)";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    /**
     * 记录实际两端环境，后续注册和配置仍由各自的职责模块负责
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        var side = event.getSide();
        LOG.info("Initializing {} {} on {}", MOD_NAME, Tags.VERSION, side);
    }

    /**
     * 提供与构建版本绑定的加载证据，不将空入口记录成玩法完成
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("TLMNH_BOOTSTRAP_READY side={} version={} bytecodeTarget=8", event.getSide(), Tags.VERSION);
    }
}
