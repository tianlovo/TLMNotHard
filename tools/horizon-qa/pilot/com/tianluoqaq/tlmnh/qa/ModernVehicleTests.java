package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidPlayerVehicleState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVehicleActor.Vehicle;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernVehicleTests {

    private ModernVehicleTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 220, batch = "modern_vehicle_board")
    public static void naturalEfrApproachBoardsPassengerSeatAndRecentOwnerDismountReleasesMaid(GameTestHelper helper) {
        var v = new Fixture(helper);
        var f = v.f;
        int[] boarded = { -1 }, ownerLeft = { -1 };
        boolean[] path = { false }, done = { false }, vehicleWalk = { false };
        int[] otherWalkTicks = { 0 };
        helper.onEachTick("actual EFR approach, passenger identity and recent owner departure", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            if (boarded[0] < 0) {
                var walk = f.core.getActor()
                    .peekWalkTarget();
                if (walk != null) {
                    // 原任务只在没有WALK时写乘船目标，不能把自然游走/其他任务的WALK当成乘船路径
                    if (tracksEntity(walk, v.boat)) {
                        helper.assertTrue(
                            walk.getSpeedModifier() == .5F && walk.getCloseEnoughDistance() == 2,
                            "Vehicle approach lost original speed/close range");
                        vehicleWalk[0] = true;
                    } else otherWalkTicks[0]++;
                    if (!f.navigation.noPath()) path[0] = true;
                }
                if (f.maid.ridingEntity != null) {
                    v.assertPassenger();
                    helper.assertTrue(path[0], "No real route preceded passenger boarding");
                    boarded[0] = age;
                }
            } else if (ownerLeft[0] < 0) {
                v.assertPassenger();
                if (age >= boarded[0] + 5) {
                    f.owner.mountEntity(null);
                    f.owner.setPosition(v.boat.posX + 1, f.maid.posY, v.boat.posZ);
                    helper.assertTrue(
                        ((MaidPlayerVehicleState) f.owner).tlmnh$inDismountCooldown(),
                        "Actual player dismount did not open the source millisecond window");
                    ownerLeft[0] = age;
                }
            } else if (age >= ownerLeft[0] + 2) {
                helper.assertTrue(
                    f.maid.ridingEntity == null && f.owner.ridingEntity == null,
                    "Nearby maid did not leave after the owner's recent dismount");
                done[0] = true;
                System.out.println(
                    "TLMNH_VEHICLE107_BOARD_PASS tick=" + age
                        + " boarded="
                        + boarded[0]
                        + " vehicleWalkObserved="
                        + vehicleWalk[0]
                        + " otherWalkTicks="
                        + otherWalkTicks[0]);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 210, batch = "modern_vehicle_capacity_flags")
    public static void fullSeatAndRidePermissionRejectThenExpiredWindowKeepsPassengerAndMenuDisembarks(
        GameTestHelper helper) {
        var v = new Fixture(helper);
        var f = v.f;
        // 旧版31号字符串默认允许骑乘，其余已保存设置不能在追加字段时丢失
        f.maid.setSitting(true); // 填满第二座后才允许AI开始，避免首帧空座产生合法旧WALK
        f.maid.getDataWatcher()
            .updateObject(31, "3;0.6;2;5;3");
        helper.assertTrue(f.maid.isRideable(), "Old metadata did not retain the source true default");
        var chicken = new EntityChicken(helper.getWorld());
        chicken.tasks.taskEntries.clear();
        chicken.targetTasks.taskEntries.clear();
        v.owned.add(chicken);
        helper.spawnEntity(chicken, 15.5, 2, 4.5);
        int[] phase = { 0 }, enabled = { -1 };
        long[] left = { 0 };
        boolean[] done = { false };
        helper.onEachTick("capacity, cursor/permission/NBT and real 3000ms expiry", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (v.seat() == null) return;
                v.add(chicken);
                helper.assertTrue(v.seat().riddenByEntity == chicken, "Second real seat was not occupied");
                f.maid.setSitting(false);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                helper.assertTrue(
                    f.maid.ridingEntity == null && f.core.getActor()
                        .peekWalkTarget() == null,
                    "Full vehicle admitted a maid or created a vehicle approach");
                if (age < 8) return;
                var menu = new ContainerMaid(f.owner.inventory, f.maid);
                f.owner.inventory.setItemStack(new ItemStack(Items.diamond));
                helper.assertFalse(
                    menu.enchantItem(f.owner, ContainerMaid.ACTION_RIDEABLE),
                    "Cursor-occupied ride command was accepted");
                f.owner.inventory.setItemStack(null);
                helper.assertTrue(
                    menu.enchantItem(f.owner, ContainerMaid.ACTION_RIDEABLE) && !f.maid.isRideable(),
                    "Owner ride permission command failed");
                var config = f.maid.getConfigManager();
                helper.assertTrue(
                    config.getSoundFreq() == .6F && config.getPickupType()
                        .ordinal() == 2
                        && config.isOpenDoor()
                        && !config.isOpenFenceGate()
                        && config.isActiveClimbing()
                        && config.isShowBackpack()
                        && config.isShowBackItem()
                        && !config.isChatBubbleShow(),
                    "Appending rideable metadata discarded existing fields");
                var saved = new NBTTagCompound();
                f.maid.writeEntityToNBT(saved);
                helper.assertTrue(
                    saved.hasKey("MaidIsRideable", 1) && !saved.getBoolean("MaidIsRideable"),
                    "Ride permission did not use the original top-level NBT key");
                var clone = new EntityMaid(helper.getWorld());
                try {
                    clone.readEntityFromNBT(saved);
                    helper.assertFalse(clone.isRideable(), "False ride permission was lost on reload");
                } finally {
                    clone.setDead();
                }
                chicken.mountEntity(null);
                chicken.setDead();
                f.owner.mountEntity(null);
                left[0] = System.nanoTime();
                v.add(f.maid); // 显式骑乘输入，隔离已过期窗口与自动加入两种机制
                f.owner.setPosition(v.boat.posX + 1, f.maid.posY, v.boat.posZ);
                phase[0] = 2;
            } else if (phase[0] == 2) {
                helper.assertTrue(
                    f.maid.ridingEntity != null && !f.maid.isRideable(),
                    "Disabled AI ride permission changed an independently supplied passenger relation");
                if (System.nanoTime() - left[0] < 3_100_000_000L) return;
                helper.assertFalse(
                    ((MaidPlayerVehicleState) f.owner).tlmnh$inDismountCooldown(),
                    "Player dismount window followed game ticks instead of elapsed milliseconds");
                f.maid.setRideable(true);
                enabled[0] = age;
                phase[0] = 3;
            } else if (age >= enabled[0] + 2) {
                helper.assertTrue(f.maid.ridingEntity != null, "Expired owner window still forced a dismount");
                f.owner.setPosition(v.boat.posX + 1, f.maid.posY, v.boat.posZ);
                helper.assertTrue(
                    new ContainerMaid(f.owner.inventory, f.maid).enchantItem(f.owner, ContainerMaid.ACTION_RIDEABLE)
                        && !f.maid.isRideable()
                        && f.maid.ridingEntity == null,
                    "Menu permission-off did not disembark the current maid");
                done[0] = true;
                System.out.println("TLMNH_VEHICLE107_CAPACITY_FLAGS_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 220, batch = "modern_vehicle_cancel")
    public static void liveApproachCancellationRejectsOldVehicleViewAfterResumeAndCanBoardFresh(GameTestHelper helper) {
        var v = new Fixture(helper);
        var f = v.f;
        Vehicle[] old = { null };
        int[] phase = { 0 }, stopped = { -1 };
        boolean[] done = { false };
        helper.onEachTick("live vehicle route cancellation and stale frame view after a fresh token", () -> {
            f.check();
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;
            if (phase[0] == 0 && f.maid.posX >= f.x + 9.3
                && !f.navigation.noPath()
                && f.maid.ridingEntity == null
                && actor.peekWalkTarget() != null) {
                old[0] = actor.ownerControlledVehicle();
                helper.assertTrue(old[0] != null, "Controlled vehicle view missing on live approach");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                stopped[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1) {
                helper.assertTrue(
                    f.navigation.noPath() && f.maid.ridingEntity == null,
                    "Suspended vehicle approach kept moving or boarded");
                if (age >= stopped[0] + 3) {
                    // 外部船位移是输入；恢复后新帧面对同一艘已在三格内的船
                    v.boat.setPosition(f.x + 11.8, f.y + 2, f.z + 4.5);
                    f.maid.setRideable(false);
                    f.maid.getBrainCoordinator()
                        .setSuspended(false);
                    phase[0] = 2;
                }
            } else if (phase[0] == 2) {
                f.maid.setRideable(true);
                boolean rejected = false;
                try {
                    actor.startRiding(old[0]);
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(
                    rejected && f.maid.ridingEntity == null,
                    "Old vehicle view became authorized by the new actor frame");
                phase[0] = 3;
            } else if (phase[0] == 3 && f.maid.ridingEntity != null) {
                v.assertPassenger();
                done[0] = true;
                System.out.println("TLMNH_VEHICLE107_CANCEL_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    /** 只读真实目标对象身份，不能凭速度或坐标反推哪项行为持有WALK */
    private static boolean tracksEntity(Object walk, Entity expected) {
        try {
            var field = walk.getClass()
                .getDeclaredField("entity");
            field.setAccessible(true);
            return field.get(walk) == expected;
        } catch (NoSuchFieldException otherTarget) {
            return false;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot inspect actual WALK target identity", failure);
        }
    }

    static final class Fixture {

        final GameTestHelper helper;
        final List<Entity> owned = new ArrayList<>();
        ModernCoreTests.Fixture f;
        Entity boat;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            // 框架按注册顺序清理，必须在标准fixture关闭玩家内存通道前解除真实骑乘关系
            helper.afterTest(this::cleanup);
            f = new ModernCoreTests.Fixture(helper, false, 10000, 16);
            f.maid.setSchedule(MaidSchedule.DAY);
            for (int x = 1; x <= 19; x++) for (int z = 1; z <= 7; z++) helper.setBlock(x, 0, z, Blocks.stone);
            for (int x = 1; x <= 19; x++) for (int z = 1; z <= 7; z++) helper
                .setBlock(x, 1, z, x <= 10 ? Blocks.stone : x == 19 || z == 1 || z == 7 ? Blocks.glass : Blocks.water);
            f.maid.setPosition(f.x + 7.5, f.y + 2, f.z + 4.5);
            f.previousX = f.maid.posX;
            f.previousY = f.maid.posY;
            f.previousZ = f.maid.posZ;
            try {
                boat = (Entity) Class.forName("ganymedes01.etfuturum.entities.EntityNewBoat")
                    .getConstructor(World.class)
                    .newInstance(helper.getWorld());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            owned.add(boat);
            helper.spawnEntity(boat, 13.5, 2, 4.5);
            add(f.owner);
            helper.assertTrue(boat.riddenByEntity == f.owner, "Actual owner must control the real boat");
        }

        void add(Entity entity) {
            invoke(boat, "addToBoat", new Class<?>[] { Entity.class }, entity);
        }

        Entity seat() {
            return (Entity) invoke(boat, "getSeat", new Class<?>[0]);
        }

        void assertPassenger() {
            assertPassenger(f.core.getActor());
        }

        void assertPassenger(com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor actor) {
            var seat = seat();
            helper.assertTrue(
                boat.riddenByEntity == f.owner && seat != null
                    && seat.riddenByEntity == f.maid
                    && f.maid.ridingEntity == seat,
                "Maid displaced the owner or did not join the real passenger seat");
            var view = actor.ownerControlledVehicle();
            helper.assertTrue(
                view != null && actor.sameMaidVehicle(view),
                "Seat proxy was not normalized to the owner's controlled boat");
        }

        void cleanup() {
            if (f == null) return;
            f.maid.getBrainCoordinator()
                .setSuspended(true);
            if (f.owner.ridingEntity != null) f.owner.mountEntity(null);
            if (f.maid.ridingEntity != null) f.maid.mountEntity(null);
            if (boat != null) {
                var seat = seat();
                if (seat != null) owned.add(seat);
            }
            for (Entity entity : owned) {
                if (entity.ridingEntity != null) entity.mountEntity(null);
                if (entity.riddenByEntity != null) entity.riddenByEntity.mountEntity(null);
                entity.setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(entity);
            }
            f.owner.inventory.setItemStack(null);
        }

        private Object invoke(Object object, String method, Class<?>[] types, Object... args) {
            try {
                return object.getClass()
                    .getMethod(method, types)
                    .invoke(object, args);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(method, failure);
            }
        }
    }
}
