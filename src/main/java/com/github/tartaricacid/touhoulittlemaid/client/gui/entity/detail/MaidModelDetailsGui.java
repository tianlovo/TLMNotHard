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
import com.tianluoqaq.tlmnh.client.gui.MaidPreviewEntity;
import com.tianluoqaq.tlmnh.client.gui.MaidPreviewSeat;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidModelDetailsGui extends AbstractModelDetailsGui {

    private static final String[] STATES = { "beg", "walk", "sit", "ride", "helmet", "chest_plate", "leggings", "boots",
        "main_hand", "off_hand" };
    private final MaidPreviewSeat seat;
    private boolean walk;

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
            case 4, 5, 6, 7 -> guiEntity
                .setCurrentItemOrArmor(8 - state, active ? new ItemStack(Items.golden_helmet) : null);
            case 8 -> guiEntity.setCurrentItemOrArmor(0, active ? new ItemStack(Items.diamond_sword) : null);
            case 9 -> {
                var shield = com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess
                    .woodenShieldItem();
                if (active && shield == null) throw new IllegalStateException("native wood shield missing");
                guiEntity.setOffhandItem(active ? new ItemStack(shield) : null);
            }
            default -> throw new IllegalArgumentException("Unknown preview state");
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (guiEntity == null) return;
        guiEntity.ticksExisted++;
        guiEntity.prevLimbSwingAmount = guiEntity.limbSwingAmount;
        guiEntity.limbSwingAmount += ((walk ? 0.5F : 0) - guiEntity.limbSwingAmount) * 0.4F;
        guiEntity.limbSwing += guiEntity.limbSwingAmount;
    }
}
