package com.tianluoqaq.tlmnh.common.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** 服务端编辑副本，与客户端提交使用独立消息类型和方向 */
public final class MaidAttackSnapshot implements IMessage {

    private MaidAttackListPayload payload;

    public MaidAttackSnapshot() {}

    public MaidAttackSnapshot(MaidAttackListPayload payload) {
        this.payload = payload;
    }

    public MaidAttackListPayload payload() {
        return payload;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        payload = MaidAttackListPayload.read(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        payload.write(buffer);
    }
}
