package com.tianluoqaq.tlmnh.common.network;

import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;

/** 客户端入站网络消息派发入口，由主入口与代理初始化阶段绑定端侧实现，切断网络层对 TLMNotHard 的反向依赖 */
public final class MaidClientPackets {

    /** 客户端入站消息接收契约，服务端默认空操作，不加载客户端渲染或音频类型 */
    public interface Sink {

        default void enqueueMaidSound(PlayMaidSoundMessage message) {}

        default void enqueueMaidEffects(SendEffectMessage message) {}

        default void enqueueMaidAttackSnapshot(MaidAttackSnapshot message) {}
    }

    private static final Sink NOOP = new Sink() {};
    private static volatile Sink activeSink = NOOP;

    private MaidClientPackets() {}

    public static void bind(Sink sink) {
        activeSink = sink != null ? sink : NOOP;
    }

    public static Sink current() {
        return activeSink;
    }

    public static void enqueueMaidSound(PlayMaidSoundMessage message) {
        activeSink.enqueueMaidSound(message);
    }

    public static void enqueueMaidEffects(SendEffectMessage message) {
        activeSink.enqueueMaidEffects(message);
    }

    public static void enqueueMaidAttackSnapshot(MaidAttackSnapshot message) {
        activeSink.enqueueMaidAttackSnapshot(message);
    }
}
