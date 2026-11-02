package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.WorldSettings;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.life.cabinet.ItemSnackCabinet;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 原生库存/命名与真实世界中的两个内存玩家窗口；不声明真实客户端输入或听觉验收 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SnackCabinetBehaviorTests {

    private SnackCabinetBehaviorTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "cabinet_name_signal")
    public static void namedNativePlacementAndInventorySignalsPreserveItemsRecoveryAndUnknownName(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(
            Item.getItemFromBlock(MaidLifeContent.snackCabinet) instanceof ItemSnackCabinet,
            "Cabinet tooltip item did not keep the existing block registration");
        helper.assertTrue(
            f.cabinet.hasCustomInventoryName() && f.cabinet.getInventoryName()
                .equals("QA-零食柜"),
            "Native named ItemBlock placement lost the title");
        var raw = new NBTTagCompound();
        raw.setString("id", "future:unavailable_food");
        raw.setString("Raw", "opaque_recovery_payload");
        f.cabinet.recovery()
            .retain(raw);
        helper.assertTrue(f.signal() == 0, "Recovery escrow contributed to visible inventory signal");
        var apple = new ItemStack(Items.golden_apple, 1, 1);
        var payload = new NBTTagCompound();
        payload.setString("Opaque", "native_item_payload");
        apple.setTagCompound(payload);
        f.cabinet.setInventorySlotContents(0, apple.copy());
        helper.assertTrue(f.signal() == 1, "One native stack did not produce signal1");
        for (int slot = 0; slot < 27; slot++) {
            var half = apple.copy();
            half.stackSize = 32;
            f.cabinet.setInventorySlotContents(slot, half);
        }
        helper.assertTrue(f.signal() == 8, "Half-full native inventory did not produce signal8");
        for (int slot = 0; slot < 27; slot++) {
            f.cabinet.getStackInSlot(slot).stackSize = 64;
        }
        f.cabinet.markDirty();
        helper.assertTrue(f.signal() == 15, "Full native inventory did not produce signal15");
        var saved = f.saved();
        var loaded = new TileEntitySnackCabinet();
        loaded.readFromNBT(saved);
        var roundTrip = new NBTTagCompound();
        loaded.writeToNBT(roundTrip);
        helper.assertTrue(saved.equals(roundTrip), "Native name/27 full item NBT/Recovery round trip changed data");
        f.first.displayGUIChest(f.cabinet);
        helper.assertTrue(
            f.first.openContainer instanceof ContainerChest chest && chest.getLowerChestInventory() == f.cabinet
                && chest.getLowerChestInventory()
                    .hasCustomInventoryName()
                && chest.getLowerChestInventory()
                    .getInventoryName()
                    .equals("QA-零食柜"),
            "Native container lost the custom title");
        f.first.closeScreen();
        saved.setByteArray("CustomName", new byte[] { 9, 2, 7 });
        loaded.readFromNBT(saved);
        helper.assertTrue(
            !loaded.hasCustomInventoryName() && loaded.getInventoryName()
                .equals("container.tlmnh.snack_cabinet"),
            "Unknown raw name was used as a display string");
        roundTrip = new NBTTagCompound();
        loaded.writeToNBT(roundTrip);
        helper.assertTrue(saved.equals(roundTrip), "Unknown raw CustomName or inventory/Recovery was normalized");
        helper.setBlock(9, 0, 4, Blocks.stone);
        helper.getWorld()
            .setBlock(f.x + 1, f.y, f.z, Blocks.unpowered_comparator, 1, 3);
        f.cabinet.markDirty();
        f.at = helper.getWorld()
            .getTotalWorldTime();
        helper.succeedWhen(() -> {
            long now = helper.getWorld()
                .getTotalWorldTime();
            if (now - f.at < 5) return false;
            var comparator = (net.minecraft.tileentity.TileEntityComparator) helper.getWorld()
                .getTileEntity(f.x + 1, f.y, f.z);
            if (f.stage == 0) {
                helper.assertTrue(
                    f.signal() == 15 && comparator.getOutputSignal() == 15,
                    "Native world comparator did not update from full inventory markDirty");
                for (int slot = 0; slot < 27; slot++) f.cabinet.setInventorySlotContents(slot, null);
                f.stage = 1;
                f.at = now;
                return false;
            }
            helper.assertTrue(
                f.signal() == 0 && comparator.getOutputSignal() == 0,
                "Native comparator retained power after inventory removal or counted Recovery escrow");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "cabinet_openers")
    public static void firstOpenLastCloseMissingCloseCallbackAndSpectatorUseNativeWorldSounds(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.getWorld()
            .addWorldAccess(f.sounds);
        f.listening = true;
        f.first.displayGUIChest(f.cabinet);
        helper.succeedWhen(() -> {
            long now = helper.getWorld()
                .getTotalWorldTime();
            if (f.stage == 0) {
                if (f.sounds.entries.size() < 1) return false;
                f.sounds.check(1, 0);
                f.second.displayGUIChest(f.cabinet);
                f.stage = 1;
                f.at = now;
                return false;
            }
            if (now - f.at < 6) return false;
            if (f.stage == 1) {
                f.sounds.check(1, 0);
                f.first.closeScreen();
            } else if (f.stage == 2) {
                f.sounds.check(1, 0);
                // 实际从世界移除第二玩家，不调用其容器关闭回调，核定期窗口重查
                helper.getWorld()
                    .removePlayerEntityDangerously(f.second);
            } else if (f.stage == 3) {
                f.sounds.check(1, 1);
                f.first.theItemInWorldManager.setGameType(WorldSettings.GameType.getByID(3));
                helper.assertTrue(
                    f.first.theItemInWorldManager.getGameType()
                        .getID() == 3,
                    "Actual registered spectator mode missing");
                f.first.displayGUIChest(f.cabinet);
            } else if (f.stage == 4) {
                f.sounds.check(1, 1);
                f.first.closeScreen();
                f.first.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                f.first.displayGUIChest(f.cabinet);
            } else if (f.stage == 5) {
                f.sounds.check(2, 1);
                f.first.closeScreen();
            } else {
                f.sounds.check(2, 2);
                TLMNotHard.LOG
                    .info("TLMNH_CABINET_OPENERS_NATIVE_PASS events={} elapsed={}", f.sounds.entries, now - f.started);
                return true;
            }
            f.stage++;
            f.at = now;
            return false;
        });
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final List<EmbeddedChannel> channels = new ArrayList<>();
        final EntityPlayerMP first, second;
        final TileEntitySnackCabinet cabinet;
        final int x, y, z;
        final Sounds sounds;
        final long started;
        int stage;
        long at;
        boolean listening;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            started = helper.getWorld()
                .getTotalWorldTime();
            var pos = helper.absolute(8, 1, 4);
            x = pos.x();
            y = pos.y();
            z = pos.z();
            first = player("QA-Cabinet-A", 7.5);
            second = player("QA-Cabinet-B", 9.5);
            helper.afterTest(this::close);
            helper.setBlock(8, 0, 4, Blocks.stone);
            var stack = new ItemStack(Item.getItemFromBlock(MaidLifeContent.snackCabinet));
            stack.setStackDisplayName("QA-零食柜");
            first.rotationYaw = 90;
            first.inventory.mainInventory[first.inventory.currentItem] = stack;
            helper.assertTrue(
                stack.getItem()
                    .onItemUse(stack, first, helper.getWorld(), x, y - 1, z, 1, .5F, .5F, .5F) && stack.stackSize == 0,
                "Native named cabinet placement failed or did not consume exactly one");
            cabinet = (TileEntitySnackCabinet) helper.getWorld()
                .getTileEntity(x, y, z);
            sounds = new Sounds(this);
        }

        EntityPlayerMP player(String name, double relativeX) {
            var player = new EntityPlayerMP(
                MinecraftServer.getServer(),
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), name),
                new ItemInWorldManager(helper.getWorld()));
            player.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
            player.capabilities.disableDamage = true;
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            channels.add(channel);
            manager.setConnectionState(EnumConnectionState.PLAY);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
            IsolatedPlayerChannel.verify(manager, channel);
            helper.setBlock((int) relativeX, 0, 4, Blocks.stone);
            helper.spawnEntity(player, relativeX, 1, 4.5);
            return player;
        }

        int signal() {
            helper
                .assertTrue(MaidLifeContent.snackCabinet.hasComparatorInputOverride(), "Cabinet has no analog output");
            return MaidLifeContent.snackCabinet.getComparatorInputOverride(helper.getWorld(), x, y, z, 0);
        }

        NBTTagCompound saved() {
            var result = new NBTTagCompound();
            cabinet.writeToNBT(result);
            return result;
        }

        void close() {
            if (listening) helper.getWorld()
                .removeWorldAccess(sounds);
            for (var player : new EntityPlayerMP[] { first, second }) {
                if (player.openContainer != player.inventoryContainer) player.closeScreen();
                helper.getWorld()
                    .removePlayerEntityDangerously(player);
            }
            if (helper.getWorld()
                .getTileEntity(x, y, z) == cabinet) {
                for (int slot = 0; slot < 27; slot++) cabinet.setInventorySlotContents(slot, null);
                cabinet.recovery()
                    .read(new net.minecraft.nbt.NBTTagList());
            }
            for (var channel : channels) {
                channel.finish();
                Object message;
                while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
            }
        }
    }

    private static final class Sounds implements IWorldAccess {

        final Fixture f;
        final List<String> entries = new ArrayList<>();

        Sounds(Fixture f) {
            this.f = f;
        }

        void check(int opens, int closes) {
            f.helper.assertTrue(
                entries.stream()
                    .filter(s -> s.endsWith(".open"))
                    .count() == opens
                    && entries.stream()
                        .filter(s -> s.endsWith(".close"))
                        .count() == closes,
                "Unexpected first-open/last-close native sounds: " + entries);
        }

        @Override
        public void playSound(String name, double x, double y, double z, float volume, float pitch) {
            if (!name.contains("barrel.") && !name.startsWith("random.chest")) return;
            if (Math.abs(x - (f.x + 1)) > .001 || Math.abs(z - (f.z + .5)) > .001 || Math.abs(y - (f.y + .5)) > .001)
                return;
            f.helper.assertTrue(
                name.equals("minecraft_1.21.10:block.barrel.open")
                    || name.equals("minecraft_1.21.10:block.barrel.close"),
                "Actual installed EFR sound mapping differed: " + name);
            f.helper.assertTrue(
                volume == .5F && pitch >= .8F && pitch < .901F,
                "Original barrel sound volume/pitch changed");
            entries.add(name);
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void markBlockForRenderUpdate(int x, int y, int z) {}

        @Override
        public void markBlockRangeForRenderUpdate(int x, int y, int z, int a, int b, int c) {}

        @Override
        public void playSoundToNearExcept(EntityPlayer p, String n, double x, double y, double z, float v, float q) {}

        @Override
        public void spawnParticle(String n, double x, double y, double z, double a, double b, double c) {}

        @Override
        public void onEntityCreate(Entity entity) {}

        @Override
        public void onEntityDestroy(Entity entity) {}

        @Override
        public void playRecord(String name, int x, int y, int z) {}

        @Override
        public void broadcastSound(int n, int x, int y, int z, int data) {}

        @Override
        public void playAuxSFX(EntityPlayer player, int n, int x, int y, int z, int data) {}

        @Override
        public void destroyBlockPartially(int id, int x, int y, int z, int stage) {}

        @Override
        public void onStaticEntitiesChanged() {}
    }
}
