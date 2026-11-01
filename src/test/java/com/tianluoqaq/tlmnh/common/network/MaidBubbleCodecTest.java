package com.tianluoqaq.tlmnh.common.network;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.*;

public class MaidBubbleCodecTest {

    @Test
    public void expiryBoundaryAndPriorityEviction() {
        var now = new AtomicLong(1000);
        var data = new ChatBubbleDataCollection(now::get);
        long key = data.add(TextChatBubbleData.type2("hello"));
        now.set(key);
        assertFalse(data.update());
        now.incrementAndGet();
        assertTrue(data.update());
        assertTrue(data.isEmpty());
        for (int i = 0; i < 5; i++) {
            now.incrementAndGet();
            data.add(TextChatBubbleData.create(300, "high" + i, 5));
        }
        assertEquals(-1, data.add(TextChatBubbleData.create(300, "low", 0)));
        assertEquals(5, data.size());
        long first = data.entries()
            .firstKey();
        now.incrementAndGet();
        assertTrue(data.add(TextChatBubbleData.create(300, "equal", 5)) > 0);
        assertFalse(data.containsKey(first));
        assertEquals(5, data.size());
    }

    @Test
    public void boundedWireRoundtripAndMalformedInput() {
        var data = new ChatBubbleDataCollection();
        data.put(1, TextChatBubbleData.type2("(๑>◡<๑)"));
        data.put(2, EmojiChatBubbleData.create());
        var copy = MaidBubbleCodec.decode(MaidBubbleCodec.encode(data));
        assertEquals(2, copy.size());
        assertEquals(
            "(๑>◡<๑)",
            copy.get(1)
                .text());
        assertTrue(
            copy.get(2)
                .isImage());
        assertThrows(
            UnsupportedOperationException.class,
            () -> copy.entries()
                .clear());
        assertThrows(RuntimeException.class, () -> MaidBubbleCodec.decode("2;[]"));
        assertThrows(RuntimeException.class, () -> MaidBubbleCodec.decode("1;[[1,false,\"a\"],[1,false,\"b\"]]"));
        assertThrows(RuntimeException.class, () -> MaidBubbleCodec.decode("1;[[1,true,\"a\"]]"));
        assertThrows(
            RuntimeException.class,
            () -> MaidBubbleCodec
                .decode("1;[[1,false,\"" + String.join("", java.util.Collections.nCopies(257, "x")) + "\"]]"));
        assertThrows(
            RuntimeException.class,
            () -> MaidBubbleCodec
                .decode("1;[[1,true,\"\"],[2,true,\"\"],[3,true,\"\"],[4,true,\"\"],[5,true,\"\"],[6,true,\"\"]]"));
    }
}
