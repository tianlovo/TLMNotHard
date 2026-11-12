package com.tianluoqaq.tlmnh.common.work.container;

import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingConfigIds;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.StatLogic;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.TargetMode;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/** 育种配置容器一次性会话的网络快照与提交载荷 */
public final class MaidBreedingConfigPayload {

    private final int windowId;
    private final int entityId;
    private final long session;
    private final boolean editable;
    private final BreedingTaskConfigData config;

    public MaidBreedingConfigPayload(int windowId, int entityId, long session, boolean editable,
        BreedingTaskConfigData config) {
        this.windowId = windowId;
        this.entityId = entityId;
        this.session = session;
        this.editable = editable;
        this.config = config == null ? new BreedingTaskConfigData() : config.copy();
    }

    public static MaidBreedingConfigPayload from(BreedingTaskConfigContainer menu, BreedingTaskConfigData config,
        boolean editable) {
        return new MaidBreedingConfigPayload(
            menu.windowId,
            menu.getMaid()
                .getEntityId(),
            menu.session(),
            editable,
            config);
    }

    public boolean matchesIdentity(BreedingTaskConfigContainer menu) {
        return menu != null && menu.windowId == windowId
            && menu.getMaid()
                .getEntityId() == entityId
            && menu.session() == session;
    }

    public int windowId() {
        return windowId;
    }

    public int entityId() {
        return entityId;
    }

    public long session() {
        return session;
    }

    public boolean editable() {
        return editable;
    }

    public BreedingTaskConfigData config() {
        return config.copy();
    }

    public void writeTo(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeInt(entityId);
        buf.writeLong(session);
        buf.writeBoolean(editable);
        ByteBufUtils.writeUTF8String(
            buf,
            config.isMutationPoolMode() ? BreedingConfigIds.MODE_MUTATION_POOL : BreedingConfigIds.MODE_CROP);
        ByteBufUtils.writeUTF8String(buf, config.targetId());
        buf.writeByte(config.targetGain());
        buf.writeByte(config.targetGrowth());
        buf.writeByte(config.targetResistance());
        ByteBufUtils.writeUTF8String(
            buf,
            config.statLogic() == StatLogic.OR ? BreedingConfigIds.LOGIC_OR : BreedingConfigIds.LOGIC_AND);
        buf.writeBoolean(config.max31Mode());
    }

    public static MaidBreedingConfigPayload readFrom(ByteBuf buf) {
        int win = buf.readInt();
        int ent = buf.readInt();
        long sess = buf.readLong();
        boolean edit = buf.readBoolean();
        String modeStr = ByteBufUtils.readUTF8String(buf);
        String idStr = ByteBufUtils.readUTF8String(buf);
        int gain = buf.readByte();
        int growth = buf.readByte();
        int res = buf.readByte();
        String logicStr = ByteBufUtils.readUTF8String(buf);
        boolean max31 = buf.readBoolean();

        BreedingTaskConfigData data = new BreedingTaskConfigData();
        data.setTargetMode(
            BreedingConfigIds.MODE_MUTATION_POOL.equals(modeStr) ? TargetMode.MUTATION_POOL : TargetMode.CROP);
        data.setTargetId(idStr);
        data.setTargetGain(gain);
        data.setTargetGrowth(growth);
        data.setTargetResistance(res);
        data.setStatLogic(BreedingConfigIds.LOGIC_OR.equals(logicStr) ? StatLogic.OR : StatLogic.AND);
        data.setMax31Mode(max31);
        return new MaidBreedingConfigPayload(win, ent, sess, edit, data);
    }
}
