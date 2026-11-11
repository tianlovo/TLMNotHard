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
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSettings;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 真实FML服务端开窗流程与独立原生玩家；内存通道没有真实客户端投递目标 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SleepingGuiTests {

    private SleepingGuiTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "sleep_gui_boundary")
    public static void asleepInteractionAndTabRequestsRefuseWhileWakeOpensAndStopsOnlyAcceptedNavigation(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        var path = f.installPath();
        var proxy = new CommonProxy();
        // 会话错误和持光标时拒绝，不能在返回null之前清掉原路径或注册GUI
        helper.assertTrue(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_DEFAULT_TASK_CONFIG,
                f.owner,
                helper.getWorld(),
                f.maid.getEntityId(),
                0,
                0) == null,
            "Invalid session was accepted");
        var cursor = f.seed.copy();
        f.owner.inventory.setItemStack(cursor);
        helper.assertTrue(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_CONFIG,
                f.owner,
                helper.getWorld(),
                f.maid.getEntityId(),
                0,
                0) == null,
            "Occupied cursor was accepted");
        helper.assertTrue(
            f.maid.getNavigator()
                .getPath() == path && !f.maid.guiOpening && f.owner.inventory.getItemStack() == cursor,
            "Rejected request changed navigation/GUI/cursor");
        f.owner.inventory.setItemStack(null);
        f.maid.setSleepingBedDirection(2);
        f.maid.setPose(MaidPose.SLEEPING, true);
        helper.assertTrue(f.maid.interact(f.owner), "Sleeping ordinary interaction was not consumed");
        helper.assertTrue(
            f.owner.openContainer == f.owner.inventoryContainer && !f.maid.guiOpening,
            "Sleeping maid opened a new ordinary container");
        helper.assertTrue(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_ORIGINAL_MODELS,
                f.owner,
                helper.getWorld(),
                f.maid.getEntityId(),
                0,
                0) == null,
            "Sleeping tab request bypassed entry rule");
        helper.assertTrue(
            f.maid.getNavigator()
                .getPath() == path,
            "Rejected sleeping menu cleared navigation");
        f.assertStoredItem();
        f.maid.setPose(MaidPose.SLEEPING, false);
        helper.assertTrue(f.maid.interact(f.owner), "Awake ordinary interaction failed");
        helper.assertTrue(
            f.owner.openContainer instanceof EmptyBackpackContainer && f.maid.guiOpening
                && f.maid.getNavigator()
                    .getPath() == null,
            "Accepted FML menu did not open or stop actual navigation");
        f.assertStoredItem();
        f.owner.closeScreen();
        helper.assertTrue(
            !f.maid.guiOpening && f.owner.openContainer == f.owner.inventoryContainer,
            "Normal FML close retained a stale GUI session");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "sleep_gui_special")
    public static void asleepNamingAndSneakingAppleRemainIndependentAndConserveItems(GameTestHelper helper) {
        var f = new Fixture(helper);
        var path = f.installPath();
        f.maid.setSleepingBedDirection(3);
        f.maid.setPose(MaidPose.SLEEPING, true);
        var name = new ItemStack(Items.name_tag, 2);
        name.setTagCompound(new NBTTagCompound());
        name.getTagCompound()
            .setString("Opaque", "retain_name_tag_metadata");
        var nameBefore = name.copy();
        f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = name;
        helper.assertTrue(
            f.maid.interact(f.owner) && f.owner.openContainer instanceof ContainerMaidNaming,
            "Sleeping rule incorrectly blocked the independent name-tag window");
        helper.assertTrue(
            ItemStack.areItemStacksEqual(name, nameBefore) && f.owner.getCurrentEquippedItem() == name
                && f.maid.getNavigator()
                    .getPath() == path,
            "Naming open consumed source or applied ordinary-menu navigation stop");
        f.owner.closeScreen();
        f.owner.setSneaking(true);
        f.maid.setHealth(5);
        var apple = new ItemStack(Items.golden_apple, 3);
        var tag = new NBTTagCompound();
        tag.setString("Opaque", "retain_food_metadata");
        apple.setTagCompound(tag);
        f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = apple;
        helper.assertTrue(f.maid.interact(f.owner), "Sleeping apple care was blocked");
        var remaining = f.owner.getCurrentEquippedItem();
        helper.assertTrue(
            remaining != null && remaining.getItem() == Items.golden_apple
                && remaining.stackSize == 2
                && tag.equals(remaining.getTagCompound())
                && f.maid.isPotionActive(Potion.regeneration)
                && f.maid.hasPose(MaidPose.SLEEPING)
                && f.maid.getSleepingBedDirection() == 3,
            "Sleeping care lost metadata/count/effect or changed sleeping state");
        f.assertStoredItem();
        helper.succeedWhen(() -> true);
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final EntityPlayerMP owner;
        final EntityMaid maid;
        final ItemStack seed;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var server = MinecraftServer.getServer();
            boolean animals = server.getCanSpawnAnimals();
            server.setCanSpawnAnimals(true);
            helper.afterTest(() -> server.setCanSpawnAnimals(animals));
            owner = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), "QA-SleepGui"),
                new ItemInWorldManager(helper.getWorld()));
            owner.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            new NetHandlerPlayServer(server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            maid = new EntityMaid(helper.getWorld());
            maid.getBrainCoordinator()
                .setSuspended(true);
            helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Fresh GUI fixture owner bind failed");
            helper.setBlock(5, 0, 4, Blocks.stone);
            helper.spawnEntity(maid, 5.5, 1, 4.5);
            var pos = helper.absolute(7, 1, 4);
            owner.setPosition(pos.x() + .5, pos.y(), pos.z() + .5);
            seed = new ItemStack(Items.golden_apple, 2, 1);
            var tag = new NBTTagCompound();
            tag.setString("Source", "original_maid_inventory");
            seed.setTagCompound(tag);
            maid.getMaidInventory()
                .setInventorySlotContents(0, seed.copy());
            helper.afterTest(() -> {
                if (owner.openContainer != owner.inventoryContainer) owner.closeScreen();
                maid.setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(maid);
                owner.setDead();
                channel.finish();
                Object message;
                while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
            });
        }

        PathEntity installPath() {
            var end = helper.absolute(9, 1, 4);
            var path = new PathEntity(new PathPoint[] { new PathPoint(end.x(), end.y(), end.z()) });
            helper.assertTrue(
                maid.getNavigator()
                    .setPath(path, 1)
                    && maid.getNavigator()
                        .getPath() == path,
                "Actual native navigation path was not installed");
            return path;
        }

        void assertStoredItem() {
            helper.assertTrue(
                ItemStack.areItemStacksEqual(
                    seed,
                    maid.getMaidInventory()
                        .getStackInSlot(0)),
                "GUI boundary changed original maid inventory");
        }
    }
}
