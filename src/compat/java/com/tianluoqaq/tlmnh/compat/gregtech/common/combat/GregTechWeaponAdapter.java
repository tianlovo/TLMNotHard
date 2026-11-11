package com.tianluoqaq.tlmnh.compat.gregtech.common.combat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponStrike;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** GT普通刀的明确能力子集，不调用玩家左键、玩家伤害分量回调或假玩家 */
public final class GregTechWeaponAdapter {

    private static final String GUARD = "combat/weapon/gregtech_knife";
    private static final String SUPPORTED_DEPENDENCY = "5.09.54.265:sha256:"
        + "b969e11625a0298416a75b6e39703da795233edb71cb66f25fb9bbf654d75528";

    private GregTechWeaponAdapter() {}

    public static MaidWeaponProfile inspect(ItemStack original) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD))
            return MaidWeaponProfile.unknown("gregtech_capability_disabled");
        try {
            if (original == null) return MaidWeaponProfile.unknown("missing_gregtech_stack");
            var before = original.copy();
            var admitted = admission(original.copy());
            if (!ItemStack.areItemStacksEqual(original, before))
                throw new IllegalStateException("GregTech inspection changed original stack");
            return admitted.profile;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            return MaidWeaponProfile.unknown("gregtech_capability_failure");
        }
    }

    public static MaidWeaponStrike prepare(EntityMaid maid, ItemStack original) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD) || maid == null
            || maid.worldObj == null
            || maid.worldObj.isRemote
            || maid.getHeldItem() != original) return null;
        try {
            var admitted = admission(original.copy());
            if (!admitted.profile.melee() || maid.getHeldItem() != original) return null;
            var before = original.copy();
            // getToolStats会重建ench；真实栈仅在提交准备阶段按GT原生顺序规范化
            var actualStats = admitted.api.toolStats(admitted.tool, original);
            if (actualStats != admitted.stats || maid.getHeldItem() != original
                || !ItemStack.areItemStacksEqual(original, admitted.normalized))
                throw new IllegalStateException("GregTech normalization did not match admitted copy");
            boolean changed = !ItemStack.areItemStacksEqual(original, before);
            return new MaidWeaponStrike(
                original,
                admitted.profile,
                changed,
                new NativeEffects(admitted.api, admitted.tool, admitted.stats, original.getItemDamage()));
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            return null;
        }
    }

    private static Admission admission(ItemStack copy) {
        if (!SUPPORTED_DEPENDENCY.equals(DependencyFingerprint.mod("gregtech")))
            return Admission.unknown("gregtech_dependency_not_admitted");
        var api = GregTechWeaponApi.get();
        if (copy == null || copy.stackSize != 1 || copy.getItem() == null || !api.exactTool(copy.getItem()))
            return Admission.unknown("gregtech_item_family_not_admitted");
        var tool = copy.getItem();
        int metadata = copy.getItemDamage();
        boolean butchery;
        MaidWeaponProfile.Family family;
        int resistance;
        if (metadata == api.knifeMetadata) {
            butchery = false;
            family = MaidWeaponProfile.Family.GREGTECH_KNIFE;
            resistance = 3;
        } else if (metadata == api.butcheryMetadata) {
            butchery = true;
            family = MaidWeaponProfile.Family.GREGTECH_BUTCHERY_KNIFE;
            resistance = 2;
        } else return Admission.unknown("gregtech_stats_family_not_admitted");
        var stats = api.registeredStats(tool, metadata);
        if (!api.exactStats(stats, butchery)) return Admission.unknown("gregtech_stats_registration_changed");
        // 该公开方法仅枚举行为并执行我们自己的恒true谓词，不执行行为的玩家回调
        if (api.hasBehavior(tool, copy)) return Admission.unknown("gregtech_player_behavior_registered");
        if (api.electric(tool, copy)) return Admission.unknown("gregtech_electric_tool_not_admitted");
        if (api.missingPrimaryMaterial(copy)) return Admission.unknown("gregtech_primary_material_missing");
        long maximum = api.maximumDamage(copy), damage = api.currentDamage(copy);
        if (maximum <= 0 || damage < 0 || damage >= maximum)
            return Admission.unknown("gregtech_invalid_or_exhausted_durability");
        if (api.toolStats(tool, copy) != stats) return Admission.unknown("gregtech_stats_changed_during_query");
        float physicalBonus = api.combatDamage(tool, copy);
        int cost = api.entityAttackCost(stats);
        if (!Float.isFinite(physicalBonus) || cost <= 0 || damage > Long.MAX_VALUE - cost)
            return Admission.unknown("gregtech_invalid_mechanical_values");
        var profile = MaidWeaponCatalog.describe(
            MaidWeaponProfile.Kind.MELEE,
            family,
            SUPPORTED_DEPENDENCY,
            copy,
            physicalBonus,
            cost,
            maximum - damage,
            resistance);
        return new Admission(profile, api, tool, stats, copy);
    }

    private static final class Admission {

        final MaidWeaponProfile profile;
        final GregTechWeaponApi api;
        final Item tool;
        final Object stats;
        final ItemStack normalized;

        Admission(MaidWeaponProfile profile, GregTechWeaponApi api, Item tool, Object stats, ItemStack normalized) {
            this.profile = profile;
            this.api = api;
            this.tool = tool;
            this.stats = stats;
            this.normalized = normalized;
        }

        static Admission unknown(String reason) {
            return new Admission(MaidWeaponProfile.unknown(reason), null, null, null, null);
        }
    }

    private static final class NativeEffects implements MaidWeaponStrike.HitEffects {

        private final GregTechWeaponApi api;
        private final Item tool;
        private final Object stats;
        private final int metadata;

        NativeEffects(GregTechWeaponApi api, Item tool, Object stats, int metadata) {
            this.api = api;
            this.tool = tool;
            this.stats = stats;
            this.metadata = metadata;
        }

        @Override
        public DamageSource damageSource(EntityMaid maid, EntityLivingBase target) {
            try {
                if (!IntegrationGuard.available(Side.SERVER, GUARD))
                    throw new IllegalStateException("GregTech weapon capability disabled before submission");
                checkRegistration();
                return api.damageSource(stats, maid, target);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                IntegrationGuard.report(Side.SERVER, GUARD, failure);
                throw failure;
            }
        }

        @Override
        public void settleAcceptedHit(ItemStack original, EntityLivingBase target, EntityMaid maid) {
            try {
                // 已接受的命中不因别处熔断或会话取消而免除原武器消耗
                checkRegistration();
                if (original.getItem() != tool || original.getItemDamage() != metadata || api.electric(tool, original))
                    throw new IllegalStateException("GregTech original weapon identity changed");
                target.hurtResistantTime = Math.max(1, api.hurtResistance(stats, target.hurtResistantTime, target));
                // GT左键不经hitEntity耗损，避免在此之后再走一次普通物品消耗
                if (!api.damageTool(tool, original, api.entityAttackCost(stats)))
                    throw new IllegalStateException("GregTech rejected accepted-hit resource settlement");
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                IntegrationGuard.report(Side.SERVER, GUARD, failure);
                throw failure;
            }
        }

        private void checkRegistration() {
            if (!api.exactTool(tool) || api.registeredStats(tool, metadata) != stats)
                throw new IllegalStateException("GregTech weapon registration invalidated");
            // metadata读取在已核查的普通GT物品上，无需执行任何注册行为
            var key = new ItemStack(tool, 1, metadata);
            if (api.hasBehavior(tool, key))
                throw new IllegalStateException("GregTech player behavior added after admission");
        }
    }
}
