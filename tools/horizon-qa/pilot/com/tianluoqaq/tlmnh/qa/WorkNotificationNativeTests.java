package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionEntry;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionIds;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class WorkNotificationNativeTests {

    private WorkNotificationNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "work_notifications")
    public static void everyMissingConditionUsesMaidNamedOwnerChatWithChangedSignatureAndExactCooldown(
        GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var server = MinecraftServer.getServer();
        var players = server.getConfigurationManager().playerEntityList;
        h.assertTrue(!players.contains(f.owner), "Fixture player was already registered as online");
        players.add(f.owner);
        h.afterTest(() -> players.removeIf(player -> player == f.owner));
        var channel = (EmbeddedChannel) f.owner.playerNetServerHandler.netManager.channel();
        drain(channel);
        var maid = new EntityMaid(h.getWorld());
        f.owned.add(maid);
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Maid binding failed");
        maid.setCustomNameTag("[QA] Work Reporter");
        var notifier = new MaidMissingConditionNotifier();
        int reports = 0;
        for (var code : MissingConditionCode.values()) {
            var missing = Collections.singletonList(MissingConditionEntry.of(code));
            h.assertTrue(
                notifier
                    .reportMissingForMaid(maid, MaidTask.CROPSNH_BREEDING, MissingConditionIds.MODE_BREEDING, missing),
                "Changed missing condition was not reported: " + code);
            h.assertTrue(
                !notifier
                    .reportMissingForMaid(maid, MaidTask.CROPSNH_BREEDING, MissingConditionIds.MODE_BREEDING, missing),
                "Repeated condition bypassed cooldown: " + code);
            var messages = drain(channel);
            h.assertTrue(
                messages.size() == 1 && messages.get(0)
                    .contains("[QA] Work Reporter")
                    && messages.get(0)
                        .contains(code.translationKey()),
                "Actual owner chat lost maid identity or condition: " + code);
            reports++;
        }
        var missing = Collections
            .singletonList(MissingConditionEntry.of(MissingConditionCode.MISSING_GT_WRENCH_OFFHAND));
        long start = h.getWorld()
            .getTotalWorldTime();
        notifier.reset();
        h.assertTrue(
            notifier.evaluateAndNotify(
                start,
                f.owner.getUniqueID(),
                maid.getCustomNameTag(),
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                missing,
                null),
            "First reminder missing");
        h.assertTrue(
            !notifier.evaluateAndNotify(
                start + 1199,
                f.owner.getUniqueID(),
                maid.getCustomNameTag(),
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                missing,
                null),
            "Reminder fired before 1200 ticks");
        h.assertTrue(
            notifier.evaluateAndNotify(
                start + 1200,
                f.owner.getUniqueID(),
                maid.getCustomNameTag(),
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                missing,
                null),
            "Reminder missing at exactly 1200 ticks");
        h.assertTrue(drain(channel).size() == 2, "Cooldown path did not use the real owner chat sink");
        h.assertTrue(notifier.reportHealthy(), "Healthy state did not reset missing signature");
        h.assertTrue(
            notifier
                .reportMissingForMaid(maid, MaidTask.MAINTENANCE_HATCH, MissingConditionIds.MODE_MAINTENANCE, missing),
            "Recovered then missing condition did not report immediately");
        h.assertTrue(drain(channel).size() == 1, "Recovered state emitted the wrong number of packets");
        players.removeIf(player -> player == f.owner);
        notifier.reset();
        h.assertTrue(
            !notifier
                .reportMissingForMaid(maid, MaidTask.MAINTENANCE_HATCH, MissingConditionIds.MODE_MAINTENANCE, missing),
            "Offline delivery was incorrectly acknowledged");
        players.add(f.owner);
        h.assertTrue(
            notifier
                .reportMissingForMaid(maid, MaidTask.MAINTENANCE_HATCH, MissingConditionIds.MODE_MAINTENANCE, missing),
            "Failed offline delivery consumed the reminder");
        h.assertTrue(drain(channel).size() == 1, "Reconnected owner did not receive the pending reminder");
        System.out.println(
            "TLMNH_WORK_NOTIFICATIONS_PASS conditions=" + reports
                + " cooldown=virtual-1200 realServerSink=true realClientDelivery=false");
        h.succeed();
    }

    private static List<String> drain(EmbeddedChannel channel) {
        channel.runPendingTasks();
        var messages = new ArrayList<String>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            try {
                if (packet instanceof S02PacketChat chat) {
                    var bytes = new PacketBuffer(Unpooled.buffer());
                    try {
                        chat.writePacketData(bytes);
                        messages.add(bytes.readStringFromBuffer(32767));
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException("Cannot inspect actual outgoing chat packet", failure);
                    } finally {
                        bytes.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(packet);
            }
        }
        return messages;
    }
}
