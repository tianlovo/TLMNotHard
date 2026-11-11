package com.tianluoqaq.tlmnh.common.compat;

/** 双端共享适配引导口；由 compat 源集通过 ServiceLoader 向业务层装配窄能力实现 */
public interface CompatRegistrar {

    void register(CompatServices.Sink sink);
}
