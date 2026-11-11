package com.tianluoqaq.tlmnh.common.work.notify;

import java.util.Objects;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

/** 单条缺件条件及其可选参数，供合并构造本地化女仆提醒 */
public final class MissingConditionEntry {

    public final MissingConditionCode code;
    public final String firstArg;
    public final String secondArg;
    public final boolean translateFirstArg;

    public MissingConditionEntry(MissingConditionCode code, String firstArg, String secondArg,
        boolean translateFirstArg) {
        this.code = Objects.requireNonNull(code);
        this.firstArg = firstArg == null ? "" : firstArg;
        this.secondArg = secondArg == null ? "" : secondArg;
        this.translateFirstArg = translateFirstArg;
    }

    public static MissingConditionEntry of(MissingConditionCode code) {
        return new MissingConditionEntry(code, "", "", false);
    }

    public static MissingConditionEntry withArg(MissingConditionCode code, String arg, boolean translateArg) {
        return new MissingConditionEntry(code, arg, "", translateArg);
    }

    public static MissingConditionEntry withTwoArgs(MissingConditionCode code, String arg1, String arg2,
        boolean translateArg1) {
        return new MissingConditionEntry(code, arg1, arg2, translateArg1);
    }

    public IChatComponent toChatComponent() {
        if (firstArg.isEmpty() && secondArg.isEmpty()) {
            return new ChatComponentTranslation(code.translationKey());
        }
        Object arg1Obj = translateFirstArg ? new ChatComponentTranslation(firstArg) : new ChatComponentText(firstArg);
        if (secondArg.isEmpty()) {
            return new ChatComponentTranslation(code.translationKey(), arg1Obj);
        }
        return new ChatComponentTranslation(code.translationKey(), arg1Obj, new ChatComponentText(secondArg));
    }

    public void appendSignature(StringBuilder sb) {
        sb.append(code.name())
            .append(MissingConditionIds.SIG_ARG_SEP)
            .append(firstArg)
            .append(MissingConditionIds.SIG_ARG_SEP)
            .append(secondArg);
    }
}
