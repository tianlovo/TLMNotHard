package com.tianluoqaq.tlmnh.common.network;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/** 编辑副本的有界包体；已有大存档不截断，超出单包上限时仅禁止本次编辑 */
public final class MaidAttackListPayload {

    private final int windowId, entityId, dimension;
    private final long session;
    private final UUID entityUuid;
    private final MaidTask task;
    private final boolean editable;
    private final Map<ResourceLocation, MonsterType> groups;

    private MaidAttackListPayload(int windowId, long session, int dimension, int entityId, UUID entityUuid,
        MaidTask task, boolean editable, Map<ResourceLocation, MonsterType> groups) {
        if (!fits(groups))
            throw new IllegalArgumentException(MaidNetworkProtocol.MSG_ATTACK_LIST_EXCEEDS_PACKET_LIMITS);
        this.windowId = windowId;
        this.session = session;
        this.dimension = dimension;
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.task = task;
        this.editable = editable;
        this.groups = Collections.unmodifiableMap(new HashMap<>(groups));
    }

    public static MaidAttackListPayload from(TaskConfigContainer menu, Map<ResourceLocation, MonsterType> groups,
        boolean editable) {
        var maid = menu.getMaid();
        return new MaidAttackListPayload(
            menu.windowId,
            menu.session(),
            maid.worldObj.provider.dimensionId,
            maid.getEntityId(),
            maid.getUniqueID(),
            menu.openedTask(),
            editable,
            groups);
    }

    public static boolean fits(Map<ResourceLocation, MonsterType> groups) {
        if (groups == null || groups.size() > MaidNetworkProtocol.MAX_ENTRIES) return false;
        int bytes = MaidNetworkProtocol.ATTACK_LIST_BYTES;
        for (var entry : groups.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) return false;
            String id = entry.getKey()
                .toString();
            if (id.length() > MaidNetworkProtocol.MAX_ID_BYTES || !id.matches(MaidNetworkProtocol.ID_A_Z0_9_A_Z0_9))
                return false;
            bytes += MaidNetworkProtocol.ATTACK_LIST_FITS_PARAM + id.length();
        }
        return bytes <= MaidNetworkProtocol.MAX_MAP_BYTES;
    }

    public void write(ByteBuf buffer) {
        buffer.writeInt(windowId)
            .writeLong(session)
            .writeInt(dimension)
            .writeInt(entityId)
            .writeLong(entityUuid.getMostSignificantBits())
            .writeLong(entityUuid.getLeastSignificantBits())
            .writeByte(task.ordinal())
            .writeBoolean(editable)
            .writeShort(groups.size());
        for (var entry : groups.entrySet()) {
            byte[] id = entry.getKey()
                .toString()
                .getBytes(StandardCharsets.US_ASCII);
            buffer.writeShort(id.length)
                .writeBytes(id)
                .writeByte(
                    entry.getValue()
                        .ordinal());
        }
    }

    public static MaidAttackListPayload read(ByteBuf buffer) {
        int window = buffer.readInt();
        long session = buffer.readLong();
        int dimension = buffer.readInt(), entityId = buffer.readInt();
        var uuid = new UUID(buffer.readLong(), buffer.readLong());
        int task = buffer.readUnsignedByte();
        boolean editable = buffer.readBoolean();
        int count = buffer.readUnsignedShort(), bytes = MaidNetworkProtocol.ATTACK_LIST_BYTES;
        if (task >= MaidTask.values().length || count > MaidNetworkProtocol.MAX_ENTRIES)
            throw new DecoderException(MaidNetworkProtocol.MSG_INVALID_ATTACK_LIST_HEADER);
        var groups = new HashMap<ResourceLocation, MonsterType>();
        for (int index = 0; index < count; index++) {
            int length = buffer.readUnsignedShort();
            bytes += length + MaidNetworkProtocol.ATTACK_LIST_FITS_PARAM;
            if (length < MaidNetworkProtocol.MIN_LENGTH_THRESHOLD || length > MaidNetworkProtocol.MAX_ID_BYTES
                || bytes > MaidNetworkProtocol.MAX_MAP_BYTES
                || length + 1 > buffer.readableBytes())
                throw new DecoderException(MaidNetworkProtocol.MSG_INVALID_ATTACK_LIST_IDENTIFIER_LENGTH);
            byte[] encoded = new byte[length];
            buffer.readBytes(encoded);
            String id = new String(encoded, StandardCharsets.US_ASCII);
            if (!id.matches(MaidNetworkProtocol.ID_A_Z0_9_A_Z0_9))
                throw new DecoderException(MaidNetworkProtocol.MSG_INVALID_ATTACK_LIST_IDENTIFIER);
            int type = buffer.readUnsignedByte();
            if (type >= MonsterType.values().length
                || groups.put(new ResourceLocation(id), MonsterType.values()[type]) != null)
                throw new DecoderException(MaidNetworkProtocol.MSG_INVALID_OR_REPEATED_ATTACK_LIST);
        }
        if (buffer.isReadable()) throw new DecoderException(MaidNetworkProtocol.MSG_UNEXPECTED_ATTACK_LIST_PAYLOAD);
        return new MaidAttackListPayload(
            window,
            session,
            dimension,
            entityId,
            uuid,
            MaidTask.values()[task],
            editable,
            groups);
    }

    public boolean matchesIdentity(TaskConfigContainer menu) {
        var maid = menu.getMaid();
        return menu.windowId == windowId && menu.session() == session
            && maid.worldObj.provider.dimensionId == dimension
            && maid.getEntityId() == entityId
            && maid.getUniqueID()
                .equals(entityUuid);
    }

    public MaidTask task() {
        return task;
    }

    public boolean editable() {
        return editable;
    }

    public Map<ResourceLocation, MonsterType> groups() {
        return groups;
    }
}
