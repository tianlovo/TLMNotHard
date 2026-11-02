package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/** 实际原生世界和默认End Teleporter；逐个真实入口验证，不手动调用AI或伪造spawn结果 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class DimensionTravelPreservationTests {

    private DimensionTravelPreservationTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "dimension_travel_preservation")
    public static void nativeTravelCommitsOnceAndRetainsOriginalOnCanceledThrowingOrRewrittenSpawn(
        GameTestHelper helper) throws Exception {
        helper.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Development fixture control required");
        helper.assertTrue(
            helper.getWorld().provider.dimensionId == 0,
            "Dimension travel fixture requires native overworld");
        var trial = new Trial(helper);
        for (var mode : Mode.values()) trial.run(mode);
        helper.succeed();
    }

    private enum Mode {
        NORMAL,
        CANCELED,
        THROWING,
        SOURCE_REWRITTEN
    }

    public static final class Trial {

        private final GameTestHelper helper;
        private final WorldServer destination;
        private final GameTestChunkLoader chunks = new GameTestChunkLoader();
        private final List<EntityMaid> owned = new ArrayList<>();
        private final List<BlockSnapshot> platform = new ArrayList<>();
        private final Map<String, IntegrationGuard.Fault> priorFaults = IntegrationGuard.faults(Side.SERVER);
        private final boolean animals;
        private EntityMaid source, candidate;
        private Mode mode;
        private int callbacks;
        private NBTTagCompound callbackSource;
        private ItemStack replacement;
        private IllegalStateException injected;

        Trial(GameTestHelper helper) throws Exception {
            this.helper = helper;
            var server = MinecraftServer.getServer();
            animals = server.getCanSpawnAnimals();
            server.setCanSpawnAnimals(true);
            helper.afterTest(this::cleanup);
            destination = server.worldServerForDimension(1);
            helper.assertTrue(
                destination != null && destination.provider.dimensionId == 1,
                "Actual native End world is unavailable");
            var entrance = destination.getEntrancePortalLocation();
            helper.assertTrue(entrance != null, "Actual native End entrance is unavailable");
            chunks.forceChunksStrict(
                destination,
                entrance.posX - 16,
                entrance.posY,
                entrance.posZ - 16,
                entrance.posX + 16,
                entrance.posY + 3,
                entrance.posZ + 16);
            // 原生End Teleporter只会写这个5×5×4平台；保存原块、元数据和Tile NBT后恢复
            for (int x = -2; x <= 2; x++) for (int y = -2; y <= 1; y++) for (int z = -2; z <= 2; z++) platform.add(
                BlockSnapshot.getBlockSnapshot(destination, entrance.posX + x, entrance.posY + y, entrance.posZ + z));
            MinecraftForge.EVENT_BUS.register(this);
        }

        void run(Mode mode) {
            this.mode = mode;
            callbacks = 0;
            candidate = null;
            callbackSource = null;
            source = new EntityMaid(helper.getWorld());
            owned.add(source);
            source.getBrainCoordinator()
                .setSuspended(true);
            helper.assertTrue(source.bindTo(UUID.randomUUID()), "Native source maid did not bind");
            var identity = source.getUniqueID();
            var inventory = source.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                var item = new ItemStack(Items.diamond, slot + 1, slot);
                item.setStackDisplayName("Dimension original " + slot);
                item.getTagCompound()
                    .setIntArray("Opaque", new int[] { slot, 7, 3 });
                item.getTagCompound()
                    .setString("TravelIdentity", identity.toString());
                inventory.setInventorySlotContents(slot, item);
            }
            var recovery = new NBTTagCompound();
            recovery.setString("id", "unavailable:dimension_original");
            recovery.setInteger("OpaqueRecovery", 73);
            inventory.recovery()
                .retain(recovery);
            source.getEntityData()
                .setString("DimensionOpaqueForge", identity.toString());
            var extended = snapshot(source);
            extended.getCompoundTag("TLMNH")
                .setIntArray("DimensionOpaqueExtension", new int[] { 11, 17, 23 });
            source.readFromNBT(extended);
            helper.spawnEntity(source, 2.5, 1, 2.5);
            var original = snapshot(source);
            boolean finding = destination.findingSpawnPoint;
            var beforeFaults = IntegrationGuard.faults(Side.SERVER);
            helper.assertTrue(
                beforeFaults.containsKey("item/inventory_slot"),
                "Expected recovery parcel warning is absent");
            injected = new IllegalStateException("Controlled native dimension spawn callback failure");
            source.travelToDimension(destination.provider.dimensionId);
            helper.assertTrue(
                callbacks == 1 && candidate != null,
                "Transfer did not invoke exactly one native target spawn");
            helper.assertTrue(destination.findingSpawnPoint == finding, "Native teleporter flag was not restored");
            if (mode == Mode.NORMAL) {
                helper.assertTrue(
                    source.isDead && source.worldObj == helper.getWorld()
                        && present(candidate, destination)
                        && copies(identity) == 1,
                    "Successful native travel did not retire only the source identity");
                var expected = persistent(original);
                var actual = persistent(snapshot(candidate));
                helper.assertTrue(
                    expected.equals(actual),
                    "Native transfer changed non-placement full NBT: expected=" + expected + " actual=" + actual);
            } else {
                var expected = mode == Mode.SOURCE_REWRITTEN ? callbackSource : original;
                helper.assertTrue(
                    present(source, helper.getWorld()) && expected.equals(snapshot(source))
                        && candidate.isDead
                        && copies(identity) == 1,
                    "Rejected native transfer changed source data or retained another live identity: " + mode);
                if (mode == Mode.SOURCE_REWRITTEN) helper.assertTrue(
                    source.getOffhandItem() == replacement && replacement.stackSize == 13
                        && "callback-owned".equals(
                            replacement.getTagCompound()
                                .getString("OpaqueReplacement")),
                    "Rollback overwrote the callback's new source item");
            }
            assertFaultDelta(beforeFaults, identity);
            System.out.println(
                "TLMNH_DIMENSION_TRAVEL_OBSERVED mode=" + mode
                    + " callbacks="
                    + callbacks
                    + " liveIdentityCopies="
                    + copies(identity)
                    + " sourceAlive="
                    + source.isEntityAlive()
                    + " candidateAlive="
                    + candidate.isEntityAlive()
                    + " actualTeleporter="
                    + destination.getDefaultTeleporter()
                        .getClass()
                        .getName());
            removeOwned(source);
            removeOwned(candidate);
            // 每个场景使用新的实体身份；预期封存、注入和已有真实故障均保留原对象
        }

        private void assertFaultDelta(Map<String, IntegrationGuard.Fault> before, UUID identity) {
            var expected = new LinkedHashMap<>(before);
            var actual = IntegrationGuard.faults(Side.SERVER);
            if (mode == Mode.THROWING || mode == Mode.SOURCE_REWRITTEN) {
                String key = "dimension_travel/" + identity + "/" + destination.provider.dimensionId;
                var fault = actual.get(key);
                helper.assertTrue(
                    !before.containsKey(key) && fault != null
                        && fault.side == Side.SERVER
                        && UUID.fromString(source.func_152113_b())
                            .equals(fault.owner)
                        && (mode == Mode.THROWING ? fault.cause == injected
                            : fault.cause instanceof IllegalStateException
                                && "Maid destination spawn callback revoked the staged transfer"
                                    .equals(fault.cause.getMessage())),
                    "Exceptional transfer did not retain the exact expected scoped fault: " + mode);
                expected.put(key, fault);
            }
            helper.assertTrue(
                expected.equals(actual),
                "Transfer changed another fault object or added an unexpected fault: " + mode);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onJoin(EntityJoinWorldEvent event) {
            if (source == null || event.world != destination
                || !(event.entity instanceof EntityMaid maid)
                || !source.getUniqueID()
                    .equals(maid.getUniqueID()))
                return;
            callbacks++;
            candidate = maid;
            owned.add(maid);
            if (mode == Mode.CANCELED) event.setCanceled(true);
            if (mode == Mode.THROWING) throw injected;
            if (mode == Mode.SOURCE_REWRITTEN) {
                replacement = new ItemStack(Items.gold_ingot, 13, 3);
                replacement.setTagCompound(new NBTTagCompound());
                replacement.getTagCompound()
                    .setString("OpaqueReplacement", "callback-owned");
                source.setOffhandItem(replacement);
                callbackSource = snapshot(source);
            }
        }

        private void cleanup() {
            MinecraftForge.EVENT_BUS.unregister(this);
            try {
                for (var maid : owned) removeOwned(maid);
                for (var block : platform)
                    helper.assertTrue(block.restore(true, false), "Native End platform restore failed");
            } finally {
                chunks.releaseAll();
                MinecraftServer.getServer()
                    .setCanSpawnAnimals(animals);
                var faults = IntegrationGuard.faults(Side.SERVER);
                for (var entry : priorFaults.entrySet()) helper.assertTrue(
                    faults.get(entry.getKey()) == entry.getValue(),
                    "Dimension cleanup changed a pre-existing fault object: " + entry.getKey());
            }
        }
    }

    private static boolean present(EntityMaid maid, WorldServer world) {
        if (!maid.isEntityAlive() || maid.worldObj != world || world.getEntityByID(maid.getEntityId()) != maid)
            return false;
        for (Object value : world.loadedEntityList) if (value == maid) return true;
        return false;
    }

    private static int copies(UUID identity) {
        int copies = 0;
        for (var world : DimensionManager.getWorlds()) for (Object value : world.loadedEntityList)
            if (value instanceof EntityMaid maid && maid.isEntityAlive() && identity.equals(maid.getUniqueID()))
                copies++;
        return copies;
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        maid.writeToNBT(result);
        return result;
    }

    private static NBTTagCompound persistent(NBTTagCompound full) {
        var result = (NBTTagCompound) full.copy();
        // 仅排除本次原生定位明确改变的字段，其余装备、完整物品、Forge/扩展与保管NBT完整比较
        for (String key : new String[] { "Pos", "Motion", "Rotation", "Dimension" }) result.removeTag(key);
        return result;
    }

    private static void removeOwned(EntityMaid maid) {
        if (maid == null || !(maid.worldObj instanceof WorldServer world)) return;
        var indexed = world.getEntityByID(maid.getEntityId());
        if (indexed != null && indexed != maid) throw new IllegalStateException("Cleanup refused a foreign successor");
        boolean listed = false;
        for (Object value : world.loadedEntityList) if (value == maid) listed = true;
        if (listed || indexed == maid) world.removePlayerEntityDangerously(maid);
        else maid.setDead();
    }
}
