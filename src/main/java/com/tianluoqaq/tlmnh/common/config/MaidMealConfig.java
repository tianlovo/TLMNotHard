package com.tianluoqaq.tlmnh.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.common.foundation.ModContext;

/** 原版进食黑名单；低版本河豚以fish@3区分，避免禁掉所有鱼 */
public final class MaidMealConfig {

    private static final String[] DEFAULT_BLOCK = { MaidConfigConfigSpec.ID_MINECRAFT_FISH_3,
        MaidConfigConfigSpec.ID_MINECRAFT_POISONOUS_POTATO, MaidConfigConfigSpec.ID_MINECRAFT_ROTTEN_FLESH,
        MaidConfigConfigSpec.ID_MINECRAFT_SPIDER_EYE };
    private static String[] heal = DEFAULT_BLOCK, work = DEFAULT_BLOCK;
    private static List<Pattern> healRegex = new ArrayList<>(), workRegex = new ArrayList<>();

    private MaidMealConfig() {}

    public static void load(Configuration config) {
        String help = MaidConfigConfigSpec.MSG_ID_BLOCKED_ITEM_IDS_OPTIONAL;
        heal = config
            .getStringList(MaidConfigConfigSpec.KEY_HEAL_BLOCK_LIST, MaidConfigConfigSpec.ID_MEAL, DEFAULT_BLOCK, help);
        work = config
            .getStringList(MaidConfigConfigSpec.KEY_WORK_BLOCK_LIST, MaidConfigConfigSpec.ID_MEAL, DEFAULT_BLOCK, help);
        healRegex = patterns(
            config.getStringList(
                MaidConfigConfigSpec.KEY_HEAL_REGEX,
                MaidConfigConfigSpec.ID_MEAL,
                new String[0],
                MaidConfigConfigSpec.MSG_ID_HEAL_FOOD_ID_REGEX));
        workRegex = patterns(
            config.getStringList(
                MaidConfigConfigSpec.KEY_WORK_REGEX,
                MaidConfigConfigSpec.ID_MEAL,
                new String[0],
                MaidConfigConfigSpec.MSG_ID_WORK_FOOD_ID_REGEX));
    }

    public static boolean blocked(ItemStack stack, boolean healing) {
        String id = Item.itemRegistry.getNameForObject(stack.getItem());
        if (id == null) return true;
        for (String value : healing ? heal : work) if (id.equals(value)
            || (id + MaidConfigConfigSpec.MEAL_CONFIG_BLOCKED_MESSAGE + stack.getItemDamage()).equals(value))
            return true;
        for (Pattern pattern : healing ? healRegex : workRegex) if (pattern.matcher(id)
            .matches()) return true;
        return false;
    }

    private static List<Pattern> patterns(String[] values) {
        var result = new ArrayList<Pattern>();
        for (String value : values) try {
            result.add(Pattern.compile(value));
        } catch (PatternSyntaxException failure) {
            ModContext.LOG.warn(MaidConfigConfigSpec.MSG_IGNORED_INVALID_MEAL_BLACKLIST_REGEX, value);
        }
        return result;
    }
}
