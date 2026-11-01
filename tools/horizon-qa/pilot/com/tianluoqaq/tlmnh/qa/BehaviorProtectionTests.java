package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
import com.tianluoqaq.tlmnh.mixin.MixinEntityNavigator;

import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 故障从真实实体tick进入，验证边界、单次清理、物品所有权与玩家通知 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class BehaviorProtectionTests {

    private BehaviorProtectionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "behavior_protection_callback")
    public static void callbackAndCleanupFailureStayLocalAndPreserveItems(GameTestHelper helper) {
        scenario(helper, Phase.CALLBACK);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "behavior_protection_navigation")
    public static void nativeNavigationFailureStopsDeferredBrain(GameTestHelper helper) {
        scenario(helper, Phase.NAVIGATION);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "behavior_protection_after_living")
    public static void afterLivingFailureCannotResumeThroughPersistentRead(GameTestHelper helper) {
        scenario(helper, Phase.AFTER_LIVING);
    }

    private enum Phase {
        CALLBACK,
        NAVIGATION,
        AFTER_LIVING
    }

    private static void scenario(GameTestHelper helper, Phase phase) {
        IntegrationGuard.resetForDevelopment(Side.SERVER);
        helper.afterTest(() -> IntegrationGuard.resetForDevelopment(Side.SERVER));
        var fixture = new ModernCoreTests.Fixture(helper, false);
        var maid = fixture.maid;
        maid.setPosition(fixture.x + 3.5, fixture.y + 4, fixture.z + 4.5);
        double initialY = maid.posY;
        var inventory = maid.getMaidInventory();
        var apple = new ItemStack(Items.golden_apple, 3, 1);
        apple.setStackDisplayName("boundary apple");
        apple.getTagCompound()
            .setString(
                "OpaqueData",
                UUID.randomUUID()
                    .toString());
        inventory.setInventorySlotContents(MaidInventory.OFF_HAND, apple);
        inventory.setInventorySlotContents(MaidInventory.MAIN_HAND, new ItemStack(Items.diamond, 2));
        inventory.setInventorySlotContents(2, new ItemStack(Items.emerald, 9));
        var expected = new ItemStack[inventory.getSizeInventory()];
        for (int slot = 0; slot < expected.length; slot++) {
            var stack = inventory.getStackInSlot(slot);
            expected[slot] = stack == null ? null : stack.copy();
        }

        var actions = new Actions(phase);
        maid.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(actions);
        var runtimeRef = new MaidPolicyRuntime[1];
        var oldTokenRef = new MaidPolicyContext.Token[1];
        var navigator = new BrokenNavigation(maid);

        var healthy = new EntityMaid(helper.getWorld());
        var healthyActions = new Actions(null);
        helper.afterTest(() -> {
            healthy.getBrainCoordinator()
                .setSuspended(true);
            healthy.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(healthy);
        });
        helper.assertTrue(healthy.bindTo(fixture.owner.getUniqueID()), "Healthy maid binding failed");
        healthy.setPickup(false);
        helper.spawnEntity(healthy, 7.5, 1, 4.5);
        healthy.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(healthyActions);
        healthyActions.arm();

        var server = MinecraftServer.getServer();
        var unrelated = new EntityPlayerMP(
            server,
            helper.getWorld(),
            new GameProfile(UUID.randomUUID(), "QA-Unrelated"),
            new ItemInWorldManager(helper.getWorld()));
        var network = new NetworkManager(false);
        var unrelatedChannel = new EmbeddedChannel(network);
        network.setConnectionState(EnumConnectionState.PLAY);
        new NetHandlerPlayServer(server, network, unrelated);
        helper.afterTest(() -> {
            unrelated.setDead();
            unrelatedChannel.finish();
            drain(unrelatedChannel);
        });
        // AI主人保留FakePlayer；同身份的未登记原生接收端仅验证真实S02包，不接入其他模组玩家登录流程
        var owner = new EntityPlayerMP(
            server,
            helper.getWorld(),
            fixture.owner.getGameProfile(),
            new ItemInWorldManager(helper.getWorld()));
        owner.setPosition(fixture.owner.posX, fixture.owner.posY, fixture.owner.posZ);
        owner.capabilities.disableDamage = true;
        var ownerNetwork = new NetworkManager(false);
        var ownerChannel = new EmbeddedChannel(ownerNetwork);
        ownerNetwork.setConnectionState(EnumConnectionState.PLAY);
        new NetHandlerPlayServer(server, ownerNetwork, owner);
        helper.afterTest(() -> {
            owner.setDead();
            ownerChannel.finish();
            drain(ownerChannel);
        });
        String key = ExternalFeature.BEHAVIOR_RUNTIME.id + "/" + maid.getUniqueID();
        boolean[] roundTripped = { false };
        helper.succeedWhen(() -> {
            if (maid.ticksExisted < 3) return false;
            if (!actions.armed) {
                actions.arm();
                runtimeRef[0] = maid.getBrainCoordinator()
                    .peekPolicyRuntime();
                oldTokenRef[0] = runtimeRef[0].getContext()
                    .getToken();
                if (phase == Phase.NAVIGATION) ((MixinEntityNavigator) maid).tlmnh$setNavigator(navigator);
                return false;
            }
            if (maid.ticksExisted < 12) return false;
            var runtime = runtimeRef[0];
            var oldToken = oldTokenRef[0];
            helper.assertTrue(
                runtime.isRetired() && !runtime.getContext()
                    .isCurrent(oldToken),
                "Fault left an old behavior authority active");
            helper.assertTrue(actions.cancelled == 1, "Failed cleanup was retried: " + actions.cancelled);
            helper.assertTrue(
                actions.ticks == (phase == Phase.NAVIGATION ? 0 : 1),
                "Native AI boundary retried a failed callback");
            helper.assertTrue(
                actions.afterLiving == (phase == Phase.AFTER_LIVING ? 1 : 0),
                "Deferred after-living callback ran after failure");
            helper.assertTrue(
                phase != Phase.NAVIGATION || navigator.calls == 1,
                "Native navigator was retried after API failure");
            helper.assertTrue(
                healthyActions.ticks > 5 && healthyActions.afterLiving > 5,
                "One maid fault stopped healthy maid ticks");
            helper.assertTrue(
                maid.posY < initialY - .5 && maid.isEntityAlive(),
                "Fallback froze vanilla falling or killed the maid");
            var fault = IntegrationGuard.faults(Side.SERVER)
                .get(key);
            helper.assertTrue(
                fault != null && fixture.owner.getUniqueID()
                    .equals(fault.owner),
                "Fault lost its entity scope or owner audience");
            helper.assertTrue(
                fault.cause == actions.failure || phase == Phase.NAVIGATION && fault.cause == navigator.failure,
                "Diagnostic replaced the original API exception");
            if (phase == Phase.CALLBACK) helper.assertTrue(
                fault.cause.getSuppressed().length == 1 && fault.cause.getSuppressed()[0] == actions.cleanupFailure,
                "Primary error lost failed cleanup evidence");
            verifyInventory(helper, maid, expected);
            if (!roundTripped[0]) {
                for (int cycle = 0; cycle < 3; cycle++) {
                    var saved = new NBTTagCompound();
                    maid.writeToNBT(saved);
                    maid.readFromNBT(saved);
                    verifyInventory(helper, maid, expected);
                }
                ProtectionEvents.warn(owner);
                ProtectionEvents.warn(owner);
                ProtectionEvents.warn(unrelated);
                int ownerWarnings = warnings(ownerChannel, fault.code);
                helper.assertTrue(
                    ownerWarnings == 1,
                    "Owner did not receive exactly one actual chat packet: " + ownerWarnings);
                helper.assertTrue(
                    warnings(unrelatedChannel, fault.code) == 0,
                    "Unrelated player received an entity fault warning");
                if (phase == Phase.NAVIGATION) {
                    for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 1, z, Blocks.water);
                    maid.setPosition(fixture.x + 3.5, fixture.y + 1.2, fixture.z + 4.5);
                }
                roundTripped[0] = true;
                return false;
            }
            if (maid.ticksExisted < 15) return false;
            if (phase == Phase.NAVIGATION) helper.assertFalse(
                pendingJump(maid),
                "Persistent read resumed a stopped prototype jump outside the AI boundary");
            helper.assertTrue(
                IntegrationGuard.faults(Side.SERVER)
                    .size() == 1,
                "An isolated behavior error cascaded into other faults");
            System.out.println(
                "TLMNH_BEHAVIOR_PROTECTION_PASS phase=" + phase
                    + " ticks="
                    + maid.ticksExisted
                    + " cleanup="
                    + actions.cancelled
                    + " healthyTicks="
                    + healthyActions.ticks
                    + " nbtCycles=3 ownerChat=1 unrelatedChat=0");
            return true;
        });
    }

    private static boolean pendingJump(EntityMaid maid) {
        try {
            var pending = maid.getAquaticController()
                .getClass()
                .getDeclaredField("pendingJump");
            pending.setAccessible(true);
            return pending.getBoolean(maid.getAquaticController());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void verifyInventory(GameTestHelper helper, EntityMaid maid, ItemStack[] expected) {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < expected.length; slot++) helper.assertTrue(
            ItemStack.areItemStacksEqual(expected[slot], inventory.getStackInSlot(slot)),
            "AI failure/NBT roundtrip changed slot " + slot);
        helper.assertTrue(
            inventory.recovery()
                .size() == 0,
            "Fallback duplicated items into recovery storage");
    }

    private static int warnings(EmbeddedChannel channel, String code) {
        channel.runPendingTasks();
        int warnings = 0;
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            try {
                if (packet instanceof S02PacketChat chat) {
                    var bytes = new PacketBuffer(Unpooled.buffer());
                    try {
                        chat.writePacketData(bytes);
                        if (bytes.readStringFromBuffer(32767)
                            .contains(code)) warnings++;
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    } finally {
                        bytes.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(packet);
            }
        }
        return warnings;
    }

    private static void drain(EmbeddedChannel channel) {
        Object message;
        while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
        while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
    }

    private static final class Actions implements MaidPolicyRuntime.AfterNavigationActions {

        final Phase phase;
        final LinkageError failure;
        final AssertionError cleanupFailure = new AssertionError("QA cleanup API failure");
        int ticks, afterLiving, cancelled;
        boolean armed;

        Actions(Phase phase) {
            this.phase = phase;
            failure = phase == Phase.AFTER_LIVING ? new NoSuchFieldError("QA removed post-living field")
                : new NoSuchMethodError("QA removed behavior callback");
        }

        void arm() {
            ticks = afterLiving = cancelled = 0;
            armed = true;
        }

        @Override
        public void tick(MaidPolicyContext.Token token) {
            if (!armed) return;
            ticks++;
            if (phase == Phase.CALLBACK) throw failure;
        }

        @Override
        public void afterLivingUpdate(MaidPolicyContext.Token token) {
            if (!armed) return;
            afterLiving++;
            if (phase == Phase.AFTER_LIVING) throw failure;
        }

        @Override
        public void cancel() {
            if (!armed) return;
            cancelled++;
            if (phase == Phase.CALLBACK) throw cleanupFailure;
        }
    }

    private static final class BrokenNavigation extends PathNavigate {

        int calls;
        final NoSuchMethodError failure = new NoSuchMethodError("QA removed native navigation API");

        BrokenNavigation(EntityMaid maid) {
            super(maid, maid.worldObj);
        }

        @Override
        public void onUpdateNavigation() {
            calls++;
            throw failure;
        }
    }
}
