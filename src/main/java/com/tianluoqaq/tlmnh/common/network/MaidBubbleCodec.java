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
        String value = MaidNetworkProtocol.ID_1 + array;
        validateLength(value);
        return value;
    }

    public static ChatBubbleDataCollection decode(String raw) {
        validateLength(raw);
        if (!raw.startsWith(MaidNetworkProtocol.ID_1))
            throw new IllegalArgumentException(MaidNetworkProtocol.MSG_UNKNOWN_BUBBLE_METADATA_VERSION);
        var rows = new JsonParser().parse(raw.substring(MaidNetworkProtocol.ATTACK_LIST_BYTES))
            .getAsJsonArray();
        if (rows.size() > ChatBubbleDataCollection.MAX_SIZE)
            throw new IllegalArgumentException(MaidNetworkProtocol.MSG_TOO_MANY_BUBBLE_ROWS);
        var result = new ChatBubbleDataCollection();
        for (var item : rows) {
            var row = item.getAsJsonArray();
            if (row.size() != MaidNetworkProtocol.ATTACK_LIST_FITS_PARAM)
                throw new IllegalArgumentException(MaidNetworkProtocol.MSG_INVALID_BUBBLE_ROW);
            long key = row.get(MaidNetworkProtocol.GET_INITIAL_VALUE)
                .getAsLong();
            boolean image = row.get(MaidNetworkProtocol.MIN_LENGTH_THRESHOLD)
                .getAsBoolean();
            String text = row.get(MaidNetworkProtocol.ATTACK_LIST_BYTES)
                .getAsString();
            if (key <= 0 || result.containsKey(key)
                || text.length() > MaidNetworkProtocol.MAX_LENGTH_THRESHOLD
                || image && !text.isEmpty())
                throw new IllegalArgumentException(MaidNetworkProtocol.MSG_INVALID_BUBBLE_IDENTITY_CONTENT);
            result.put(key, image ? EmojiChatBubbleData.create() : TextChatBubbleData.type2(text));
        }
        return result;
    }

    private static void validateLength(String value) {
        if (value == null || value.length() > MaidNetworkProtocol.MAX_LENGTH_THRESHOLD_2
            || value.getBytes(StandardCharsets.UTF_8).length > MaidNetworkProtocol.MAX_LENGTH_THRESHOLD_3)
            throw new IllegalArgumentException(MaidNetworkProtocol.MSG_BUBBLE_METADATA_TOO_LARGE);
    }
}
