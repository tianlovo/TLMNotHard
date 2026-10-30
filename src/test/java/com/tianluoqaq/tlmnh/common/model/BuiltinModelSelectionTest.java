package com.tianluoqaq.tlmnh.common.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C11PacketEnchantItem;

import org.junit.BeforeClass;
import org.junit.Test;

import io.netty.buffer.Unpooled;

public class BuiltinModelSelectionTest {

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void allChoicesSurviveTheActualSignedBytePacketCodec() throws Exception {
        assertEquals(
            254,
            BuiltinModelCatalog.choices()
                .size());
        for (int index = 0; index < BuiltinModelCatalog.choices()
            .size(); index++) {
            var buffer = new PacketBuffer(Unpooled.buffer());
            try {
                var outgoing = new C11PacketEnchantItem(37, BuiltinModelSelection.wireAction(index));
                outgoing.writePacketData(buffer);
                var incoming = new C11PacketEnchantItem();
                incoming.readPacketData(buffer);
                assertEquals(37, incoming.func_149539_c());
                assertEquals((byte) index, incoming.func_149537_d());
                assertEquals(
                    BuiltinModelCatalog.choices()
                        .get(index)
                        .getId(),
                    BuiltinModelSelection.choiceForAction(incoming.func_149537_d())
                        .getId());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void returnAndInvalidActionsCannotAliasAModel() {
        assertEquals(-2, BuiltinModelSelection.wireAction(BuiltinModelSelection.BACK));
        assertEquals(BuiltinModelSelection.BACK, BuiltinModelSelection.decodeAction(-2));
        assertNull(BuiltinModelSelection.choiceForAction(-2));
        assertNull(BuiltinModelSelection.choiceForAction(-1));
        for (int invalid : new int[] { -129, 128, 254, 255, 256, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertEquals(-1, BuiltinModelSelection.decodeAction(invalid));
            assertNull(BuiltinModelSelection.choiceForAction(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> BuiltinModelSelection.wireAction(255));
        assertThrows(IllegalArgumentException.class, () -> BuiltinModelSelection.wireAction(-1));
    }

    @Test
    public void filtersKeepGlobalIndicesForAllWinefoxAndExtraTextures() {
        var all = BuiltinModelSelection.matching(false, "", BuiltinModelCatalog.Choice::getName);
        assertEquals(254, all.size());
        var winefox = BuiltinModelSelection.matching(true, "", BuiltinModelCatalog.Choice::getName);
        assertEquals(35, winefox.size());
        assertEquals(35, new HashSet<>(winefox).size());
        int variants = 0;
        int previous = -1;
        for (int index : winefox) {
            assertTrue(index > previous);
            previous = index;
            var choice = BuiltinModelCatalog.choices()
                .get(index);
            assertTrue(choice.isWinefox());
            if (BuiltinModelSelection.extraTextureNumber(choice) > 0) variants++;
            assertEquals(
                choice.getId(),
                BuiltinModelSelection.choiceForAction(BuiltinModelSelection.wireAction(index))
                    .getId());
        }
        assertEquals(14, variants);
        assertEquals(
            35,
            BuiltinModelSelection.matching(false, "酒狐", choice -> choice.isWinefox() ? "酒狐" : "其他")
                .size());
        assertEquals(
            1,
            BuiltinModelSelection.matching(false, " GECKOLIB:WINEFOX_HANFU ", choice -> "")
                .size());
    }
}
