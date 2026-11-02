/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: network/message/SetAttackListMessage.java, MIT
 * Adaptation: bounded native packet and server-owned editor session instead of entity ID alone
 */
package com.github.tartaricacid.touhoulittlemaid.network.message;

import com.tianluoqaq.tlmnh.common.network.MaidAttackListPayload;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public final class SetAttackListMessage implements IMessage {

    private MaidAttackListPayload payload;
    private int nextAction = -1;

    public SetAttackListMessage() {}

    public SetAttackListMessage(MaidAttackListPayload payload) {
        this(payload, -1);
    }

    public SetAttackListMessage(MaidAttackListPayload payload, int nextAction) {
        this.payload = payload;
        this.nextAction = nextAction;
    }

    public MaidAttackListPayload payload() {
        return payload;
    }

    public int nextAction() {
        return nextAction;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        nextAction = buffer.readByte();
        payload = MaidAttackListPayload.read(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(nextAction);
        payload.write(buffer);
    }
}
