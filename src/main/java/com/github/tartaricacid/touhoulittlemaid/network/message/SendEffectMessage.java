/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: network/message/SendEffectMessage.java, MIT
 * Adaptation: bounded legacy FML message, original effect data and exact open-window identity
 */
package com.github.tartaricacid.touhoulittlemaid.network.message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public final class SendEffectMessage implements IMessage {

    private static final int MAX_EFFECTS = 128, MAX_KEY_BYTES = 128;
    private int dimension, entityId, windowId;
    private UUID entityUuid;
    private boolean invulnerable;
    private List<EffectData> effects = Collections.emptyList();

    public SendEffectMessage() {}

    public SendEffectMessage(EntityMaid maid, int windowId) {
        this(
            maid.worldObj.provider.dimensionId,
            maid.getEntityId(),
            maid.getUniqueID(),
            windowId,
            maid.isEntityInvulnerable(),
            snapshot(maid));
    }

    public SendEffectMessage(int dimension, int entityId, UUID uuid, int windowId, boolean invulnerable,
        List<EffectData> effects) {
        this.dimension = dimension;
        this.entityId = entityId;
        this.entityUuid = uuid;
        this.windowId = windowId;
        this.invulnerable = invulnerable;
        this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        validate();
    }

    private static List<EffectData> snapshot(EntityMaid maid) {
        var values = new ArrayList<EffectData>();
        for (Object value : maid.getActivePotionEffects()) if (value instanceof PotionEffect effect) {
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) continue;
            values.add(
                new EffectData(id, Potion.potionTypes[id].getName(), effect.getAmplifier(), effect.getDuration(), 2));
        }
        return values;
    }

    private void validate() {
        if (entityUuid == null || entityId < 0 || windowId <= 0 || windowId > 255 || effects.size() > MAX_EFFECTS)
            throw new IllegalArgumentException("Invalid maid effect window");
        for (var effect : effects) {
            if (effect == null || effect.descriptionId == null
                || effect.descriptionId.isEmpty()
                || effect.descriptionId.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES
                || effect.amplifier < 0
                || effect.potionId < -1
                || effect.potionId > 65535
                || effect.duration < -1
                || effect.category < 0
                || effect.category > 2) throw new IllegalArgumentException("Invalid maid effect data");
        }
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        validate();
        buffer.writeInt(dimension)
            .writeInt(entityId)
            .writeLong(entityUuid.getMostSignificantBits())
            .writeLong(entityUuid.getLeastSignificantBits())
            .writeByte(windowId)
            .writeBoolean(invulnerable)
            .writeShort(effects.size());
        for (var effect : effects) {
            byte[] key = effect.descriptionId.getBytes(StandardCharsets.UTF_8);
            buffer.writeShort(key.length)
                .writeBytes(key)
                .writeInt(effect.amplifier)
                .writeInt(effect.duration)
                .writeByte(effect.category)
                .writeInt(effect.potionId);
        }
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        dimension = buffer.readInt();
        entityId = buffer.readInt();
        entityUuid = new UUID(buffer.readLong(), buffer.readLong());
        windowId = buffer.readUnsignedByte();
        invulnerable = buffer.readBoolean();
        int count = buffer.readUnsignedShort();
        if (count > MAX_EFFECTS) throw new IllegalArgumentException("Too many maid effects");
        var loaded = new ArrayList<EffectData>(count);
        for (int i = 0; i < count; i++) {
            int size = buffer.readUnsignedShort();
            if (size == 0 || size > MAX_KEY_BYTES || buffer.readableBytes() < size + 13)
                throw new IllegalArgumentException("Invalid maid effect key");
            byte[] key = new byte[size];
            buffer.readBytes(key);
            String description = new String(key, StandardCharsets.UTF_8);
            int amplifier = buffer.readInt(), duration = buffer.readInt(), category = buffer.readUnsignedByte();
            loaded.add(new EffectData(buffer.readInt(), description, amplifier, duration, category));
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing maid effect data");
        effects = Collections.unmodifiableList(loaded);
        validate();
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

    public int windowId() {
        return windowId;
    }

    public boolean invulnerable() {
        return invulnerable;
    }

    public List<EffectData> effects() {
        return effects;
    }

    public static final class EffectData {

        public final String descriptionId;
        public final int potionId, amplifier, duration, category;

        public EffectData(String key, int amplifier, int duration, int category) {
            this(-1, key, amplifier, duration, category);
        }

        public EffectData(int potionId, String key, int amplifier, int duration, int category) {
            this.potionId = potionId;
            this.descriptionId = key;
            this.amplifier = amplifier;
            this.duration = duration;
            this.category = category;
        }
    }
}
