package com.tianluoqaq.tlmnh.common.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 真实原生装备相位与已准入主手属性共同确认，不读写previousEquipment */
public final class EquipmentEpoch {

    private final EntityMaid maid;
    private ItemStack[] entering, enteringValues, acknowledged, acknowledgedValues;
    private ItemStack describedMain, describedContents;
    private Map<UUID, AttributeModifier> expectedModifiers;
    private Set<UUID> retiredModifiers = new HashSet<>();
    private long generation;
    private boolean phaseOpen, confirmed;

    public EquipmentEpoch(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, MaidCombatIds.ID_MAID);
    }

    /** 原生更新提前退出时不能沿用上一轮确认 */
    public void beforeNativeUpdate() {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        if (confirmed && !same(acknowledged, acknowledgedValues)) invalidate();
        confirmed = false;
        entering = equipment();
        enteringValues = copies(entering);
        phaseOpen = true;
    }

    /** 原生装备差异循环之后、EntityMaid.onLivingUpdate入口调用 */
    public void afterNativeEquipmentUpdate() {
        if (!phaseOpen || maid.worldObj == null || maid.worldObj.isRemote) return;
        phaseOpen = false;
        if (!same(entering, enteringValues)) {
            invalidate();
            return;
        }
        acknowledged = entering;
        acknowledgedValues = enteringValues;
        entering = enteringValues = null;
        confirmed = true;
    }

    public void invalidate() {
        generation++;
        confirmed = false;
        phaseOpen = false;
        entering = enteringValues = acknowledged = acknowledgedValues = null;
        // 保留有限旧主手UUID以检出未被原生移除的贡献，不擅自删除任何属性
    }

    /** 只有新受支持内容需要一次有预算的副本查询，后续提交仅核有限UUID集合 */
    public boolean bindAttackModifiers(ItemStack mainHand, MaidWeaponProfile admitted, CombatWorkQuota quota) {
        if (!phaseCurrent(mainHand) || !knownItem(mainHand, admitted)) return false;
        if (describedMain == mainHand && ItemStack.areItemStacksEqual(mainHand, describedContents))
            return attributesCurrent();
        if (quota == null || !quota.spend(Work.ITEM)) return false;
        try {
            var copy = mainHand.copy();
            // 原生ItemStack会先遍历整份自定义列表，必须在查询前限制输入规模
            if (copy.hasTagCompound() && copy.getTagCompound()
                .hasKey(MaidCombatIds.KEY_ATTRIBUTE_MODIFIERS)) {
                if (!copy.getTagCompound()
                    .hasKey(MaidCombatIds.KEY_ATTRIBUTE_MODIFIERS, Constants.NBT.TAG_LIST)
                    || copy.getTagCompound()
                        .getTagList(MaidCombatIds.KEY_ATTRIBUTE_MODIFIERS, Constants.NBT.TAG_COMPOUND)
                        .tagCount() > MaidCombatTuning.MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS)
                    return refuse(MaidCombatIds.ID_MAIN_HAND_MODIFIER_INPUT_LIMIT);
            }
            var modifiers = copy.getAttributeModifiers()
                .get(MaidCombatAttributeIds.ATTACK_ATTRIBUTE);
            if (modifiers.size() > MaidCombatTuning.MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS)
                return refuse(MaidCombatIds.ID_MAIN_HAND_MODIFIER_OUTPUT_LIMIT);
            var expected = new HashMap<UUID, AttributeModifier>();
            for (Object raw : modifiers) {
                if (!(raw instanceof AttributeModifier modifier) || modifier.getID() == null
                    || !Double.isFinite(modifier.getAmount())
                    || modifier.getOperation() < 0
                    || modifier.getOperation() > MaidCombatTuning.TARGET_SIZE_THRESHOLD
                    || expected.put(modifier.getID(), modifier) != null)
                    return refuse(MaidCombatIds.ID_AMBIGUOUS_MAIN_HAND_MODIFIER_DEFINITION);
            }
            if (!phaseCurrent(mainHand)) return false;
            var retiring = new HashSet<>(retiredModifiers);
            if (expectedModifiers != null) retiring.addAll(expectedModifiers.keySet());
            retiring.removeAll(expected.keySet());
            if (retiring.size() > MaidCombatTuning.MAX_RETIRED_ATTRIBUTE_MODIFIERS)
                return refuse(MaidCombatIds.ID_UNRESOLVED_MAIN_HAND_MODIFIER_HISTORY_LIMIT);
            describedMain = mainHand;
            describedContents = mainHand.copy();
            expectedModifiers = expected;
            retiredModifiers = retiring;
            return attributesCurrent();
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard
                .report(Side.SERVER, MaidCombatIds.ID_COMBAT_EQUIPMENT_MODIFIER_QUERY + maid.getUniqueID(), failure);
            return false;
        }
    }

    /** 改栈和装备属性被外部删除/替换均撤销本次提交许可，独立buff不参与总量断言 */
    public boolean acknowledged(ItemStack mainHand) {
        return phaseCurrent(mainHand) && describedMain == mainHand
            && ItemStack.areItemStacksEqual(mainHand, describedContents)
            && attributesCurrent();
    }

    public long generation() {
        if (confirmed && !same(acknowledged, acknowledgedValues)) invalidate();
        return generation;
    }

    private boolean phaseCurrent(ItemStack mainHand) {
        if (!confirmed || maid.getHeldItem() != mainHand) return false;
        if (!same(acknowledged, acknowledgedValues)) {
            invalidate();
            return false;
        }
        return true;
    }

    private boolean attributesCurrent() {
        if (expectedModifiers == null) return false;
        var attribute = maid.getEntityAttribute(SharedMonsterAttributes.attackDamage);
        if (attribute == null) return refuse(MaidCombatIds.ID_MISSING_NATIVE_ATTACK_ATTRIBUTE);
        for (var entry : expectedModifiers.entrySet()) {
            var actual = attribute.getModifier(entry.getKey());
            var expected = entry.getValue();
            if (actual == null || actual.getOperation() != expected.getOperation()
                || Double.doubleToLongBits(actual.getAmount()) != Double.doubleToLongBits(expected.getAmount()))
                return refuse(MaidCombatIds.ID_NATIVE_MAIN_HAND_MODIFIER_NOT_CONFIRMED);
        }
        for (var identity : retiredModifiers) if (attribute.getModifier(identity) != null)
            // 其它装备或外部来源同UUID时归因不明，不能删除它来伪造主手确认
            return refuse(MaidCombatIds.ID_OLD_MAIN_HAND_MODIFIER_STILL_PRESENT_OR_AMBIGUOUS);
        retiredModifiers.clear();
        return true;
    }

    private boolean refuse(String reason) {
        IntegrationGuard.report(
            Side.SERVER,
            MaidCombatIds.ID_COMBAT_EQUIPMENT + reason + MaidCombatIds.PATH_SLASH_SEPARATOR + maid.getUniqueID(),
            new IllegalStateException(MaidCombatDiagnostics.MSG_NATIVE_EQUIPMENT_CAPABILITY_WITHHELD + reason));
        return false;
    }

    private static boolean knownItem(ItemStack stack, MaidWeaponProfile profile) {
        if (stack == null || profile == null || !profile.supported()) return false;
        var item = stack.getItem();
        return switch (profile.family) {
            case HEALING_AXE -> CompatServices.healingAxe()
                .isHealingAxe(stack);
            case VANILLA_BOW -> item == Items.bow && item.getClass() == ItemBow.class;
            case VANILLA_SWORD -> item.getClass() == ItemSword.class
                && (item == Items.wooden_sword || item == Items.stone_sword
                    || item == Items.iron_sword
                    || item == Items.golden_sword
                    || item == Items.diamond_sword);
            case GREGTECH_KNIFE, GREGTECH_BUTCHERY_KNIFE -> item.getClass()
                .getName()
                .equals(MaidCombatReflectionSpec.TARGET_CLASS_META_GENERATED_TOOL01);
            default -> false;
        };
    }

    private ItemStack[] equipment() {
        var result = new ItemStack[MaidCombatTuning.EQUIPMENT_EPOCH_EQUIPMENT_PARAM];
        for (int slot = 0; slot < MaidCombatTuning.MIN_SLOT_THRESHOLD; slot++)
            result[slot] = maid.getEquipmentInSlot(slot);
        result[MaidCombatTuning.MIN_SLOT_THRESHOLD] = maid.getOffhandItem();
        return result;
    }

    private boolean same(ItemStack[] references, ItemStack[] values) {
        if (references == null || values == null) return false;
        var actual = equipment();
        for (int slot = 0; slot < actual.length; slot++)
            if (actual[slot] != references[slot] || !ItemStack.areItemStacksEqual(actual[slot], values[slot]))
                return false;
        return true;
    }

    private static ItemStack[] copies(ItemStack[] references) {
        var result = new ItemStack[references.length];
        for (int slot = 0; slot < result.length; slot++) result[slot] = ItemStack.copyItemStack(references[slot]);
        return result;
    }
}
