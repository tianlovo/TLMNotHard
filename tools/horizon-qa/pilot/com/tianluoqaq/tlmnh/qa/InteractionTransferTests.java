package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidInteractionTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleNbtKeys;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/** 原生交互入口和本域持有记录，不把同JVM磁盘重载称作跨进程或硬崩溃证明 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class InteractionTransferTests {

    private InteractionTransferTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "interaction_transfer")
    public static void stackedPotionCanceledOrRevokedDeliveryRetriesWithoutOverwritingInventory(GameTestHelper helper) {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new OutputEvents(f);
        var maid = source(f);
        for (boolean canceled : new boolean[] { true, false }) {
            f.resetPlayer();
            for (int i = 0; i < f.owner.inventory.mainInventory.length; i++)
                f.owner.inventory.mainInventory[i] = new ItemStack(Items.stick, 64);
            var potion = new ItemStack(Items.potionitem, 2, 0);
            var custom = new NBTTagList();
            custom.appendTag(
                new PotionEffect(Potion.moveSpeed.id, 333, 0).writeCustomPotionEffectToNBT(new NBTTagCompound()));
            potion.setTagCompound(new NBTTagCompound());
            potion.getTagCompound()
                .setTag("CustomPotionEffects", custom);
            f.owner.inventory.mainInventory[0] = potion;
            f.owner.setSneaking(true);
            events.callback = event -> {
                if (canceled) event.setCanceled(true);
                else {
                    f.owner.inventory.mainInventory[5] = new ItemStack(Items.gold_ingot, 17);
                    f.owner.inventory.currentItem = 1;
                    f.owner.inventory.setItemStack(new ItemStack(Items.emerald));
                }
            };
            events.interact(maid);
            helper.assertTrue(
                events.calls == 1 && f.owner.inventory.mainInventory[0].stackSize == 2
                    && !maid.isPotionActive(Potion.moveSpeed)
                    && events.live() == 0
                    && !maid.getEntityData()
                        .hasKey(MaidLifecycleNbtKeys.TAG_2),
                "Safely retractable potion callback consumed the source/effect or left a permanent lock: " + canceled);
            if (!canceled) helper.assertTrue(
                f.owner.inventory.mainInventory[5].getItem() == Items.gold_ingot
                    && f.owner.inventory.mainInventory[5].stackSize == 17
                    && f.owner.inventory.currentItem == 1
                    && f.owner.inventory.getItemStack()
                        .getItem() == Items.emerald,
                "Potion rollback overwrote callback inventory/selection/cursor");
            f.owner.inventory.currentItem = 0;
            f.owner.inventory.setItemStack(null);
            events.callback = null;
            events.interact(maid);
            helper.assertTrue(
                events.calls == 2 && f.owner.inventory.mainInventory[0].stackSize == 1
                    && maid.getActivePotionEffect(Potion.moveSpeed)
                        .getDuration() == 333
                    && events.live() == 1
                    && !maid.getEntityData()
                        .hasKey(MaidLifecycleNbtKeys.TAG_2),
                "Same ordinary potion source could not retry exactly once after safe cancellation");
            maid.removePotionEffect(Potion.moveSpeed.id);
            events.clearOwnedOutputs();
            events.calls = 0;
        }
        System.out.println(
            "TLMNH_INTERACTION_CONTAINER_PASS canceled=retry revoked=retry unknownInventory=retained nativeEffect=speed333");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "interaction_transfer")
    public static void changedRewardRemainsHeldAcrossNativeReloadAndDeathFilmWithoutLosingLaterExperience(
        GameTestHelper helper) throws Exception {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new OutputEvents(f);
        var maid = source(f);
        maid.setExperience(24);
        f.owner.inventory.mainInventory[0] = new ItemStack(Items.glass_bottle, 2);
        // 真正修改输出物品本身，不能只改无关EntityData而假装触发完整物品所有权保护
        events.callback = event -> {
            var output = ((EntityItem) event.entity).getEntityItem();
            output.stackSize = 2;
            output.setStackDisplayName("Callback successor");
        };
        events.interact(maid);
        var successor = events.outputs.get(0);
        helper.assertTrue(
            events.calls == 1 && MaidSpawnChecks.present(successor, helper.getWorld())
                && successor.getEntityItem().stackSize == 2
                && successor.getEntityItem()
                    .hasDisplayName()
                && f.owner.inventory.mainInventory[0].stackSize == 1
                && maid.getExperience() == 24
                && MaidInteractionTransfer.availableExperience(maid) == 12
                && maid.getEntityData()
                    .hasKey(MaidLifecycleNbtKeys.TAG_2, 10),
            "Unknown native reward successor was erased/refunded or remained independently redeemable");
        captureControlledFault(f, maid);
        events.callback = null;
        events.interact(maid);
        helper.assertTrue(events.calls == 1, "Held reward was spawned twice");
        var world = helper.getWorld();
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader loader))
            throw new IllegalStateException("Actual native Anvil loader required");
        loader
            .saveChunk(world, world.getChunkFromBlockCoords((int) Math.floor(maid.posX), (int) Math.floor(maid.posZ)));
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        var raw = diskEntity(loader, maid);
        var held = (NBTTagCompound) raw.getCompoundTag("ForgeData")
            .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)
            .copy();
        helper.assertTrue(
            held.equals(
                maid.getEntityData()
                    .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)),
            "Actual MCA did not retain the complete interaction hold");
        world.removePlayerEntityDangerously(maid);
        var restored = (EntityMaid) EntityList.createEntityFromNBT(raw, world);
        f.owned.add(restored);
        helper.assertTrue(
            world.spawnEntityInWorld(restored) && restored.getEntityData()
                .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)
                .equals(held) && MaidInteractionTransfer.availableExperience(restored) == 12,
            "Native full NBT reload lost escrow/XP lock");
        events.interact(restored);
        helper.assertTrue(
            events.calls == 1 && successor.getEntityItem().stackSize == 2,
            "Native reload reissued or overwrote a held reward");
        restored.setPickupItems(false);
        restored.setPickupExperience(true);
        var orb = new EntityXPOrb(world, restored.posX, restored.posY, restored.posZ, 7);
        f.owned.add(orb);
        helper.assertTrue(world.spawnEntityInWorld(orb), "Native pickup fixture orb was rejected");
        orb.ticksExisted = 3;
        restored.pickupXPOrb(orb);
        helper.assertTrue(
            orb.isDead && restored.getExperience() == 31 && MaidInteractionTransfer.availableExperience(restored) == 0,
            "Later native XP was lost or unlocked an uncertain interaction balance");
        restored.setHealth(0);
        restored.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(restored.getUniqueID());
        f.cleanupXp.add(token);
        var identity = f.ledger()
            .reserve(token, MaidLifecycleData.Kind.FILM);
        helper.assertTrue(
            identity != null && f.ledger()
                .remainingDeathExperience(token) == 0,
            "Locked XP became ordinary death XP or lost its independent film right");
        var deathHold = identity.getCompoundTag("ForgeData")
            .getCompoundTag(MaidLifecycleNbtKeys.TAG_2);
        var balances = deathHold.getTagList("DeathBalances", 10);
        helper.assertTrue(
            deathHold.getCompoundTag("OriginalMaid")
                .getInteger("MaidExperience") == 24 && balances.tagCount() == 1
                && balances.getCompoundTagAt(0)
                    .getInteger("CurrentExperience") == 31
                && balances.getCompoundTagAt(0)
                    .getInteger("AvailableExperience") == 0,
            "Film lost the original or subsequently acquired locked XP balance");
        f.ledger()
            .rollback(token);
        helper.assertTrue(
            f.tombstone != null && MaidTombstoneTransfer.collect(f.tombstone, f.owner),
            "Actual death film collection failed");
        ItemStack film = null;
        for (int i = 0; i < f.owner.inventory.mainInventory.length; i++)
            if (MaidLifecycleItems.isFilm(f.owner.inventory.mainInventory[i])) {
                film = f.owner.inventory.mainInventory[i];
                f.owner.inventory.mainInventory[i] = null;
                break;
            }
        helper.setBlock(10, 0, 4, MaidResurrectionContent.SHRINE);
        var shrine = (MaidShrineTile) world.getTileEntity(f.x + 10, f.y, f.z + 4);
        helper.assertTrue(film != null && shrine.insert(film), "Native shrine film setup failed");
        f.owner.inventory.mainInventory[0] = null;
        f.owner.setHealth(f.owner.getMaxHealth());
        var result = MaidLifecycleService.resurrect(f.owner, shrine);
        var revived = f.revived;
        helper.assertTrue(
            result == MaidLifecycleService.Result.SUCCESS && revived != null
                && revived != restored
                && revived.getExperience() == 0
                && revived.getEntityData()
                    .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)
                    .equals(deathHold)
                && MaidInteractionTransfer.availableExperience(revived) == 0
                && successor.getEntityItem().stackSize == 2
                && events.calls == 1,
            "Resurrection unlocked/reissued the held reward or lost its opaque balances: " + result);
        System.out.println(
            "TLMNH_INTERACTION_HOLD_PASS changedReward=retained nativeMcaReload=held laterXP=31 film=independent resurrection=held");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "interaction_transfer")
    public static void soundCallbackCannotDeleteUnknownFieldsAddedInPlaceToThePaidHold(GameTestHelper helper)
        throws Exception {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new OutputEvents(f);
        var maid = source(f);
        maid.setExperience(24);
        f.owner.inventory.mainInventory[0] = new ItemStack(Items.glass_bottle, 2);
        events.onSound = () -> maid.getEntityData()
            .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)
            .setString("UnknownPaidBalance", "callback-owned");
        events.interact(maid);
        var hold = maid.getEntityData()
            .getCompoundTag(MaidLifecycleNbtKeys.TAG_2);
        helper.assertTrue(
            events.soundCalls == 1 && events.calls == 1
                && events.live() == 1
                && maid.getExperience() == 12
                && f.owner.inventory.mainInventory[0].stackSize == 1
                && hold.getBoolean("Paid")
                && hold.getString("UnknownPaidBalance")
                    .equals("callback-owned")
                && MaidInteractionTransfer.availableExperience(maid) == 0,
            "Native sound callback's same-object hold successor was deleted, refunded or became redeemable");
        captureControlledFault(f, maid);
        events.onSound = null;
        events.interact(maid);
        helper.assertTrue(events.calls == 1 && events.soundCalls == 1, "Unknown paid hold issued another reward");
        var world = helper.getWorld();
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader loader))
            throw new IllegalStateException("Actual native Anvil loader required");
        loader
            .saveChunk(world, world.getChunkFromBlockCoords((int) Math.floor(maid.posX), (int) Math.floor(maid.posZ)));
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        helper.assertTrue(
            diskEntity(loader, maid).getCompoundTag("ForgeData")
                .getCompoundTag(MaidLifecycleNbtKeys.TAG_2)
                .equals(hold),
            "Native save discarded the exact opaque paid-hold successor");
        System.out.println(
            "TLMNH_INTERACTION_PAID_HOLD_PASS nativeSoundCallback=exercised sameObjectUnknown=retained nativeMca=retained newReward=0");
        helper.succeed();
    }

    private static EntityMaid source(LifecycleConservationTests.Fixture f) {
        var maid = new EntityMaid(f.helper.getWorld());
        f.owned.add(maid);
        f.helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Source owner binding failed");
        f.helper.spawnEntity(maid, 6.5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPickup(false);
        return maid;
    }

    private static NBTTagCompound diskEntity(AnvilChunkLoader loader, EntityMaid maid) throws Exception {
        try (var input = RegionFileCache.getChunkInputStream(
            loader.chunkSaveLocation,
            (int) Math.floor(maid.posX) >> 4,
            (int) Math.floor(maid.posZ) >> 4)) {
            if (input == null) throw new IllegalStateException("No native saved interaction source chunk");
            var entities = CompressedStreamTools.read(input)
                .getCompoundTag("Level")
                .getTagList("Entities", 10);
            NBTTagCompound result = null;
            for (int i = 0; i < entities.tagCount(); i++) {
                var row = entities.getCompoundTagAt(i);
                if (new UUID(row.getLong("UUIDMost"), row.getLong("UUIDLeast")).equals(maid.getUniqueID())) {
                    if (result != null) throw new IllegalStateException("Native source UUID is not unique");
                    result = (NBTTagCompound) row.copy();
                }
            }
            if (result != null) return result;
        }
        throw new IllegalStateException("Native source UUID absent from MCA");
    }

    private static void captureControlledFault(LifecycleConservationTests.Fixture f, EntityMaid maid) {
        String key = "interaction_transfer/" + maid.getUniqueID();
        var fault = IntegrationGuard.faults(Side.SERVER)
            .get(key);
        f.helper.assertTrue(
            !f.priorFaults.containsKey(key) && fault != null
                && f.owner.getUniqueID()
                    .equals(fault.owner),
            "Controlled reward fault collided with existing state");
        f.expectedFaults.put(key, fault.cause);
    }

    public static final class OutputEvents {

        final LifecycleConservationTests.Fixture fixture;
        final List<EntityItem> outputs = new ArrayList<>();
        Consumer<EntityJoinWorldEvent> callback;
        Runnable onSound;
        EntityMaid target;
        boolean active;
        int calls, soundCalls;

        OutputEvents(LifecycleConservationTests.Fixture fixture) {
            this.fixture = fixture;
            MinecraftForge.EVENT_BUS.register(this);
            fixture.helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(this));
        }

        void interact(EntityMaid maid) {
            target = maid;
            active = true;
            try {
                fixture.helper
                    .assertTrue(fixture.owner.interactWith(maid), "Actual native interaction was not accepted");
            } finally {
                active = false;
                target = null;
            }
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (!active || event.world != fixture.helper.getWorld() || !(event.entity instanceof EntityItem output))
                return;
            calls++;
            outputs.add(output);
            fixture.owned.add(output);
            if (callback != null) callback.accept(event);
        }

        @SubscribeEvent
        public void sound(PlaySoundAtEntityEvent event) {
            if (active && event.entity == target && onSound != null) {
                soundCalls++;
                onSound.run();
            }
        }

        int live() {
            int sum = 0;
            for (var item : outputs) if (MaidSpawnChecks.present(item, fixture.helper.getWorld())) sum++;
            return sum;
        }

        void clearOwnedOutputs() {
            for (var item : outputs) if (!item.isDead) fixture.helper.getWorld()
                .removeEntity(item);
            outputs.clear();
        }
    }
}
