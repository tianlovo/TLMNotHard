package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/** Actual owner/container and native FML encoding; isolated memory owner has no remote-client dispatcher. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidEffectSyncTests {

    private MaidEffectSyncTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 25, batch = "maid_effect_signed_native")
    public static void nativeSignedEffectsOpenReopenAndBadExternalKeyOnlyStopsItsOwnSync(GameTestHelper helper) {
        var e = new ProjectileImpactTests.Environment(helper);
        var maid = e.f.maid;
        e.owner.setPosition(maid.posX + 1, maid.posY, maid.posZ);
        helper.afterTest(e.owner::closeScreen);
        var channel = NetworkRegistry.INSTANCE.getChannel("tlmnh_effect1", Side.SERVER);
        helper.assertTrue(
            channel != null && channel.pipeline()
                .get("fml:outbound") != null,
            "Existing native effect FML channel missing");
        var tap = new EncodedTap();
        String name = "tlmnh-qa-effect-" + maid.getEntityId();
        channel.pipeline()
            .addAfter("fml:outbound", name, tap);
        helper.afterTest(
            () -> {
                if (channel.pipeline()
                    .get(name) == tap)
                    channel.pipeline()
                        .remove(name);
            });
        maid.addPotionEffect(new PotionEffect(Potion.resistance.id, 619, -3));
        maid.addPotionEffect(new PotionEffect(Potion.moveSpeed.id, 72000, 1));
        maid.addPotionEffect(new PotionEffect(Potion.fireResistance.id, -17, 0));
        var before = payload(maid);
        var first = open(helper, e);
        helper.assertTrue(before.equals(payload(maid)), "Opening GUI changed native effects or original inventory");
        helper.assertTrue(
            tap.failure == null && tap.decoded.size() == 1,
            "Actual FML payload was not encoded once: " + tap.failure);
        var message = tap.decoded.get(0);
        identity(helper, message, e, first.windowId);
        helper.assertTrue(
            message.effects()
                .size() == 3 && value(message, Potion.resistance.id, -3, 619)
                && value(message, Potion.fireResistance.id, 0, -17)
                && value(message, Potion.moveSpeed.id, 1, 72000),
            "Actual native signed effects were clamped, omitted or changed in the full encoded packet");
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                e.owner.closeScreen();
                helper.assertTrue(!maid.guiOpening, "Native close did not release original GUI ownership");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                var unchanged = payload(maid);
                var second = open(helper, e);
                helper.assertTrue(
                    second != first && second.windowId != first.windowId,
                    "Reopened native container did not establish a fresh window");
                helper.assertTrue(unchanged.equals(payload(maid)), "Reopening changed native effects or inventory");
                helper.assertTrue(
                    tap.failure == null && tap.decoded.size() == 2,
                    "Reopening failed its actual native effect sync: " + tap.failure);
                var sent = tap.decoded.get(1);
                identity(helper, sent, e, second.windowId);
                helper.assertTrue(
                    sent.effects()
                        .stream()
                        .anyMatch(v -> v.potionId == Potion.resistance.id && v.amplifier == -3),
                    "Later native ticks/reopen dropped or rewrote the real negative amplifier");
                e.owner.closeScreen();
                phase[0] = 2;
                return false;
            }
            int id = -1;
            // Hodgepodge masks PotionEffect constructor IDs to an unsigned byte, even with an expanded potion array.
            for (int i = Math.min(Potion.potionTypes.length - 1, 255); i > 23; i--) if (Potion.potionTypes[i] == null) {
                id = i;
                break;
            }
            helper.assertTrue(id >= 0, "No empty native potion slot for one owned malformed-addon input");
            var bad = new BadKeyPotion(id);
            helper.afterTest(() -> {
                try {
                    maid.removePotionEffect(bad.id);
                } finally {
                    if (Potion.potionTypes[bad.id] == bad) Potion.potionTypes[bad.id] = null;
                }
            });
            var badEffect = new PotionEffect(bad.id, 100, 0);
            helper.assertTrue(
                bad.id == id && Potion.potionTypes[id] == bad && badEffect.getPotionID() == id,
                "Malformed-addon fixture did not preserve its registered native potion ID");
            maid.addPotionEffect(badEffect);
            var unchanged = payload(maid);
            var menu = open(helper, e);
            helper.assertTrue(
                menu.canInteractWith(e.owner) && unchanged.equals(payload(maid))
                    && tap.decoded.size() == 2
                    && tap.failure == null
                    && IntegrationGuard.faults(Side.SERVER)
                        .containsKey("network/maid_effects/" + maid.getUniqueID()),
                "Malformed external effect key escaped the local sync guard, changed native data or broke GUI access");
            helper.assertTrue(
                IntegrationGuard.faults(Side.SERVER)
                    .size() == 1,
                "Malformed external key disabled unrelated maid capabilities");
            e.owner.closeScreen();
            System.out.println(
                "TLMNH_NATIVE_EFFECT_SYNC_PASS signedAmplifier=-3 signedDuration=-17 nativeWindows=3 encodedPayloads=2 guard=effect-only remoteClient=false");
            return true;
        });
    }

    private static AbstractMaidContainer open(GameTestHelper helper, ProjectileImpactTests.Environment e) {
        helper.assertTrue(
            e.f.maid.interact(e.owner) && e.owner.openContainer instanceof AbstractMaidContainer,
            "Actual native owner interaction did not open original maid container");
        return (AbstractMaidContainer) e.owner.openContainer;
    }

    private static void identity(GameTestHelper helper, SendEffectMessage message, ProjectileImpactTests.Environment e,
        int window) {
        helper.assertTrue(
            message.entityUuid()
                .equals(e.f.maid.getUniqueID()) && message.entityId() == e.f.maid.getEntityId()
                && message.windowId() == window
                && message.dimension() == e.f.maid.dimension,
            "Encoded effect payload changed actual window/entity/world identity");
    }

    private static boolean value(SendEffectMessage message, int id, int amplifier, int duration) {
        return message.effects()
            .stream()
            .anyMatch(v -> v.potionId == id && v.amplifier == amplifier && v.duration == duration);
    }

    private static NBTTagCompound payload(EntityMaid maid) {
        var all = new NBTTagCompound();
        maid.writeToNBT(all);
        var owned = new NBTTagCompound();
        for (String key : new String[] { "ActiveEffects", "MaidInventory", "Equipment", "TLMNHOffhand" })
            if (all.hasKey(key)) owned.setTag(
                key,
                all.getTag(key)
                    .copy());
        return owned;
    }

    private static final class BadKeyPotion extends Potion {

        BadKeyPotion(int id) {
            super(id, false, 0);
            setPotionName("");
        }
    }

    private static final class EncodedTap extends ChannelOutboundHandlerAdapter {

        final List<SendEffectMessage> decoded = new ArrayList<>();
        String failure;

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try {
                if (message instanceof FMLProxyPacket packet && packet.channel()
                    .equals("tlmnh_effect1")) {
                    var bytes = packet.payload()
                        .copy();
                    try {
                        if (bytes.readUnsignedByte() != 0)
                            throw new IllegalStateException("Unexpected effect discriminator");
                        var value = new SendEffectMessage();
                        value.fromBytes(bytes);
                        decoded.add(value);
                    } finally {
                        bytes.release();
                    }
                }
            } catch (Exception | LinkageError | AssertionError error) {
                failure = error.toString();
            } finally {
                context.write(message, promise);
            }
        }
    }
}
