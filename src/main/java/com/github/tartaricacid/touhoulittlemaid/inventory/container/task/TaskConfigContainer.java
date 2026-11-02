/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/task/TaskConfigContainer.java, MIT
 * Adaptation: native slots and a one-use session with close-time commit validation
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container.task;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.network.MaidAttackListPayload;
import com.tianluoqaq.tlmnh.common.network.MaidTaskConfigNetwork;

public final class TaskConfigContainer extends AbstractMaidContainer {

    private final long session;
    private final boolean attackPage;
    private final EntityPlayer viewer;
    private final Map<ResourceLocation, MonsterType> baseline;
    private final Map<ResourceLocation, MonsterType> edited = new HashMap<>();
    private MaidTask openedTask;
    private long closedUntil = Long.MIN_VALUE;
    private int snapshotTicks;
    private boolean submitted, ready, editable, dirty;

    public TaskConfigContainer(InventoryPlayer inventory, EntityMaid maid, long session, boolean attackPage) {
        super(inventory, maid);
        this.session = session;
        this.attackPage = attackPage;
        viewer = inventory.player;
        openedTask = maid.getTask();
        baseline = new HashMap<>(
            maid.getAttackListData()
                .attackGroups());
    }

    public long session() {
        return session;
    }

    public MaidTask openedTask() {
        return openedTask;
    }

    public boolean attackPage() {
        return attackPage;
    }

    public boolean ready() {
        return ready;
    }

    public boolean editable() {
        return editable;
    }

    public boolean dirty() {
        return dirty;
    }

    public boolean serverEditable() {
        return attackPage && !submitted
            && maid.getTask() == openedTask
            && maid.getAttackListData()
                .attackGroups()
                .equals(baseline)
            && MaidAttackListPayload.fits(baseline);
    }

    public Map<ResourceLocation, MonsterType> editedGroups() {
        return edited;
    }

    public void markEdited() {
        dirty = true;
    }

    public void acceptSnapshot(MaidAttackListPayload payload) {
        if (!maid.worldObj.isRemote || !payload.matchesIdentity(this)
            || (payload.task() != MaidTask.IDLE) != attackPage) return;
        if (!ready) {
            openedTask = payload.task();
            baseline.clear();
            baseline.putAll(payload.groups());
            edited.clear();
            edited.putAll(payload.groups());
            ready = true;
        } else if (!baseline.equals(payload.groups()) || openedTask != payload.task()) {
            editable = false;
            return;
        }
        editable = payload.editable();
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (maid.worldObj.isRemote || snapshotTicks++ % 20 != 0) return;
        for (Object listener : crafters) if (listener instanceof EntityPlayerMP player && player == viewer)
            MaidTaskConfigNetwork.snapshot(this, player);
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!maid.worldObj.isRemote && player == viewer && player instanceof EntityPlayerMP serverPlayer) {
            closedUntil = maid.worldObj.getTotalWorldTime() + 20;
            MaidTaskConfigNetwork.closed(this, serverPlayer);
            maid.getBrainCoordinator()
                .refreshModernBrain();
        }
    }

    public boolean expired() {
        return closedUntil < maid.worldObj.getTotalWorldTime();
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        boolean accepted = super.enchantItem(player, action);
        if (accepted && action >= ACTION_IDLE && action <= ACTION_RANGED && player.openContainer == this)
            openTaskConfiguration(player);
        return accepted;
    }

    public boolean submit(EntityPlayerMP player, MaidAttackListPayload payload) {
        if (maid.worldObj.isRemote || player != viewer
            || submitted
            || !attackPage
            || !payload.matchesIdentity(this)
            || payload.task() != openedTask
            || maid.getTask() != openedTask
            || !canInteractWith(player)
            || player.inventory.getItemStack() != null
            || player.openContainer != this && expired()
            || !MaidAttackListPayload.fits(baseline)
            || !maid.getAttackListData()
                .attackGroups()
                .equals(baseline))
            return false;
        var known = MaidCombatEntities.registeredLivingTypes();
        for (var id : payload.groups()
            .keySet()) if (!baseline.containsKey(id) && !known.containsKey(id)) return false;
        var next = new HashMap<>(payload.groups());
        submitted = true;
        var groups = maid.getAttackListData()
            .attackGroups();
        groups.clear();
        groups.putAll(next);
        if (!maid.getBrainCoordinator()
            .refreshModernBrain()) {
            maid.setAttackTarget(null);
            maid.getNavigator()
                .clearPathEntity();
        }
        return true;
    }
}
