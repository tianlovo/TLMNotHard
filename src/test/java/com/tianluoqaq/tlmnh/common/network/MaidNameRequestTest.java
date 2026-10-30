package com.tianluoqaq.tlmnh.common.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

public class MaidNameRequestTest {

    @Test
    public void chineseNameAndVisibilitySurviveActualByteBufferCodec() {
        var bytes = Unpooled.buffer();
        try {
            var request = new MaidNameRequest(99, Long.MIN_VALUE + 17, 478, UUID.randomUUID(), "酒狐 小满", true);
            request.toBytes(bytes);
            var decoded = new MaidNameRequest();
            decoded.fromBytes(bytes);
            assertEquals("酒狐 小满", decoded.getName());
            assertTrue(decoded.isAlwaysShow());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void malformedOversizedOrTrailingPayloadNeverReachesTheServerAction() {
        for (int kind = 0; kind < 4; kind++) {
            var bytes = Unpooled.buffer();
            try {
                bytes.writeInt(1)
                    .writeLong(1)
                    .writeInt(1)
                    .writeLong(0)
                    .writeLong(1);
                if (kind == 0) bytes.writeShort(129);
                else if (kind == 1) bytes.writeShort(1)
                    .writeByte(0xC0)
                    .writeBoolean(false);
                else if (kind == 2) bytes.writeShort(33)
                    .writeBytes(new byte[33])
                    .writeBoolean(false);
                else bytes.writeShort(1)
                    .writeByte('a')
                    .writeBoolean(false)
                    .writeByte(1);
                assertThrows(DecoderException.class, () -> new MaidNameRequest().fromBytes(bytes));
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void validationPreservesChineseAndRefusesBlankControlsFormattingAndBrokenUnicode() {
        assertTrue(MaidNameRules.isValid("酒狐 小满"));
        assertTrue(MaidNameRules.isValid("12345678901234567890123456789012"));
        assertTrue(MaidNameRules.isValid("酒狐\uD83E\uDD8A"));
        for (String invalid : new String[] { "", "  ", "\u3000", "酒狐\n小满", "\u00A7c酒狐", "\u0000", "\uD83E", "\uDD8A",
            "123456789012345678901234567890123" }) assertFalse(invalid, MaidNameRules.isValid(invalid));
    }
}
