package com.tianluoqaq.tlmnh.common.network;

import java.nio.charset.StandardCharsets;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.EmojiChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** 元数据只携带有界显示副本，过期与优先级只由服务端管理 */
public final class MaidBubbleCodec {

    public static final String EMPTY = "1;[]";

    private MaidBubbleCodec() {}

    public static String encode(ChatBubbleDataCollection collection) {
        var array = new JsonArray();
        for (var entry : collection.entries()
            .entrySet()) {
            var row = new JsonArray();
            row.add(new JsonPrimitive(entry.getKey()));
            row.add(
                new JsonPrimitive(
                    entry.getValue()
                        .isImage()));
            row.add(
                new JsonPrimitive(
                    entry.getValue()
                        .text()));
            array.add(row);
        }
        String value = "1;" + array;
        validateLength(value);
        return value;
    }

    public static ChatBubbleDataCollection decode(String raw) {
        validateLength(raw);
        if (!raw.startsWith("1;")) throw new IllegalArgumentException("Unknown bubble metadata version");
        var rows = new JsonParser().parse(raw.substring(2))
            .getAsJsonArray();
        if (rows.size() > ChatBubbleDataCollection.MAX_SIZE) throw new IllegalArgumentException("Too many bubble rows");
        var result = new ChatBubbleDataCollection();
        for (var item : rows) {
            var row = item.getAsJsonArray();
            if (row.size() != 3) throw new IllegalArgumentException("Invalid bubble row");
            long key = row.get(0)
                .getAsLong();
            boolean image = row.get(1)
                .getAsBoolean();
            String text = row.get(2)
                .getAsString();
            if (key <= 0 || result.containsKey(key) || text.length() > 256 || image && !text.isEmpty())
                throw new IllegalArgumentException("Invalid bubble identity/content");
            result.put(key, image ? EmojiChatBubbleData.create() : TextChatBubbleData.type2(text));
        }
        return result;
    }

    private static void validateLength(String value) {
        if (value == null || value.length() > 4096 || value.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IllegalArgumentException("Bubble metadata too large");
    }
}
