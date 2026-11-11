package com.tianluoqaq.tlmnh.common.work.container;

import java.util.function.Function;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;

/**
 * 育种工作模式任务配置容器：
 * 沿用 TaskConfigContainer 单次权威会话架构，支持实时快照同步、保存提交与关闭时一次性落盘
 */
public final class BreedingTaskConfigContainer extends AbstractMaidContainer {

    private final long session;
    private final EntityPlayer viewer;
    private final Function<EntityMaid, BreedingTaskConfigData> configAccessor;
    private final BreedingTaskConfigData baseline;
    private final BreedingTaskConfigData edited = new BreedingTaskConfigData();
    private final long openedTaskRevision;
    private long closedUntil = Long.MIN_VALUE;
    private int snapshotTicks;
    private boolean submitted;
    private boolean ready;
    private boolean editable;
    private boolean dirty;

    public BreedingTaskConfigContainer(InventoryPlayer inventory, EntityMaid maid, long session,
        Function<EntityMaid, BreedingTaskConfigData> configAccessor) {
        super(inventory, maid);
        this.session = session;
        this.viewer = inventory.player;
        this.configAccessor = configAccessor;
        this.openedTaskRevision = maid.getCombatTaskState().revision();
        BreedingTaskConfigData live = configAccessor.apply(maid);
        this.baseline = live.copy();
        this.edited.copyFrom(live);
    }

    public long session() {
        return session;
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

    public BreedingTaskConfigData editedConfig() {
        return edited;
    }

    public void markEdited() {
        dirty = true;
    }

    public boolean serverEditable() {
        return !submitted && canModifyWith(viewer)
            && maid.getTask() == MaidTask.CROPSNH_BREEDING
            && maid.getCombatTaskState().revision() == openedTaskRevision
            && configAccessor.apply(maid).equals(baseline);
    }

    public void acceptSnapshot(MaidBreedingConfigPayload payload) {
        if (!maid.worldObj.isRemote || payload == null || !payload.matchesIdentity(this)) return;
        if (!ready) {
            baseline.copyFrom(payload.config());
            edited.copyFrom(payload.config());
            ready = true;
        } else if (!baseline.equals(payload.config()) || maid.getTask() != MaidTask.CROPSNH_BREEDING) {
            editable = false;
            return;
        }
        editable = payload.editable();
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (maid.worldObj.isRemote
            || snapshotTicks++ % BreedingContainerProtocol.SNAPSHOT_SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (Object listener : crafters) {
            if (listener instanceof EntityPlayerMP player && player == viewer) {
                MaidBreedingConfigNetwork.snapshot(this, player, configAccessor.apply(maid));
            }
        }
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!maid.worldObj.isRemote && player == viewer && player instanceof EntityPlayerMP serverPlayer) {
            closedUntil = maid.worldObj.getTotalWorldTime() + BreedingContainerProtocol.CLOSE_GRACE_TICKS;
            MaidBreedingConfigNetwork.closed(this, serverPlayer);
            maid.getBrainCoordinator().refreshModernBrain();
        }
    }

    public boolean expired() {
        return closedUntil < maid.worldObj.getTotalWorldTime();
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        boolean accepted = super.enchantItem(player, action);
        if (accepted && (action >= MaidContainerProtocol.ACTION_IDLE && action <= MaidContainerProtocol.ACTION_RANGED
            || action == MaidContainerProtocol.ACTION_MAINTENANCE_HATCH
            || action == MaidContainerProtocol.ACTION_CROPSNH_BREEDING) && player.openContainer == this) {
            openTaskConfiguration(player);
        }
        return accepted;
    }

    public boolean submit(EntityPlayerMP player, MaidBreedingConfigPayload payload) {
        if (maid.worldObj.isRemote || player != viewer
            || submitted
            || payload == null
            || !payload.matchesIdentity(this)
            || maid.getTask() != MaidTask.CROPSNH_BREEDING
            || maid.getCombatTaskState().revision() != openedTaskRevision
            || !canModifyWith(player)
            || player.inventory.getItemStack() != null
            || player.openContainer != this && expired()
            || !configAccessor.apply(maid).equals(baseline)) {
            return false;
        }
        submitted = true;
        configAccessor.apply(maid).copyFrom(payload.config());
        maid.getBrainCoordinator().refreshModernBrain();
        return true;
    }
}
