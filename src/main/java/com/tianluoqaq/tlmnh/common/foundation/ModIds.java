package com.tianluoqaq.tlmnh.common.foundation;

/** 模组核心标识与加载期依赖顺序定义 */
public final class ModIds {

    public static final String MOD_ID = "tlmnh";
    public static final String MOD_NAME = "Touhou Little Maid (Not Hard)";
    public static final String UPSTREAM_MOD_ID = "touhou_little_maid";
    public static final String ACCEPTED_MC_VERSIONS = "[1.7.10]";
    public static final String CLIENT_PROXY_CLASS = "com.tianluoqaq.tlmnh.client.proxy.ClientProxy";
    public static final String SERVER_PROXY_CLASS = "com.tianluoqaq.tlmnh.common.proxy.CommonProxy";
    public static final String MOD_DEPENDENCIES = "after:" + KnownModIds.BATTLEGEAR2
        + ";after:"
        + KnownModIds.APPLE_CORE
        + ";after:"
        + KnownModIds.GREGTECH
        + ";after:"
        + KnownModIds.DREAMCRAFT;

    private ModIds() {}
}
