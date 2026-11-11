/*
 * Named model precedence adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/event/SpecialMaidRenderEvent.java, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.client.render.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Supplier;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 名字仅覆盖本次绘制，原存储模型及目录索引保持；占位作用域只属于指定的脱离世界预览实体 */
@SideOnly(Side.CLIENT)
public final class MaidNamedModelOverride {

    private static final ThreadLocal<EntityMaid> PLACEHOLDER_PREVIEW = new ThreadLocal<>();

    private MaidNamedModelOverride() {}

    public static BuiltinModelCatalog.Choice resolve(EntityMaid maid) {
        if (PLACEHOLDER_PREVIEW.get() == maid) return BuiltinModelCatalog.easterEggPlaceholder();
        return resolve(maid.getModelId(), maid.hasCustomNameTag() ? maid.getCustomNameTag() : "");
    }

    public static BuiltinModelCatalog.Choice resolve(String requested, String customName) {
        String encrypted = null;
        if (customName != null && !customName.trim()
            .isEmpty()) {
            // 上游优先加密标签，再匹配普通标签；标签精确匹配，不裁剪或忽略大小写
            for (var choice : BuiltinModelCatalog.choices())
                if (choice.isEasterEgg() && choice.isEasterEggEncrypted()) {
                    if (encrypted == null) encrypted = sha1(customName);
                    if (choice.getEasterEggTag()
                        .equals(encrypted)) return choice;
                }
            for (var choice : BuiltinModelCatalog.choices()) if (choice.isEasterEgg() && !choice.isEasterEggEncrypted()
                && choice.getEasterEggTag()
                    .equals(customName))
                return choice;
        }
        return BuiltinModelCatalog.get(requested);
    }

    public static <T> T placeholder(EntityMaid preview, Supplier<T> draw) {
        Objects.requireNonNull(preview, "preview");
        var previous = PLACEHOLDER_PREVIEW.get();
        PLACEHOLDER_PREVIEW.set(preview);
        try {
            return draw.get();
        } finally {
            if (previous == null) PLACEHOLDER_PREVIEW.remove();
            else PLACEHOLDER_PREVIEW.set(previous);
        }
    }

    private static String sha1(String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-1")
                .digest(name.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(40);
            for (byte value : digest) result.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required platform SHA-1 is unavailable", failure);
        }
    }
}
