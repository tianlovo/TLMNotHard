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




    private GregTechWeaponAdapter() {}

    public static MaidWeaponProfile inspect(ItemStack original) {
        if (!IntegrationGuard.available(Side.SERVER, GregTechCombatIds.GUARD))
            return MaidWeaponProfile.unknown(GregTechCombatIds.ID_GREGTECH_CAPABILITY_DISABLED);
        try {
            if (original == null) return MaidWeaponProfile.unknown(GregTechCombatIds.ID_MISSING_GREGTECH_STACK);
            var before = original.copy();
            var admitted = admission(original.copy());
            if (!ItemStack.areItemStacksEqual(original, before))
                throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_INSPECTION_CHANGED_ORIGINAL_STACK);
            return admitted.profile;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechCombatIds.GUARD, failure);
            return MaidWeaponProfile.unknown(GregTechCombatIds.ID_GREGTECH_CAPABILITY_FAILURE);
        }
    }

    public static MaidWeaponStrike prepare(EntityMaid maid, ItemStack original) {
        if (!IntegrationGuard.available(Side.SERVER, GregTechCombatIds.GUARD) || maid == null
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
                throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_NORMALIZATION_DID_NOT_MATCH);
            boolean changed = !ItemStack.areItemStacksEqual(original, before);
            return new MaidWeaponStrike(
                original,
                admitted.profile,
                changed,
                new NativeEffects(admitted.api, admitted.tool, admitted.stats, original.getItemDamage()));
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechCombatIds.GUARD, failure);
            return null;
        }
    }

    private static Admission admission(ItemStack copy) {
        if (!GregTechCombatIds.SUPPORTED_DEPENDENCY.equals(DependencyFingerprint.mod(GregTechCombatIds.ID_GREGTECH)))
            return Admission.unknown(GregTechCombatIds.ID_GREGTECH_DEPENDENCY_NOT_ADMITTED);
        var api = GregTechWeaponApi.get();
        if (copy == null || copy.stackSize != GregTechCombatTuning.TARGET_STACK_SIZE_THRESHOLD || copy.getItem() == null || !api.exactTool(copy.getItem()))
            return Admission.unknown(GregTechCombatIds.ID_GREGTECH_ITEM_FAMILY_NOT_ADMITTED);
        var tool = copy.getItem();
        int metadata = copy.getItemDamage();
        boolean butchery;
        MaidWeaponProfile.Family family;
        int resistance;
        if (metadata == api.knifeMetadata) {
            butchery = false;
            family = MaidWeaponProfile.Family.GREGTECH_KNIFE;
            resistance = GregTechCombatTuning.GREG_TECH_RESISTANCE;
        } else if (metadata == api.butcheryMetadata) {
            butchery = true;
            family = MaidWeaponProfile.Family.GREGTECH_BUTCHERY_KNIFE;
            resistance = GregTechCombatTuning.GREG_TECH_RESISTANCE_2;
        } else return Admission.unknown(GregTechCombatIds.ID_GREGTECH_STATS_FAMILY_NOT_ADMITTED);
        var stats = api.registeredStats(tool, metadata);
        if (!api.exactStats(stats, butchery)) return Admission.unknown(GregTechCombatIds.ID_GREGTECH_STATS_REGISTRATION_CHANGED);
        // 该公开方法仅枚举行为并执行我们自己的恒true谓词，不执行行为的玩家回调
        if (api.hasBehavior(tool, copy)) return Admission.unknown(GregTechCombatIds.ID_GREGTECH_PLAYER_BEHAVIOR_REGISTERED);
        if (api.electric(tool, copy)) return Admission.unknown(GregTechCombatIds.ID_GREGTECH_ELECTRIC_TOOL_NOT_ADMITTED);
        if (api.missingPrimaryMaterial(copy)) return Admission.unknown(GregTechCombatIds.ID_GREGTECH_PRIMARY_MATERIAL_MISSING);
        long maximum = api.maximumDamage(copy), damage = api.currentDamage(copy);
        if (maximum <= 0 || damage < 0 || damage >= maximum)
            return Admission.unknown(GregTechCombatIds.ID_GREGTECH_INVALID_OR_EXHAUSTED_DURABILITY);
        if (api.toolStats(tool, copy) != stats) return Admission.unknown(GregTechCombatIds.ID_GREGTECH_STATS_CHANGED_DURING_QUERY);
        float physicalBonus = api.combatDamage(tool, copy);
        int cost = api.entityAttackCost(stats);
        if (!Float.isFinite(physicalBonus) || cost <= 0 || damage > Long.MAX_VALUE - cost)
            return Admission.unknown(GregTechCombatIds.ID_GREGTECH_INVALID_MECHANICAL_VALUES);
        var profile = MaidWeaponCatalog.describe(
            MaidWeaponProfile.Kind.MELEE,
            family,
            GregTechCombatIds.SUPPORTED_DEPENDENCY,
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
                if (!IntegrationGuard.available(Side.SERVER, GregTechCombatIds.GUARD))
                    throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_WEAPON_CAPABILITY_DISABLED_BEFORE);
                checkRegistration();
                return api.damageSource(stats, maid, target);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                IntegrationGuard.report(Side.SERVER, GregTechCombatIds.GUARD, failure);
                throw failure;
            }
        }

        @Override
        public void settleAcceptedHit(ItemStack original, EntityLivingBase target, EntityMaid maid) {
            try {
                // 已接受的命中不因别处熔断或会话取消而免除原武器消耗
                checkRegistration();
                if (original.getItem() != tool || original.getItemDamage() != metadata || api.electric(tool, original))
                    throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_ORIGINAL_WEAPON_IDENTITY_CHANGED);
                target.hurtResistantTime = Math.max(GregTechCombatTuning.TARGET_STACK_SIZE_THRESHOLD, api.hurtResistance(stats, target.hurtResistantTime, target));
                // GT左键不经hitEntity耗损，避免在此之后再走一次普通物品消耗
                if (!api.damageTool(tool, original, api.entityAttackCost(stats)))
                    throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_REJECTED_ACCEPTED_HIT_RESOURCE);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                IntegrationGuard.report(Side.SERVER, GregTechCombatIds.GUARD, failure);
                throw failure;
            }
        }

        private void checkRegistration() {
            if (!api.exactTool(tool) || api.registeredStats(tool, metadata) != stats)
                throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_WEAPON_REGISTRATION_INVALIDATED);
            // metadata读取在已核查的普通GT物品上，无需执行任何注册行为
            var key = new ItemStack(tool, GregTechCombatTuning.TARGET_STACK_SIZE_THRESHOLD, metadata);
            if (api.hasBehavior(tool, key))
                throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_PLAYER_BEHAVIOR_ADDED_AFTER);
        }
    }
}
