package com.tianluoqaq.tlmnh.common.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage.EffectData;

import io.netty.buffer.Unpooled;

public class SendEffectMessageTest {

    @Test
    public void identityAndUntruncatedDurationRoundtrip() {
        var uuid = UUID.fromString("57788131-014c-46b4-8f18-cab5ca9bf42c");
        var input = new SendEffectMessage(
            7337,
            42,
            uuid,
            7,
            true,
            Arrays
                .asList(new EffectData(1, "potion.moveSpeed", 1, 72000, 0), new EffectData("potion.poison", 2, -1, 1)));
        var buffer = Unpooled.buffer();
        try {
            input.toBytes(buffer);
            var output = new SendEffectMessage();
            output.fromBytes(buffer);
            assertEquals(7337, output.dimension());
            assertEquals(42, output.entityId());
            assertEquals(uuid, output.entityUuid());
            assertEquals(7, output.windowId());
            assertTrue(output.invulnerable());
            assertEquals(
                1,
                output.effects()
                    .get(0).potionId);
            assertEquals(
                72000,
                output.effects()
                    .get(0).duration);
            assertEquals(
                "potion.moveSpeed",
                output.effects()
                    .get(0).descriptionId);
            assertEquals(
                -1,
                output.effects()
                    .get(1).duration);
            assertEquals(0, buffer.readableBytes());
            assertThrows(
                UnsupportedOperationException.class,
                () -> output.effects()
                    .clear());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void invalidCountsAndTruncatedRecordsAreRejected() {
        var buffer = Unpooled.buffer();
        try {
            new SendEffectMessage(
                0,
                1,
                UUID.randomUUID(),
                1,
                false,
                Arrays.asList(new EffectData("potion.poison", 0, 30, 1))).toBytes(buffer);
            buffer.setShort(26, 129);
            assertThrows(IllegalArgumentException.class, () -> new SendEffectMessage().fromBytes(buffer));
            buffer.setShort(26, 1)
                .readerIndex(0);
            buffer.setShort(28, 129);
            assertThrows(IllegalArgumentException.class, () -> new SendEffectMessage().fromBytes(buffer));
            buffer.setShort(28, 13)
                .readerIndex(0);
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(IllegalArgumentException.class, () -> new SendEffectMessage().fromBytes(buffer));
        } finally {
            buffer.release();
        }
    }
}
