package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.BlockBed;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedLifeTests {

    private EnhancedLifeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "enhanced_life_home")
    public static void offlineHomeReturnsToRestAreaSleepsOnceAndScheduleReleasesBed(GameTestHelper helper) {
        int radius = MaidHomeConfig.sleepRange;
        MaidHomeConfig.sleepRange = 3;
        helper.afterTest(() -> MaidHomeConfig.sleepRange = radius);
        var f = fixture(helper);
        var bed = bed(helper, 8, 1, 4);
        f.maid.setPosition(f.x + 2.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(
            f.maid.getHomeArea()
                .setEnabled(true),
            "Home enable failed");
        f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(
            f.maid.getHomeArea()
                .capture(Activity.REST),
            "Rest location capture failed");
        f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
        var core = select(helper, f.maid);
        helper.getWorld().playerEntities.remove(f.owner);
        net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.remove(f.owner);
        boolean[] returned = { false }, path = { false };
        int[] sleptAt = { -1 }, changedAt = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f.maid, core);
            int age = f.maid.ticksExisted;
            returned[0] |= core.selectedKind() == Kind.HOME;
            path[0] |= !f.navigation.noPath();
            if (sleptAt[0] < 0 && f.maid.hasPose(MaidPose.SLEEPING)) {
                helper.assertTrue(
                    returned[0] && path[0]
                        && f.maid.posX > f.x + 6
                        && bed.isOwnedBy(f.maid)
                        && core.life()
                            .sleepEntries() == 1,
                    "Offline home sleep bypassed return, real approach or bed ownership");
                sleptAt[0] = age;
            }
            if (sleptAt[0] < 0) return false;
            if (changedAt[0] < 0 && age >= sleptAt[0] + 5) {
                helper.assertTrue(
                    core.life()
                        .sleepEntries() == 1 && bed.isOwnedBy(f.maid),
                    "Sleep restarted or lost lease");
                helper.getWorld()
                    .setWorldTime(12000);
                changedAt[0] = age;
                return false;
            }
            if (changedAt[0] < 0 || age <= changedAt[0]) return false;
            helper.assertTrue(
                !f.maid.hasPose(MaidPose.SLEEPING) && !bed.isOwnedBy(f.maid)
                    && (helper.getWorld()
                        .getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 4) == 0
                    && f.maid.getHomeArea()
                        .restrictionCenter().x == f.x + 2,
                "Schedule did not wake, release bed and refresh work restriction");
            return age >= changedAt[0] + 3;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "enhanced_life_shared_bed")
    public static void approachingReservationIsExclusiveCancelledAndReusedThenRemovalWakes(GameTestHelper helper) {
        int radius = MaidHomeConfig.sleepRange;
        MaidHomeConfig.sleepRange = 12;
        helper.afterTest(() -> MaidHomeConfig.sleepRange = radius);
        var f = fixture(helper);
        var bed = bed(helper, 8, 1, 4);
        var other = additional(helper, f, 1.5, 2.5);
        helper.assertTrue(
            f.maid.getHomeArea()
                .setEnabled(true)
                && other.getHomeArea()
                    .setEnabled(true),
            "Home setup for exclusive bed failed");
        var first = select(helper, f.maid);
        var second = select(helper, other);
        var actors = new EntityMaid[] { f.maid, other };
        var cores = new EnhancedCoreBrain[] { first, second };
        TileEntityMaidBed[] replacementBed = { null };
        int[] cancelled = { -1 }, phase = { 0 }, changed = { 0 };
        helper.succeedWhen(() -> {
            ready(helper, f.maid, first);
            ready(helper, other, second);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                int owner = bed.isOwnedBy(f.maid) ? 0 : bed.isOwnedBy(other) ? 1 : -1;
                if (owner < 0) return false;
                helper.assertTrue(
                    !actors[owner].hasPose(MaidPose.SLEEPING) && !bed.isOwnedBy(actors[1 - owner]),
                    "Approach reservation was late or not exclusive");
                actors[owner].getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(!bed.isOwnedBy(actors[owner]), "Cancelled approach kept bed lease");
                cancelled[0] = owner;
                phase[0] = 1;
                return false;
            }
            var successor = actors[1 - cancelled[0]];
            if (phase[0] == 1) {
                if (!successor.hasPose(MaidPose.SLEEPING)) return false;
                helper.assertTrue(
                    bed.isOwnedBy(successor) && cores[1 - cancelled[0]].life()
                        .sleepEntries() == 1,
                    "Cancelled reservation was not safely reused");
                // 同一床方块保留，使用原生setTileEntity替换实际对象
                var replacement = new TileEntityMaidBed();
                replacementBed[0] = replacement;
                helper.getWorld()
                    .setTileEntity(bed.xCoord, bed.yCoord, bed.zCoord, replacement);
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 2) {
                helper.assertTrue(
                    !bed.isOwnedBy(successor) && (cores[1 - cancelled[0]].selectedKind() != Kind.SLEEP
                        || replacementBed[0].isOwnedBy(successor)),
                    "Replaced bed object retained old ownership or stale selected action");
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                if (!successor.hasPose(MaidPose.SLEEPING)) return false;
                helper.assertTrue(
                    replacementBed[0].isOwnedBy(successor) && cores[1 - cancelled[0]].life()
                        .sleepEntries() == 2,
                    "Replacement did not acquire a fresh actual sleep entry and its own lease");
                helper.setBlock(8, 1, 4, Blocks.air);
                changed[0] = age;
                phase[0] = 4;
                return false;
            }
            helper.assertTrue(
                !successor.hasPose(MaidPose.SLEEPING) && !actors[cancelled[0]].hasPose(MaidPose.SLEEPING),
                "Removed replacement bed retained sleep or revived cancelled actor");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "enhanced_life_snack_commit")
    public static void revokedNativePlacementKeepsItemsFreshPlacementPaysOnceAndAnotherMaidTakesOneBite(
        GameTestHelper helper) {
        var f = fixture(helper);
        f.maid.setSchedule(MaidSchedule.ALL);
        var core = select(helper, f.maid);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
        helper.setBlock(3, 0, 4, MaidLifeContent.snackCabinet);
        var hand = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.diamond, 7);
        f.maid.setCurrentItemOrArmor(0, hand.copy());
        f.maid.setOffhandItem(off.copy());
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.cake));
        var callback = new RevokePlacement(f.maid);
        MinecraftForge.EVENT_BUS.register(callback);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(callback));
        EntityMaid[] eater = { null };
        EnhancedCoreBrain[] eaterCore = { null };
        int[] phase = { 0 }, placedAt = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f.maid, core);
            int age = f.maid.ticksExisted;
            var cake = helper.absolute(3, 1, 4);
            if (phase[0] == 0) {
                if (callback.calls == 0) return false;
                helper.assertTrue(
                    f.maid.getBrainCoordinator()
                        .isSuspended()
                        && helper.getWorld()
                            .isAirBlock(cake.x(), cake.y(), cake.z())
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 1
                        && ItemStack.areItemStacksEqual(hand, f.maid.getHeldItem())
                        && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                    "Revoked native placement consumed, duplicated or replaced original items");
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (core.life()
                    .placements() < 1) return false;
                helper.assertTrue(
                    core.life()
                        .placements() == 1
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0) == null
                        && helper.getWorld()
                            .getBlock(cake.x(), cake.y(), cake.z()) == Blocks.cake,
                    "Fresh placement did not pay exactly one finite cake");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                eater[0] = additional(helper, f, 1.5, 6.5);
                eater[0].setSchedule(MaidSchedule.ALL);
                eaterCore[0] = select(helper, eater[0]);
                placedAt[0] = age;
                phase[0] = 2;
                return false;
            }
            ready(helper, eater[0], eaterCore[0]);
            if (eaterCore[0].life()
                .bites() < 1) return false;
            helper.assertTrue(
                eaterCore[0].life()
                    .bites() == 1
                    && helper.getWorld()
                        .getBlockMetadata(cake.x(), cake.y(), cake.z()) == 1
                    && core.life()
                        .placements() == 1
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0) == null,
                "Steal did not change one real bite or repeated placement payment");
            return age >= placedAt[0] + 3;
        });
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 16, 16);
        f.maid.setSchedule(MaidSchedule.NIGHT);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EntityMaid additional(GameTestHelper helper, ModernCoreTests.Fixture f, double x, double z) {
        var maid = new EntityMaid(helper.getWorld());
        helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Additional owner binding failed");
        maid.setPickup(false);
        maid.setSchedule(MaidSchedule.NIGHT);
        helper.spawnEntity(maid, x, 1, z);
        helper.afterTest(() -> {
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(maid);
        });
        return maid;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, EntityMaid maid) {
        var profile = MaidBrainProfile.ENHANCED;
        helper.assertTrue(
            maid.selectBrainProfileForDevelopment(profile) == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Actual enhanced life selection failed");
        helper.assertTrue(
            maid.getBrainCoordinator()
                .peekPolicyRuntime()
                .getProfile() == profile,
            "Enhanced life silently used a different profile");
        System.out.println("TLMNH_ENHANCED_LIFE_PROFILE profile=" + profile);
        return maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static TileEntityMaidBed bed(GameTestHelper helper, int x, int y, int z) {
        int[] offset = BlockBed.field_149981_a[0];
        helper.setBlock(x - offset[0], y, z - offset[1], MaidLifeContent.maidBed, 0);
        helper.setBlock(x, y, z, MaidLifeContent.maidBed, 8);
        var p = helper.absolute(x, y, z);
        var bed = (TileEntityMaidBed) helper.getWorld()
            .getTileEntity(p.x(), p.y(), p.z());
        helper.assertTrue(bed != null && bed.complete(), "Real bed setup failed");
        return bed;
    }

    private static void ready(GameTestHelper helper, EntityMaid maid, EnhancedCoreBrain core) {
        var brain = maid.getBrainCoordinator();
        helper.assertTrue(
            maid.isEntityAlive() && brain.peekEnhancedCore() == core
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Life behavior faulted or dispatched another root");
    }

    public static final class RevokePlacement {

        private final EntityMaid maid;
        int calls;

        RevokePlacement(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void place(BlockEvent.PlaceEvent event) {
            if (event.world == maid.worldObj && event.player != null
                && event.player.getUniqueID()
                    .equals(maid.getUniqueID())) {
                if (calls++ == 0) maid.getBrainCoordinator()
                    .setSuspended(true);
            }
        }
    }
}
