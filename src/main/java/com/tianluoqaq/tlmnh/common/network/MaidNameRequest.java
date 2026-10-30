/*
 * Copyright (c) 2019-2025 tartaric_acid, for the code part
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: network/message/SendNameTagMessage.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: bounded UTF-8 codec, window/session/UUID binding; authority lives in the server container
 */
package com.tianluoqaq.tlmnh.common.network;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/** v1仅发送名称请求；没有客户端修改女仆数据的入口 */
public final class MaidNameRequest implements IMessage {

    private static final int MAX_ENCODED_LENGTH = MaidNameRules.MAX_LENGTH * 4;
    private int windowId, entityId;
    private long session;
    private UUID maidId;
    private String name;
    private boolean alwaysShow;

    public MaidNameRequest() {}

    public MaidNameRequest(int windowId, long session, int entityId, UUID maidId, String name, boolean alwaysShow) {
        this.windowId = windowId;
        this.session = session;
        this.entityId = entityId;
        this.maidId = maidId;
        this.name = name;
        this.alwaysShow = alwaysShow;
    }

    public static MaidNameRequest from(ContainerMaidNaming container, String name, boolean alwaysShow) {
        var maid = container.getMaid();
        return new MaidNameRequest(
            container.windowId,
            container.getSession(),
            maid.getEntityId(),
            maid.getUniqueID(),
            name,
            alwaysShow);
    }

    public boolean matches(ContainerMaidNaming container) {
        var maid = container.getMaid();
        return windowId == container.windowId && session == container.getSession()
            && entityId == maid.getEntityId()
            && maid.getUniqueID()
                .equals(maidId);
    }

    public String getName() {
        return name;
    }

    public boolean isAlwaysShow() {
        return alwaysShow;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        windowId = buffer.readInt();
        session = buffer.readLong();
        entityId = buffer.readInt();
        maidId = new UUID(buffer.readLong(), buffer.readLong());
        int length = buffer.readUnsignedShort();
        if (length > MAX_ENCODED_LENGTH || length > buffer.readableBytes() - 1)
            throw new DecoderException("Invalid maid name byte length");
        var bytes = new byte[length];
        buffer.readBytes(bytes);
        try {
            name = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new DecoderException("Invalid maid name UTF-8", error);
        }
        if (name.length() > MaidNameRules.MAX_LENGTH) throw new DecoderException("Maid name is too long");
        alwaysShow = buffer.readBoolean();
        if (buffer.isReadable()) throw new DecoderException("Unexpected maid name payload");
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        if (name == null || name.length() > MaidNameRules.MAX_LENGTH)
            throw new IllegalArgumentException("Maid name is too long");
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        buffer.writeInt(windowId)
            .writeLong(session)
            .writeInt(entityId)
            .writeLong(maidId.getMostSignificantBits())
            .writeLong(maidId.getLeastSignificantBits())
            .writeShort(bytes.length)
            .writeBytes(bytes)
            .writeBoolean(alwaysShow);
    }
}
