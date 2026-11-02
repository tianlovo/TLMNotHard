package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
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
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 原生方块点击及世界音效回调；不在服务端调用CLIENT-only闪光/tooltip或声称真实客户端收音 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ShrineInteractionTests {

    private ShrineInteractionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "shrine_sound_normal")
    public static void nativeInsertExtractSoundsKeepWholeFilmAndRefusedActionsStaySilent(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(f.click(), "Native insert click was not consumed");
        f.inShrine();
        helper.assertTrue(
            f.sounds.entries.equals(java.util.Arrays.asList("minecraft_1.21.10:entity.item_frame.add_item")),
            "Original successful insert sound missing or repeated");
        var successor = new ItemStack(Items.golden_apple, 2, 1);
        successor.setTagCompound(new NBTTagCompound());
        successor.getTagCompound()
            .setString("Opaque", "do_not_overwrite_held_successor");
        f.player.inventory.mainInventory[f.player.inventory.currentItem] = successor;
        f.player.setSneaking(true);
        f.click();
        helper.assertTrue(
            f.player.getCurrentEquippedItem() == successor
                && ItemStack.areItemStacksEqual(f.original, f.shrine.getFilm())
                && f.sounds.entries.size() == 1,
            "Refused occupied-hand extraction altered successor/film or played a sound");
        f.player.inventory.mainInventory[f.player.inventory.currentItem] = null;
        f.click();
        f.inHand();
        helper.assertTrue(
            f.sounds.entries.equals(
                java.util.Arrays.asList(
                    "minecraft_1.21.10:entity.item_frame.add_item",
                    "minecraft_1.21.10:entity.item_frame.remove_item")),
            "Original extraction sound missing or repeated");
        f.player.setSneaking(false);
        f.player.inventory.mainInventory[f.player.inventory.currentItem] = successor;
        f.click();
        helper.assertTrue(
            f.player.getCurrentEquippedItem() == successor && f.shrine.getFilm() == null
                && f.sounds.entries.size() == 2,
            "Rejected non-film insert changed data or played a sound");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "shrine_sound_fault")
    public static void nativeSoundLinkageFailureAfterTransferKeepsFilmAndDoesNotRetryDeliveryOrSound(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.afterTest(() -> IntegrationGuard.resetForDevelopment(Side.SERVER));
        f.sounds.fail = true;
        helper.assertTrue(f.click(), "Sound exception escaped the native insert click");
        f.inShrine();
        var fault = IntegrationGuard.faults(Side.SERVER)
            .get(ExternalFeature.SHRINE_SOUNDS.id);
        helper.assertTrue(
            f.sounds.calls == 1 && fault != null
                && fault.cause instanceof NoSuchMethodError
                && !IntegrationGuard.available(Side.SERVER, ExternalFeature.SHRINE_SOUNDS),
            "Actual sound callback linkage failure did not trip only shrine sounds");
        helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, ExternalFeature.CABINET_SOUNDS),
            "Shrine sound failure disabled unrelated cabinet sounds");
        f.player.setSneaking(true);
        helper.assertTrue(f.click(), "Fallback extraction was not consumed");
        f.inHand();
        helper.assertTrue(f.sounds.calls == 1, "Disabled sound boundary retried the failing world API");
        helper.succeedWhen(() -> {
            f.inHand();
            helper.assertTrue(f.sounds.calls == 1, "World tick retried the disabled sound or delivery");
            return true;
        });
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final EntityPlayerMP player;
        final EmbeddedChannel channel;
        final MaidShrineTile shrine;
        final ItemStack original;
        final int x, y, z;
        final Sounds sounds;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var server = MinecraftServer.getServer();
            player = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), "QA-ShrineSound"),
                new ItemInWorldManager(helper.getWorld()));
            player.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
            player.capabilities.disableDamage = true;
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            new NetHandlerPlayServer(server, manager, player);
            IsolatedPlayerChannel.verify(manager, channel);
            helper.setBlock(7, 0, 4, Blocks.stone);
            helper.spawnEntity(player, 7.5, 1, 4.5);
            helper.setBlock(8, 0, 4, Blocks.stone);
            helper.setBlock(8, 1, 4, MaidResurrectionContent.SHRINE);
            var pos = helper.absolute(8, 1, 4);
            x = pos.x();
            y = pos.y();
            z = pos.z();
            shrine = (MaidShrineTile) helper.getWorld()
                .getTileEntity(x, y, z);
            original = MaidLifecycleItems
                .credential(MaidResurrectionContent.FILM, UUID.randomUUID(), player.getUniqueID());
            original.getTagCompound()
                .setIntArray("Opaque", new int[] { 9, 2, 7 });
            original.setStackDisplayName("QA-原胶片");
            player.inventory.mainInventory[player.inventory.currentItem] = original.copy();
            sounds = new Sounds(this);
            helper.getWorld()
                .addWorldAccess(sounds);
            helper.afterTest(this::close);
        }

        boolean click() {
            return MaidResurrectionContent.SHRINE
                .onBlockActivated(helper.getWorld(), x, y, z, player, 2, .5F, .5F, .5F);
        }

        void inShrine() {
            helper.assertTrue(
                player.getCurrentEquippedItem() == null && ItemStack.areItemStacksEqual(original, shrine.getFilm()),
                "Completed transfer did not leave exactly the complete original film in the shrine");
        }

        void inHand() {
            helper.assertTrue(
                shrine.getFilm() == null && ItemStack.areItemStacksEqual(original, player.getCurrentEquippedItem()),
                "Extraction lost, duplicated or altered the complete original film");
        }

        void close() {
            helper.getWorld()
                .removeWorldAccess(sounds);
            if (helper.getWorld()
                .getTileEntity(x, y, z) == shrine) shrine.extract();
            player.inventory.mainInventory[player.inventory.currentItem] = null;
            helper.getWorld()
                .removePlayerEntityDangerously(player);
            channel.finish();
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        }
    }

    private static final class Sounds implements IWorldAccess {

        final Fixture f;
        final List<String> entries = new ArrayList<>();
        int calls;
        boolean fail;

        Sounds(Fixture f) {
            this.f = f;
        }

        @Override
        public void playSound(String name, double x, double y, double z, float volume, float pitch) {
            if (!name.contains(":entity.item_frame.") || Math.abs(x - f.x - .5) > 1E-8
                || Math.abs(y - f.y - .5) > 1E-8
                || Math.abs(z - f.z - .5) > 1E-8) return;
            calls++;
            f.helper.assertTrue(volume == 1 && pitch == 1, "Original frame sound volume/pitch changed");
            if (fail) throw new NoSuchMethodError("QA actual optional world sound API changed after film transfer");
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
