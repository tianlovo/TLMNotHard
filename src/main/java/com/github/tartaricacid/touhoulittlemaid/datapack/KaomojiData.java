/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: datapack/KaomojiData.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.datapack;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;

public final class KaomojiData {

    private static final Map<String, List<String>> KAOMOJI_MAP = new HashMap<>();
    private static final Random RANDOM = new Random();

    private KaomojiData() {}

    public static void load() {
        var input = KaomojiData.class.getResourceAsStream("/data/touhou_little_maid/chat_bubble/kaomoji.json");
        if (input == null) throw new IllegalStateException("Missing builtin kaomoji data");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var object = new JsonParser().parse(reader)
                .getAsJsonObject();
            var data = new HashMap<String, List<String>>();
            for (var entry : object.entrySet()) {
                var list = new ArrayList<String>();
                for (var item : entry.getValue()
                    .getAsJsonArray()) {
                    String text = item.getAsString();
                    if (text.length() > 256) throw new IllegalArgumentException("Oversized builtin kaomoji");
                    list.add(text);
                }
                data.put(entry.getKey(), list);
            }
            var core = data.remove("core");
            if (core != null)
                for (String key : new String[] { "work", "idle" }) if (data.containsKey(key)) data.get(key)
                    .addAll(core);
            KAOMOJI_MAP.clear();
            KAOMOJI_MAP.putAll(data);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load builtin kaomoji", error);
        }
    }

    public static void showRoutineKaomoji(EntityMaid maid, ChatBubbleManager manager) {
        var activity = MaidLifeRules.activity(maid.getSchedule(), maid.worldObj.getWorldTime());
        String key = activity == MaidLifeRules.Activity.REST ? "sleep"
            : activity == MaidLifeRules.Activity.IDLE || maid.getTask() == MaidTask.IDLE ? "idle" : "work";
        show(key, manager);
    }

    public static void showHurtKaomoji(EntityMaid maid, ChatBubbleManager manager) {
        show("hurt", manager);
    }

    private static void show(String key, ChatBubbleManager manager) {
        var choices = KAOMOJI_MAP.get(key);
        if (choices == null || choices.isEmpty()) return;
        manager.addChatBubble(TextChatBubbleData.type2(choices.get(RANDOM.nextInt(choices.size()))));
    }
}
