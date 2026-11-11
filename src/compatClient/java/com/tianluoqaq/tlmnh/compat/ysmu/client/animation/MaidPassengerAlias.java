package com.tianluoqaq.tlmnh.compat.ysmu.client.animation;

import java.util.Locale;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.ysmu.YsmuCompat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 仅为实际女仆匹配原模型已有的乘客动画，保留提供方已成功匹配的结果 */
@SideOnly(Side.CLIENT)
public final class MaidPassengerAlias {




    private static JsonObject lastObserved;
    private static long matches;

    private MaidPassengerAlias() {}

    public static String resolve(Entity matched, String prefix, Set<String> declared, String original) {
        if (original == null || !original.isEmpty()
            || !YsmuClientAnimationIds.ID_PASSENGER.equals(prefix)
            || !(matched instanceof EntityMaid maid)
            || !maid.isEntityAlive()
            || declared == null
            || !IntegrationGuard.available(Side.CLIENT, YsmuClientAnimationIds.GUARD)) return original;
        try {
            String declaredId = null;
            for (String id : declared) {
                if (YsmuClientAnimationIds.ORIGINAL_ID.equalsIgnoreCase(id)) {
                    declaredId = id;
                    break;
                }
            }
            if (declaredId == null) return original;
            if (!YsmuClientAnimationIds.PROVIDER.equals(DependencyFingerprint.mod(YsmuCompat.MOD_ID)))
                throw new IllegalStateException(YsmuClientAnimationDiagnostics.MSG_YSM_PASSENGER_MATCHER_PROVIDER_DIFFERS);
            String clip = prefix + declaredId;
            if (DevelopmentFlags.isDevCommandsEnabled()) {
                matches++;
                var observed = new JsonObject();
                observed.addProperty(
                    YsmuClientAnimationIds.ID_MAIDUUID,
                    maid.getUniqueID()
                        .toString());
                String registry = EntityList.getEntityString(maid);
                observed.addProperty(YsmuClientAnimationIds.ID_ACTUALREGISTRYID, registry);
                String canonical = registry == null ? "" : registry.toLowerCase(Locale.US);
                if (!canonical.isEmpty() && canonical.indexOf(':') < 0) canonical = YsmuClientAnimationIds.ID_MINECRAFT + canonical;
                observed.addProperty(YsmuClientAnimationIds.ID_CANONICALACTUALID, canonical);
                observed.addProperty(YsmuClientAnimationIds.ID_ORIGINALDOTEST, original);
                observed.addProperty(YsmuClientAnimationIds.ID_SELECTEDCLIP, clip);
                observed.addProperty(YsmuClientAnimationIds.ID_MAIDENTITYTICK, maid.ticksExisted);
                lastObserved = observed;
            }
            return clip;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.CLIENT, YsmuClientAnimationIds.GUARD, failure);
            return original;
        }
    }

    public static JsonObject observation() {
        var result = new JsonObject();
        result.addProperty(YsmuClientAnimationIds.ID_ALIASMATCHES, matches);
        result.addProperty(YsmuClientAnimationIds.ID_GUARDAVAILABLE, IntegrationGuard.available(Side.CLIENT, YsmuClientAnimationIds.GUARD));
        if (lastObserved != null) result.add(YsmuClientAnimationIds.ID_LASTALIASMATCH, lastObserved);
        return result;
    }
}
