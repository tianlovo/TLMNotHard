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
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 真实原生装备相位与已准入主手属性共同确认，不读写previousEquipment */
public final class EquipmentEpoch {

    public static final int MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS = 16;
    private static final int MAX_RETIRED_ATTRIBUTE_MODIFIERS = 32;
    private static final String ATTACK_ATTRIBUTE = SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName();
    private final EntityMaid maid;
    private ItemStack[] entering, enteringValues, acknowledged, acknowledgedValues;
    private ItemStack describedMain, describedContents;
    private Map<UUID, AttributeModifier> expectedModifiers;
    private Set<UUID> retiredModifiers = new HashSet<>();
    private long generation;
    private boolean phaseOpen, confirmed;

    public EquipmentEpoch(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, "maid");
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
                .hasKey("AttributeModifiers")) {
                if (!copy.getTagCompound()
                    .hasKey("AttributeModifiers", Constants.NBT.TAG_LIST)
                    || copy.getTagCompound()
                        .getTagList("AttributeModifiers", Constants.NBT.TAG_COMPOUND)
                        .tagCount() > MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS)
                    return refuse("main_hand_modifier_input_limit");
            }
            var modifiers = copy.getAttributeModifiers()
                .get(ATTACK_ATTRIBUTE);
            if (modifiers.size() > MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS) return refuse("main_hand_modifier_output_limit");
            var expected = new HashMap<UUID, AttributeModifier>();
            for (Object raw : modifiers) {
                if (!(raw instanceof AttributeModifier modifier) || modifier.getID() == null
                    || !Double.isFinite(modifier.getAmount())
                    || modifier.getOperation() < 0
                    || modifier.getOperation() > 2
                    || expected.put(modifier.getID(), modifier) != null)
                    return refuse("ambiguous_main_hand_modifier_definition");
            }
            if (!phaseCurrent(mainHand)) return false;
            var retiring = new HashSet<>(retiredModifiers);
            if (expectedModifiers != null) retiring.addAll(expectedModifiers.keySet());
            retiring.removeAll(expected.keySet());
            if (retiring.size() > MAX_RETIRED_ATTRIBUTE_MODIFIERS)
                return refuse("unresolved_main_hand_modifier_history_limit");
            describedMain = mainHand;
            describedContents = mainHand.copy();
            expectedModifiers = expected;
            retiredModifiers = retiring;
            return attributesCurrent();
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, "combat_equipment/modifier_query/" + maid.getUniqueID(), failure);
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
        if (attribute == null) return refuse("missing_native_attack_attribute");
        for (var entry : expectedModifiers.entrySet()) {
            var actual = attribute.getModifier(entry.getKey());
            var expected = entry.getValue();
            if (actual == null || actual.getOperation() != expected.getOperation()
                || Double.doubleToLongBits(actual.getAmount()) != Double.doubleToLongBits(expected.getAmount()))
                return refuse("native_main_hand_modifier_not_confirmed");
        }
        for (var identity : retiredModifiers) if (attribute.getModifier(identity) != null)
            // 其它装备或外部来源同UUID时归因不明，不能删除它来伪造主手确认
            return refuse("old_main_hand_modifier_still_present_or_ambiguous");
        retiredModifiers.clear();
        return true;
    }

    private boolean refuse(String reason) {
        IntegrationGuard.report(
            Side.SERVER,
            "combat_equipment/" + reason + "/" + maid.getUniqueID(),
            new IllegalStateException("Native equipment capability withheld: " + reason));
        return false;
    }

    private static boolean knownItem(ItemStack stack, MaidWeaponProfile profile) {
        if (stack == null || profile == null || !profile.supported()) return false;
        var item = stack.getItem();
        return switch (profile.family) {
            case VANILLA_BOW -> item == Items.bow && item.getClass() == ItemBow.class;
            case VANILLA_SWORD -> item.getClass() == ItemSword.class
                && (item == Items.wooden_sword || item == Items.stone_sword
                    || item == Items.iron_sword
                    || item == Items.golden_sword
                    || item == Items.diamond_sword);
            case GREGTECH_KNIFE, GREGTECH_BUTCHERY_KNIFE -> item.getClass()
                .getName()
                .equals("gregtech.common.items.MetaGeneratedTool01");
            default -> false;
        };
    }

    private ItemStack[] equipment() {
        var result = new ItemStack[6];
        for (int slot = 0; slot < 5; slot++) result[slot] = maid.getEquipmentInSlot(slot);
        result[5] = maid.getOffhandItem();
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
