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

    public static final int MAX_ENTRIES = 1024, MAX_MAP_BYTES = 28000, MAX_ID_BYTES = 256;
    private final int windowId, entityId, dimension;
    private final long session;
    private final UUID entityUuid;
    private final MaidTask task;
    private final boolean editable;
    private final Map<ResourceLocation, MonsterType> groups;

    private MaidAttackListPayload(int windowId, long session, int dimension, int entityId, UUID entityUuid,
        MaidTask task, boolean editable, Map<ResourceLocation, MonsterType> groups) {
        if (!fits(groups)) throw new IllegalArgumentException("Attack list exceeds packet limits");
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
        if (groups == null || groups.size() > MAX_ENTRIES) return false;
        int bytes = 2;
        for (var entry : groups.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) return false;
            String id = entry.getKey()
                .toString();
            if (id.length() > MAX_ID_BYTES || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) return false;
            bytes += 3 + id.length();
        }
        return bytes <= MAX_MAP_BYTES;
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
        int count = buffer.readUnsignedShort(), bytes = 2;
        if (task >= MaidTask.values().length || count > MAX_ENTRIES)
            throw new DecoderException("Invalid attack list header");
        var groups = new HashMap<ResourceLocation, MonsterType>();
        for (int index = 0; index < count; index++) {
            int length = buffer.readUnsignedShort();
            bytes += length + 3;
            if (length < 1 || length > MAX_ID_BYTES || bytes > MAX_MAP_BYTES || length + 1 > buffer.readableBytes())
                throw new DecoderException("Invalid attack list identifier length");
            byte[] encoded = new byte[length];
            buffer.readBytes(encoded);
            String id = new String(encoded, StandardCharsets.US_ASCII);
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new DecoderException("Invalid attack list identifier");
            int type = buffer.readUnsignedByte();
            if (type >= MonsterType.values().length
                || groups.put(new ResourceLocation(id), MonsterType.values()[type]) != null)
                throw new DecoderException("Invalid or repeated attack list entry");
        }
        if (buffer.isReadable()) throw new DecoderException("Unexpected attack list payload");
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
