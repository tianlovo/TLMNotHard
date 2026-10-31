package com.tianluoqaq.tlmnh.common.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.UUID;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;

import io.netty.buffer.Unpooled;

public class PlayMaidSoundMessageTest {

    @Test
    public void boundedWireFormatRoundtripsTheBuiltinInventoryAndRejectsInvalidIndices() {
        var uuid = UUID.fromString("b8b81a72-3f25-4b30-a52b-94062c68b622");
        for (var pack : BuiltinSoundCatalog.packs()) for (String event : BuiltinSoundCatalog.events()) {
            var buffer = Unpooled.buffer();
            try {
                new PlayMaidSoundMessage(7337, 53, uuid, pack.id, event).toBytes(buffer);
                assertEquals(26, buffer.readableBytes());
                var copy = new PlayMaidSoundMessage();
                copy.fromBytes(buffer);
                assertEquals(7337, copy.dimension());
                assertEquals(53, copy.entityId());
                assertEquals(uuid, copy.entityUuid());
                assertEquals(pack.id, copy.pack());
                assertEquals(event, copy.event());
                assertEquals(0, buffer.readableBytes());
                buffer.setByte(24, 255)
                    .readerIndex(0);
                assertThrows(IllegalArgumentException.class, () -> new PlayMaidSoundMessage().fromBytes(buffer));
                buffer.setByte(24, 0)
                    .setByte(25, 255)
                    .readerIndex(0);
                assertThrows(IllegalArgumentException.class, () -> new PlayMaidSoundMessage().fromBytes(buffer));
            } finally {
                buffer.release();
            }
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> new PlayMaidSoundMessage(7337, 53, uuid, "untrusted:pack", "maid.mode.idle"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PlayMaidSoundMessage(7337, 53, uuid, "littlemaid_peco", "untrusted.event"));
    }
}
