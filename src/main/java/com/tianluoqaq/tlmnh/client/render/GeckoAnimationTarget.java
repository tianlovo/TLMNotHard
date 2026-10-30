package com.tianluoqaq.tlmnh.client.render;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.manager.AnimationData;

/** 原动画控制器需要的最小实例接口，不将 MC 实体或世界引入控制器 */
public interface GeckoAnimationTarget {

    Animation getAnimation(String name);

    AnimationData getAnimationData();
}
