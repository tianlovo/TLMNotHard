package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 只验证实际资源的定位结构和只读边界，不替代真实动画或绘制采样 */
public class MaidHandAttachmentTest {

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void allThirtyFiveWinefoxChoicesHaveIndependentReadableHands() throws Exception {
        var models = resources();
        int gecko = 0, bedrock = 0;
        for (var choice : BuiltinModelCatalog.choices()) {
            if (!choice.isWinefox()) continue;
            var model = models.create(choice);
            if (choice.isGecko()) gecko++;
            else bedrock++;
            for (var side : Side.values()) {
                var attachment = MaidHandAttachment.capture(model, side);
                assertEquals(choice.getId(), MaidHandAttachment.Status.READY, attachment.status);
                assertEquals(choice.getId(), attachment.modelId);
                assertTrue(choice.getId(), MaidHeldItemTransforms.finite(attachment.getSourceMatrix()));
                assertFalse(attachment.boneChain.isEmpty());
                String expected = choice.isGecko() ? (side == Side.LEFT ? "LeftHandLocator" : "RightHandLocator")
                    : (side == Side.LEFT ? "armLeft" : "armRight");
                assertEquals(expected, attachment.boneChain.get(attachment.boneChain.size() - 1));
            }
        }
        assertEquals(33, gecko);
        assertEquals(2, bedrock);
    }

    @Test
    public void returnedMatricesCannotModifyTheModelOrSavedSnapshot() throws Exception {
        var model = resources().create(BuiltinModelCatalog.get("geckolib:winefox"));
        var attachment = MaidHandAttachment.capture(model, Side.LEFT);
        var before = attachment.getSourceValues();
        attachment.getSourceMatrix()
            .translate(100, 200, 300);
        var values = attachment.getSourceValues();
        values[12] += 100;
        assertArrayEquals(before, attachment.getSourceValues(), 0);
        assertArrayEquals(
            before,
            MaidHandAttachment.capture(model, Side.LEFT)
                .getSourceValues(),
            0);
        assertThrows(UnsupportedOperationException.class, () -> attachment.boneChain.clear());
    }

    private static BuiltinMaidModels resources() {
        return new BuiltinMaidModels(
            location -> MaidHandAttachmentTest.class
                .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath()));
    }
}
