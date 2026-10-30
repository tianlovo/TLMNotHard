package com.tianluoqaq.tlmnh.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** 模型窗口独立使用C11的8位动作，254为返回、255拒绝；增大目录必须迁移协议 */
public final class BuiltinModelSelection {

    public static final int BACK = 254;

    private BuiltinModelSelection() {}

    public static int decodeAction(int signedAction) {
        return signedAction < Byte.MIN_VALUE || signedAction > Byte.MAX_VALUE ? -1 : signedAction & 255;
    }

    public static int wireAction(int index) {
        if (index < 0 || index > BACK) throw new IllegalArgumentException("Invalid builtin model action");
        return (byte) index;
    }

    public static BuiltinModelCatalog.Choice choiceForAction(int signedAction) {
        int index = decodeAction(signedAction);
        var choices = choices();
        return index >= 0 && index < choices.size() && index < BACK ? choices.get(index) : null;
    }

    public static List<Integer> matching(boolean winefoxOnly, String search,
        Function<BuiltinModelCatalog.Choice, String> names) {
        String query = search.trim()
            .toLowerCase(Locale.ROOT);
        var result = new ArrayList<Integer>();
        var choices = choices();
        for (int index = 0; index < choices.size(); index++) {
            var choice = choices.get(index);
            if (winefoxOnly && !choice.isWinefox()) continue;
            if (query.isEmpty() || choice.getId()
                .toLowerCase(Locale.ROOT)
                .contains(query)
                || names.apply(choice)
                    .toLowerCase(Locale.ROOT)
                    .contains(query))
                result.add(index);
        }
        return result;
    }

    private static List<BuiltinModelCatalog.Choice> choices() {
        var choices = BuiltinModelCatalog.choices();
        if (choices.size() > BACK)
            throw new IllegalStateException("Builtin model C11 capacity exceeded; migrate the selection protocol");
        return choices;
    }

    public static int extraTextureNumber(BuiltinModelCatalog.Choice choice) {
        if (choice.getId()
            .equals(choice.getBaseId())) return 0;
        int number = 0;
        for (var candidate : BuiltinModelCatalog.choices()) {
            if (candidate.getBaseId()
                .equals(choice.getBaseId())
                && !candidate.getId()
                    .equals(candidate.getBaseId()))
                number++;
            if (candidate.getId()
                .equals(choice.getId())) return number;
        }
        throw new IllegalArgumentException("Choice is not in the builtin catalog");
    }
}
