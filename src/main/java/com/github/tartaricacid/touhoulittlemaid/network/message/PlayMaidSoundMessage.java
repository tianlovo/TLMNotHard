/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: network/message/PlayMaidSoundMessage.java, MIT
 * Adaptation: bounded builtin indices, world/UUID identity and legacy FML client dispatch
 */
package com.github.tartaricacid.touhoulittlemaid.network.message;

import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public final class PlayMaidSoundMessage implements IMessage {

    private int dimension, entityId, packIndex, eventIndex;
    private UUID entityUuid;

    public PlayMaidSoundMessage() {}

    public PlayMaidSoundMessage(EntityMaid maid, String event) {
        this(maid.worldObj.provider.dimensionId, maid.getEntityId(), maid.getUniqueID(), maid.getSoundPackId(), event);
    }

    public PlayMaidSoundMessage(int dimension, int entityId, UUID uuid, String pack, String event) {
        this.dimension = dimension;
        this.entityId = entityId;
        this.entityUuid = uuid;
        this.packIndex = -1;
        for (int i = 0; i < BuiltinSoundCatalog.packs()
            .size(); i++)
            if (BuiltinSoundCatalog.packs()
                .get(i).id.equals(pack)) packIndex = i;
        this.eventIndex = BuiltinSoundCatalog.events()
            .indexOf(event);
        validate();
    }

    private void validate() {
        if (entityId < 0 || entityUuid == null
            || packIndex < 0
            || packIndex >= BuiltinSoundCatalog.packs()
                .size()
            || eventIndex < 0
            || eventIndex >= BuiltinSoundCatalog.events()
                .size())
            throw new IllegalArgumentException("Invalid builtin maid sound message");
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        dimension = buffer.readInt();
        entityId = buffer.readInt();
        entityUuid = new UUID(buffer.readLong(), buffer.readLong());
        packIndex = buffer.readUnsignedByte();
        eventIndex = buffer.readUnsignedByte();
        validate();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        validate();
        buffer.writeInt(dimension)
            .writeInt(entityId)
            .writeLong(entityUuid.getMostSignificantBits())
            .writeLong(entityUuid.getLeastSignificantBits())
            .writeByte(packIndex)
            .writeByte(eventIndex);
    }

    public int dimension() {
        return dimension;
    }

    public int entityId() {
        return entityId;
    }

    public UUID entityUuid() {
        return entityUuid;
    }

    public String pack() {
        return BuiltinSoundCatalog.packs()
            .get(packIndex).id;
    }

    public String event() {
        return BuiltinSoundCatalog.events()
            .get(eventIndex);
    }
}
