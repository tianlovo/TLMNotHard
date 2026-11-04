package com.tianluoqaq.tlmnh.compat.ysmu.client.animation;

import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 仅为实际女仆匹配原模型已有的乘客动画，保留提供方已成功匹配的结果 */
@SideOnly(Side.CLIENT)
public final class MaidPassengerAlias {

    private static final String GUARD = "ysmu/maid-passenger";
    private static final String PROVIDER = "1.9-git+9f76452dc6-dirty:sha256:5019eca24d80fb6e6ca998b20aba9ee047aa08fc075e9bb69e28fae3e373b952";
    private static final String ORIGINAL_ID = "touhou_little_maid:maid";
    private static JsonObject lastObserved;
    private static long matches;

    private MaidPassengerAlias() {}

    public static String resolve(Entity matched, String prefix, Set<String> declared, String original) {
        if (original == null || !original.isEmpty()
            || !"passenger$".equals(prefix)
            || !(matched instanceof EntityMaid maid)
            || !maid.isEntityAlive()
            || declared == null
            || !IntegrationGuard.available(Side.CLIENT, GUARD)) return original;
        try {
            String declaredId = null;
            for (String id : declared) {
                if (ORIGINAL_ID.equalsIgnoreCase(id)) {
                    declaredId = id;
                    break;
                }
            }
            if (declaredId == null) return original;
            if (!PROVIDER.equals(DependencyFingerprint.mod("ysmu")))
                throw new IllegalStateException("YSM passenger matcher provider differs from the inspected artifact");
            String clip = prefix + declaredId;
            if (Boolean.getBoolean("tlmnh.devCommands")) {
                matches++;
                var observed = new JsonObject();
                observed.addProperty(
                    "maidUUID",
                    maid.getUniqueID()
                        .toString());
                String registry = EntityList.getEntityString(maid);
                observed.addProperty("actualRegistryId", registry);
                String canonical = registry == null ? "" : registry.toLowerCase(java.util.Locale.US);
                if (!canonical.isEmpty() && canonical.indexOf(':') < 0) canonical = "minecraft:" + canonical;
                observed.addProperty("canonicalActualId", canonical);
                observed.addProperty("originalDoTest", original);
                observed.addProperty("selectedClip", clip);
                observed.addProperty("maidEntityTick", maid.ticksExisted);
                lastObserved = observed;
            }
            return clip;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.CLIENT, GUARD, failure);
            return original;
        }
    }

    public static JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("aliasMatches", matches);
        result.addProperty("guardAvailable", IntegrationGuard.available(Side.CLIENT, GUARD));
        if (lastObserved != null) result.add("lastAliasMatch", lastObserved);
        return result;
    }
}
