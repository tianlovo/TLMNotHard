package com.tianluoqaq.tlmnh.api.behavior;

import java.util.UUID;

/** 服务端女仆增强行为会话初始化摘要，不暴露底层实体或世界可变句柄 */
public interface MaidBehaviorInitContext {

    UUID maidId();

    int dimensionId();
}
