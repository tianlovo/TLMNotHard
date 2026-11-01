package com.tianluoqaq.tlmnh.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.TLMNotHard;

/** 原版进食黑名单；低版本河豚以fish@3区分，避免禁掉所有鱼 */
public final class MaidMealConfig {

    private static final String[] DEFAULT_BLOCK = { "minecraft:fish@3", "minecraft:poisonous_potato",
        "minecraft:rotten_flesh", "minecraft:spider_eye" };
    private static String[] heal = DEFAULT_BLOCK, work = DEFAULT_BLOCK;
    private static List<Pattern> healRegex = new ArrayList<>(), workRegex = new ArrayList<>();

    private MaidMealConfig() {}

    public static void load(Configuration config) {
        String help = "不自动食用的物品ID，可用@区分元数据 / Blocked item IDs; optional @metadata";
        heal = config.getStringList("HealBlockList", "meal", DEFAULT_BLOCK, help);
        work = config.getStringList("WorkBlockList", "meal", DEFAULT_BLOCK, help);
        healRegex = patterns(
            config.getStringList("HealRegex", "meal", new String[0], "回血食物ID正则黑名单 / Heal food ID regex blacklist"));
        workRegex = patterns(
            config.getStringList("WorkRegex", "meal", new String[0], "日常食物ID正则黑名单 / Work food ID regex blacklist"));
    }

    public static boolean blocked(ItemStack stack, boolean healing) {
        String id = Item.itemRegistry.getNameForObject(stack.getItem());
        if (id == null) return true;
        for (String value : healing ? heal : work)
            if (id.equals(value) || (id + "@" + stack.getItemDamage()).equals(value)) return true;
        for (Pattern pattern : healing ? healRegex : workRegex) if (pattern.matcher(id)
            .matches()) return true;
        return false;
    }

    private static List<Pattern> patterns(String[] values) {
        var result = new ArrayList<Pattern>();
        for (String value : values) try {
            result.add(Pattern.compile(value));
        } catch (PatternSyntaxException failure) {
            TLMNotHard.LOG.warn("Ignored invalid meal blacklist regex: {}", value);
        }
        return result;
    }
}
