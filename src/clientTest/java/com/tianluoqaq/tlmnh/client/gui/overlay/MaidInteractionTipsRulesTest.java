package com.tianluoqaq.tlmnh.client.gui.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MaidInteractionTipsRulesTest {

    @Test
    public void ordinaryOwnedFirstPersonInteractionIsVisible() {
        assertTrue(MaidInteractionTipsRules.visible(true, true, false, true, true, true, false));
    }

    @Test
    public void spectatorThirdPersonAndHiddenHudDoNotShowInteractionHints() {
        assertFalse(MaidInteractionTipsRules.visible(true, true, true, true, true, true, false));
        assertFalse(MaidInteractionTipsRules.visible(false, true, false, true, true, true, false));
        assertFalse(MaidInteractionTipsRules.visible(true, false, false, true, true, true, false));
    }

    @Test
    public void foreignDeadOffhandAndSharedVehicleTargetsDoNotShowHints() {
        assertFalse(MaidInteractionTipsRules.visible(true, true, false, true, false, true, false));
        assertFalse(MaidInteractionTipsRules.visible(true, true, false, false, true, true, false));
        assertFalse(MaidInteractionTipsRules.visible(true, true, false, true, true, false, false));
        assertFalse(MaidInteractionTipsRules.visible(true, true, false, true, true, true, true));
    }
}
