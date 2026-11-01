/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/ChatBubbleDataCollection.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.LongSupplier;

public final class ChatBubbleDataCollection {

    public static final int MAX_SIZE = 5;
    private final NavigableMap<Long, IChatBubbleData> chatBubbles = new TreeMap<>();
    private final LongSupplier clock;

    public ChatBubbleDataCollection() {
        this(System::currentTimeMillis);
    }

    public ChatBubbleDataCollection(LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public long add(IChatBubbleData data) {
        long expires = Math.addExact(clock.getAsLong(), data.existTick() * 50L);
        if (chatBubbles.size() >= MAX_SIZE) {
            Long remove = null;
            for (var entry : chatBubbles.entrySet()) if (entry.getValue()
                .priority() <= data.priority()) {
                    remove = entry.getKey();
                    break;
                }
            if (remove == null) return -1;
            chatBubbles.remove(remove);
        }
        chatBubbles.put(expires, data);
        return expires;
    }

    public boolean update() {
        long now = clock.getAsLong();
        boolean dirty = false;
        while (!chatBubbles.isEmpty() && now > chatBubbles.firstKey()) {
            chatBubbles.pollFirstEntry();
            dirty = true;
        }
        return dirty;
    }

    public int size() {
        return chatBubbles.size();
    }

    public boolean isEmpty() {
        return chatBubbles.isEmpty();
    }

    public IChatBubbleData get(long key) {
        return chatBubbles.get(key);
    }

    public void remove(long key) {
        chatBubbles.remove(key);
    }

    public boolean containsKey(long key) {
        return chatBubbles.containsKey(key);
    }

    public void clear() {
        chatBubbles.clear();
    }

    public void put(long key, IChatBubbleData data) {
        if (!chatBubbles.containsKey(key) && size() >= MAX_SIZE)
            throw new IllegalArgumentException("Too many chat bubbles");
        chatBubbles.put(key, java.util.Objects.requireNonNull(data));
    }

    public NavigableMap<Long, IChatBubbleData> entries() {
        return Collections.unmodifiableNavigableMap(chatBubbles);
    }
}
