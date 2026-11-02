/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/maid/task/MaidTaskConfigGui.java, MIT
 * Adaptation: native close lifecycle; server container owns refresh and one-use save authority
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SetAttackListMessage;
import com.tianluoqaq.tlmnh.common.network.MaidAttackListPayload;
import com.tianluoqaq.tlmnh.common.network.MaidTaskConfigNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public abstract class MaidTaskConfigGui extends AbstractMaidContainerGui<TaskConfigContainer> {

    private boolean sent;

    protected MaidTaskConfigGui(TaskConfigContainer container) {
        super(container);
    }

    @Override
    protected boolean isTaskConfigPage() {
        return true;
    }

    private boolean hasEdits() {
        return !sent && maidContainer.attackPage()
            && maidContainer.ready()
            && maidContainer.editable()
            && maidContainer.dirty()
            && MaidAttackListPayload.fits(maidContainer.editedGroups());
    }

    @Override
    protected void send(int action) {
        if (hasEdits() && MaidTaskConfigNetwork.navigates(action)) {
            sent = true;
            MaidTaskConfigNetwork.send(
                new SetAttackListMessage(
                    MaidAttackListPayload.from(maidContainer, maidContainer.editedGroups(), true),
                    action));
            return;
        }
        super.send(action);
    }

    @Override
    public void onGuiClosed() {
        if (hasEdits()) {
            sent = true;
            MaidTaskConfigNetwork.send(
                new SetAttackListMessage(
                    MaidAttackListPayload.from(maidContainer, maidContainer.editedGroups(), true)));
        }
        super.onGuiClosed();
    }
}
