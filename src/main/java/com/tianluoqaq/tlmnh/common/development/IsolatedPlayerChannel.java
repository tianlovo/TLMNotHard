package com.tianluoqaq.tlmnh.common.development;

import net.minecraft.network.NetworkManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import io.netty.channel.embedded.EmbeddedChannel;

/** 校验内存玩家自身通道；Hodgepodge的全局fallback不是此通道绑定的dispatcher */
public final class IsolatedPlayerChannel {

    private IsolatedPlayerChannel() {}

    public static void verify(NetworkManager manager, EmbeddedChannel channel) {
        require(
            manager.channel() == channel && manager.isChannelOpen(),
            "Memory player channel is not attached and open");
        requireUnbound(channel);
        var handlers = new JsonArray();
        boolean ownsManager = false;
        for (var handler : channel.pipeline()
            .toMap()
            .values()) {
            String type = handler.getClass()
                .getName();
            handlers.add(new com.google.gson.JsonPrimitive(type));
            if (handler == manager) ownsManager = true;
            else require(
                type.startsWith("io.netty.channel.embedded."),
                "Unexpected handler in isolated player channel: " + type);
        }
        require(ownsManager, "Memory player pipeline is missing its network manager");
        var evidence = new JsonObject();
        evidence.addProperty("sameChannel", true);
        evidence.addProperty("channelOpen", true);
        evidence.addProperty("rawOwnDispatcherPresent", false);
        evidence.addProperty("resolvedFallbackPresent", NetworkDispatcher.get(manager) != null);
        evidence.addProperty(
            "scope",
            "own channel and normal FML PLAYER route only; no real network or arbitrary third-party routing proof");
        evidence.add("pipelineHandlers", handlers);
        TLMNotHard.LOG.info("TLMNH_MEMORY_PLAYER_CHANNEL_VERIFIED {}", evidence);
    }

    /** FML的PLAYER目标直接读取该属性；null表示本通道没有真实客户端投递目标 */
    public static void requireUnbound(EmbeddedChannel channel) {
        require(
            channel.attr(NetworkDispatcher.FML_DISPATCHER)
                .get() == null,
            "A real dispatcher is attached to the memory player channel");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
