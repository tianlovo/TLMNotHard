package com.tianluoqaq.tlmnh.common.interaction;

import net.minecraft.util.ChatAllowedCharacters;

/** 名称按原版编辑框的32个UTF-16字符计数，拒绝空白和无法安全传输的字符 */
public final class MaidNameRules {

    public static final int MAX_LENGTH = 32;

    private MaidNameRules() {}

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) return false;
        boolean hasText = false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!ChatAllowedCharacters.isAllowedCharacter(character) || Character.isISOControl(character)) return false;
            if (Character.isHighSurrogate(character)) {
                if (++index >= name.length() || !Character.isLowSurrogate(name.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) hasText = true;
        }
        return hasText;
    }
}
