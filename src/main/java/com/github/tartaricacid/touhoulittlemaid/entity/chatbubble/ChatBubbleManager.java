/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/ChatBubbleManager.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.network.MaidBubbleCodec;

public final class ChatBubbleManager {

    private static final int DATA_BUBBLES = 19;
    private final EntityMaid maid;
    private final ChatBubbleDataCollection server = new ChatBubbleDataCollection();
    private ChatBubbleDataCollection client = new ChatBubbleDataCollection();
    private String cached;

    public ChatBubbleManager(EntityMaid maid) {
        this.maid = maid;
    }

    public static void defineSynchedData(net.minecraft.entity.DataWatcher watcher) {
        watcher.addObject(DATA_BUBBLES, MaidBubbleCodec.EMPTY);
    }

    public ChatBubbleDataCollection getChatBubbleDataCollection() {
        if (!maid.worldObj.isRemote) return server;
        String raw = maid.getDataWatcher()
            .getWatchableObjectString(DATA_BUBBLES);
        if (!raw.equals(cached)) {
            cached = raw;
            try {
                client = MaidBubbleCodec.decode(raw);
            } catch (RuntimeException invalid) {
                client = new ChatBubbleDataCollection();
            }
        }
        return client;
    }

    public void tick() {
        if (maid.worldObj.isRemote) return;
        RandomEmoji.tick(maid);
        if (maid.ticksExisted % 5 == 0 && server.update()) forceUpdateChatBubble();
    }

    public long addChatBubble(IChatBubbleData data) {
        if (maid.worldObj.isRemote) return -1;
        long key = server.add(data);
        if (key != -1) forceUpdateChatBubble();
        return key;
    }

    public void removeChatBubble(long key) {
        if (maid.worldObj.isRemote) return;
        server.remove(key);
        forceUpdateChatBubble();
    }

    public void clear() {
        if (maid.worldObj.isRemote) return;
        server.clear();
        forceUpdateChatBubble();
    }

    public void forceUpdateChatBubble() {
        if (!maid.worldObj.isRemote) maid.getDataWatcher()
            .updateObject(DATA_BUBBLES, MaidBubbleCodec.encode(server));
    }
}
