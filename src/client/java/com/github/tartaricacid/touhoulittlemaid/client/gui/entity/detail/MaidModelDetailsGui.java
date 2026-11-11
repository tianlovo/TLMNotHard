/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/detail/MaidModelDetailsGui.java, MIT
 * Adaptation: detached preview state, native offhand shield and same model container
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.detail;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.ModelDetailsButton;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.gui.MaidPreviewEntity;
import com.tianluoqaq.tlmnh.client.gui.MaidPreviewSeat;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentAccess;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidModelDetailsGui extends AbstractModelDetailsGui {

    private static final String[] STATES = { "beg", "walk", "sit", "ride", "helmet", "chest_plate", "leggings", "boots",
        "main_hand", "off_hand" };
    private final MaidPreviewSeat seat;
    private boolean walk;
    private JsonObject equipmentFixture = new JsonObject();

    public MaidModelDetailsGui(ContainerMaidModels container, BuiltinModelCatalog.Choice info) {
        super(container, info);
        guiEntity = new MaidPreviewEntity(container.getMaid().worldObj, info.getId());
        seat = new MaidPreviewSeat(guiEntity.worldObj);
    }

    @Override
    protected void applyReturnButtonLogic() {
        mc.displayGuiScreen(new MaidModelGui(container));
    }

    @Override
    protected void initSideButton() {
        for (int i = 0; i < STATES.length; i++) {
            int state = i;
            buttonList.add(
                new ModelDetailsButton(
                    10 + i,
                    2,
                    17 + 13 * i,
                    "gui.touhou_little_maid.skin_details." + STATES[i],
                    active -> toggle(state, active)));
        }
    }

    private void toggle(int state, boolean active) {
        switch (state) {
            case 0 -> guiEntity.setPose(MaidPose.BEGGING, active);
            case 1 -> walk = active;
            case 2 -> guiEntity.setSitting(active);
            case 3 -> guiEntity.mountEntity(active ? seat : null);
            // 原版以同一金头盔标记四个非空护甲槽，由动画读取槽位状态
            case 4, 5, 6, 7 -> guiEntity
                .setCurrentItemOrArmor(8 - state, active ? new ItemStack(Items.golden_helmet) : null);
            case 8 -> guiEntity.setCurrentItemOrArmor(0, active ? new ItemStack(Items.diamond_sword) : null);
            case 9 -> guiEntity.setOffhandItem(active ? previewShield() : null);
            default -> throw new IllegalArgumentException("Unknown preview state");
        }
    }

    private static ItemStack previewShield() {
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.SHIELDS, () -> {
            var shield = CompatServices.shields()
                .previewShieldItem();
            if (shield == null) throw new IllegalStateException("Native wood shield missing for model preview");
            return new ItemStack(shield);
        }, () -> null);
    }

    /** 开发观测只改变未登记的预览实体，不发送装备或格挡状态到服务端 */
    public void previewShieldBlocking(boolean active) {
        if (!DevelopmentAccess.isEnabled()) throw new IllegalStateException("Development preview required");
        boolean using = active && guiEntity.getOffhandItem() != null;
        guiEntity.setPose(MaidPose.USING_ITEM, using);
        guiEntity.setPose(MaidPose.USING_OFFHAND, using);
    }

    /** 开发装备输入只进入本窗口的独立预览，沿现有原生物品工厂及保护边界 */
    public boolean previewEquipment(String action) {
        if (!DevelopmentAccess.isEnabled()) throw new IllegalStateException("Development preview required");
        if (action.equals("clear_offhand")) {
            guiEntity.setOffhandItem(null);
            previewShieldBlocking(false);
            return true;
        }
        if (action.equals("clear_main")) {
            guiEntity.setCurrentItemOrArmor(0, null);
            return true;
        }
        var construction = new JsonObject();
        var stack = IntegrationGuard.call(
            Side.CLIENT,
            ExternalFeature.SHIELDS,
            () -> ClientDevelopmentAccess.hooks()
                .createDetailsItemFixture(action, construction),
            () -> null);
        equipmentFixture = construction;
        if (stack == null) return false;
        if (action.startsWith("shield_")) guiEntity.setOffhandItem(stack);
        else guiEntity.setCurrentItemOrArmor(0, stack);
        return true;
    }

    public boolean previewAction(String action) {
        if (!DevelopmentAccess.isEnabled()) throw new IllegalStateException("Development preview required");
        for (var value : buttonList) if (value instanceof ModelDetailsButton button && button.id >= 10
            && button.id < 10 + STATES.length
            && button.isSelected()) button.press();
        ((MaidPreviewEntity) guiEntity).setDevelopmentAction(action);
        walk = false;
        guiEntity.limbSwing = guiEntity.limbSwingAmount = guiEntity.prevLimbSwingAmount = 0;
        return true;
    }

    @Override
    public JsonObject observation() {
        var result = super.observation();
        result.addProperty("previewWalk", walk);
        var preview = (MaidPreviewEntity) guiEntity;
        result.addProperty("previewAction", preview.getDevelopmentAction());
        result.addProperty("actionTicks", preview.getActionTicks());
        result.addProperty("previewSwing", preview.swingProgress);
        result.addProperty("previewSwingTick", preview.swingProgressInt);
        result.addProperty("previewHurtTime", preview.hurtTime);
        result.addProperty("previewAimingBow", preview.hasPose(MaidPose.AIMING_BOW));
        result.addProperty("previewSleeping", preview.hasPose(MaidPose.SLEEPING));
        result.add("equipmentFixture", new JsonParser().parse(equipmentFixture.toString()));
        result.add(
            "sourceVisibleState",
            ClientDevelopmentAccess.hooks()
                .captureVisibleState(container.getMaid()));
        result.add(
            "previewVisibleState",
            ClientDevelopmentAccess.hooks()
                .captureVisibleState(guiEntity));
        return result;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (guiEntity == null) return;
        guiEntity.ticksExisted++;
        ((MaidPreviewEntity) guiEntity).advanceDevelopmentAction();
        guiEntity.prevLimbSwingAmount = guiEntity.limbSwingAmount;
        guiEntity.limbSwingAmount += ((walk ? 0.5F : 0) - guiEntity.limbSwingAmount) * 0.4F;
        guiEntity.limbSwing += guiEntity.limbSwingAmount;
    }
}
