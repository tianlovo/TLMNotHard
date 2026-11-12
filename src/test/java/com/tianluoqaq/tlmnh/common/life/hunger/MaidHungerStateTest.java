package com.tianluoqaq.tlmnh.common.life.hunger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.life.hunger.spi.HungerPolicyCompatProvider;

/** 验证女仆饥饿状态机的耗竭折算顺序、旧存档无损迁移以及未来/损坏标签安全暂停保护 */
public class MaidHungerStateTest {

    @Test
    public void exhaustionDrainsSaturationBeforeFoodAndClampsBounds() {
        var state = new MaidHungerState();
        assertEquals(20, state.getFood());
        assertEquals(5.0F, state.getSaturation(), 0.0001F);

        state.addExhaustion(4.5F);
        assertEquals(4.5F, state.getExhaustion(), 0.0001F);

        state.setFood(15);
        state.setSaturation(1.0F);
        state.addStats(10, 0.5F);
        assertEquals(20, state.getFood());
        assertEquals(11.0F, state.getSaturation(), 0.0001F);

        state.setFood(6);
        assertEquals(6.0F, state.getSaturation(), 0.0001F);
    }

    @Test
    public void legacyArchivePreservesUnactivatedSnapshotAndBacksUpLegacyMaidHungerOnceActivated() {
        var legacyRoot = new NBTTagCompound();
        legacyRoot.setInteger(MaidHungerNbtKeys.LEGACY_HUNGER, 7);

        var state = new MaidHungerState();
        state.readFromNBT(legacyRoot);
        assertEquals(20, state.getFood());

        var untouchedOut = (NBTTagCompound) legacyRoot.copy();
        state.writeToNBT(untouchedOut);
        assertFalse(untouchedOut.hasKey(MaidHungerNbtKeys.ROOT_TAG));
        assertFalse(untouchedOut.hasKey(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP));
        assertEquals(legacyRoot, untouchedOut);

        state.addExhaustion(1.0F);
        var activeOut = new NBTTagCompound();
        state.writeToNBT(activeOut);
        assertTrue(activeOut.hasKey(MaidHungerNbtKeys.ROOT_TAG));
        assertEquals(7, activeOut.getInteger(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP));
        assertEquals(20, activeOut.getInteger(MaidHungerNbtKeys.LEGACY_HUNGER));

        var reloaded = new MaidHungerState();
        reloaded.readFromNBT(activeOut);
        assertFalse(reloaded.isPaused());
        assertEquals(1.0F, reloaded.getExhaustion(), 0.0001F);
    }

    @Test
    public void futureSchemaOrMalformedPayloadEntersPausedModeAndPreservesRawTag() {
        var futureState = new NBTTagCompound();
        futureState.setInteger(MaidHungerNbtKeys.VERSION, 99);
        futureState.setInteger(MaidHungerNbtKeys.FOOD, 12);
        futureState.setString("FutureCustomField", "keep-me");

        var root = new NBTTagCompound();
        root.setTag(MaidHungerNbtKeys.ROOT_TAG, futureState);

        var state = new MaidHungerState();
        state.readFromNBT(root);
        assertTrue(state.isPaused());

        state.addExhaustion(20.0F);
        state.setFood(3);

        var saved = new NBTTagCompound();
        state.writeToNBT(saved);
        assertEquals(futureState, saved.getCompoundTag(MaidHungerNbtKeys.ROOT_TAG));
    }

    @Test
    public void vanillaStarvePolicyRespectsDifficultyFloors() {
        assertEquals(0.0F, HungerPolicyCompatProvider.NOOP.computeStarveDamage(null, 20.0F), 0.0001F);
    }

    @Test
    public void oldTypedMirrorSurvivesHostWriterUntilActivation() {
        var input = new NBTTagCompound();
        input.setByte(MaidHungerNbtKeys.LEGACY_HUNGER, (byte) 0);
        var state = new MaidHungerState();
        state.readFromNBT(input);
        var hostOutput = new NBTTagCompound();
        hostOutput.setInteger(MaidHungerNbtKeys.LEGACY_HUNGER, 20);
        state.writeToNBT(hostOutput);
        assertEquals(input, hostOutput);
        assertFalse(state.isActivated());
    }

    @Test
    public void saturationAboveFoodPausesAndLargeNutritionDoesNotOverflow() {
        var state = new MaidHungerState();
        state.setFood(1);
        state.addStats(Integer.MAX_VALUE, 1.0F);
        assertEquals(20, state.getFood());
        var root = new NBTTagCompound();
        state.writeToNBT(root);
        root.getCompoundTag(MaidHungerNbtKeys.ROOT_TAG)
            .setInteger(MaidHungerNbtKeys.FOOD, 1);
        var restored = new MaidHungerState();
        restored.readFromNBT(root);
        assertTrue(restored.isPaused());
    }

}
