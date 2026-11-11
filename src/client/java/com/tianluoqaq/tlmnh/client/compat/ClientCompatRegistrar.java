package com.tianluoqaq.tlmnh.client.compat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端专属适配引导口；由 compatClient 源集在客户端装配渲染平台与模型物品适配 */
@SideOnly(Side.CLIENT)
public interface ClientCompatRegistrar {

    void register(ClientCompatServices.Sink sink);
}
