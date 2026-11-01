package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernRefreshTests {

    private ModernRefreshTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_gui_use")
    public static void realContainerPausesBrainButContinuesBowUseThenRelease(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.RANGED_ATTACK);
        var pig = target(helper, f);
        ContainerMaid[] menu = { null };
        helper.afterTest(() -> { if (menu[0] != null) menu[0].onContainerClosed(f.owner); });
        int[] opened = { -1 }, used = { -1 }, phase = { 0 };
        long[] scans = { -1 }, shots = { -1 };
        Object[] lease = { null }, token = { null };
        boolean[] done = { false };
        helper.onEachTick("actual server container, frozen Brain memory and continuing native use", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            var uses = f.maid.getItemUseState();
            if (phase[0] == 0 && uses.getUsedTicks() >= 3 && f.maid.getAttackTarget() == pig) {
                opened[0] = age;
                used[0] = uses.getUsedTicks();
                scans[0] = f.core.getPerception()
                    .livingScanCount();
                shots[0] = actor.rangedBow()
                    .getShots();
                lease[0] = uses.peek();
                token[0] = f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .getContext()
                    .getToken();
                f.maid.getModernCombatMemory()
                    .coolDown(17);
                f.owner.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ + 1);
                menu[0] = new ContainerMaid(f.owner.inventory, f.maid);
                f.owner.openContainer = menu[0];
                phase[0] = 1;
            } else if (phase[0] == 1) {
                helper.assertTrue(
                    f.maid.guiOpening && f.maid.getModernCombatMemory()
                        .remaining() == 17
                        && scans[0] == f.core.getPerception()
                            .livingScanCount()
                        && shots[0] == actor.rangedBow()
                            .getShots()
                        && uses.peek() == lease[0]
                        && uses.getUsedTicks() == used[0] + age - opened[0]
                        && token[0] == f.maid.getBrainCoordinator()
                            .peekPolicyRuntime()
                            .getContext()
                            .getToken(),
                    "GUI cancelled use/epoch, advanced Brain TTL/sensor or fired while paused");
                if (age == opened[0] + 10) {
                    var oldMenu = menu[0];
                    helper.assertTrue(oldMenu.canInteractWith(f.owner), "GUI owner lost container access");
                    // 标准FakePlayer.openGui为空，直接进入实际FML流程，保留构造/关闭/安装顺序
                    cpw.mods.fml.common.network.internal.FMLNetworkHandler.openGui(
                        f.owner,
                        com.tianluoqaq.tlmnh.TLMNotHard.INSTANCE,
                        com.tianluoqaq.tlmnh.common.proxy.CommonProxy.GUI_MAID_ORIGINAL,
                        f.maid.worldObj,
                        f.maid.getEntityId(),
                        0,
                        0);
                    helper.assertTrue(
                        f.owner.openContainer instanceof ContainerMaid && f.owner.openContainer != oldMenu,
                        "FML did not install the new original page");
                    menu[0] = (ContainerMaid) f.owner.openContainer;
                    oldMenu.onContainerClosed(f.owner);
                    helper.assertTrue(f.maid.guiOpening, "Old close callback released the new GUI pause");
                }
                if (age >= opened[0] + 25) {
                    menu[0].onContainerClosed(f.owner);
                    menu[0] = null;
                    f.owner.openContainer = f.owner.inventoryContainer;
                    opened[0] = age;
                    phase[0] = 2;
                }
            } else if (phase[0] == 2 && actor.rangedBow()
                .getShots() > shots[0] && pig.getHealth() < 200) {
                    helper.assertTrue(
                        !f.maid.guiOpening && f.maid.getOffhandItem().stackSize == 15
                            && f.maid.getHeldItem()
                                .getItemDamage() == 1
                            && age <= opened[0] + 8,
                        "Closing real container did not release completed draw once with finite resources");
                    done[0] = true;
                    System.out.println("TLMNH_REFRESH113_GUI_USE_PASS age=" + age + " pausedUse=" + used[0] + "+25");
                }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_refresh_memory")
    public static void taskRefreshStopsRunningMoveButKeepsWorkingCombatMemoryAndRejectsOldView(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.ATTACK);
        var pig = target(helper, f);
        var point = new Point(f.x + 3, f.y + 1, f.z + 3);
        ContainerMaid[] menu = { null };
        helper.afterTest(() -> { if (menu[0] != null) menu[0].onContainerClosed(f.owner); });
        MaidCombatActor.Target[] old = { null };
        int[] changed = { -1 }, phase = { 0 };
        long[] count = { -1 };
        boolean[] done = { false };
        helper.onEachTick("source stopAll, copied working memory and fresh action views", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (phase[0] == 0 && f.core.getMoveState()
                .getStatus() == FollowMoveState.Status.RUNNING && f.maid.getAttackTarget() == pig) {
                old[0] = actor.attackTarget();
                actor.setWorkTarget(point);
                actor.setEdibleAction(MaidEdibleBlockAction.TRY_PLACE);
                actor.setCantReachSince(f.maid.worldObj.getTotalWorldTime() - 10);
                f.maid.getModernCombatMemory()
                    .coolDown(17);
                count[0] = f.core.refreshes();
                f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
                f.maid.setTask(MaidTask.RANGED_ATTACK);
                helper.assertTrue(
                    f.core.refreshes() == count[0] + 1 && f.core.getMoveState()
                        .getStatus() == FollowMoveState.Status.STOPPED
                        && f.navigation.noPath()
                        && actor.peekWalkTarget() == null
                        && actor.peekPathMemory() == null
                        && actor.peekWorkTarget() == point
                        && actor.peekEdibleAction() == MaidEdibleBlockAction.TRY_PLACE
                        && actor.peekCantReachSince() != null
                        && f.maid.getModernCombatMemory()
                            .target() == pig
                        && f.maid.getModernCombatMemory()
                            .remaining() == 17,
                    "Refresh cleared copied work/combat memory or kept running movement");
                f.maid.setTask(MaidTask.RANGED_ATTACK);
                helper.assertTrue(f.core.refreshes() == count[0] + 1, "Identical task unnecessarily refreshed Brain");
                f.owner.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ + 1);
                menu[0] = new ContainerMaid(f.owner.inventory, f.maid);
                f.owner.openContainer = menu[0];
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changed[0] + 3) {
                boolean rejected = false;
                try {
                    actor.performRangedAttack(old[0], 1);
                } catch (IllegalArgumentException | IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(
                    rejected && actor.peekWorkTarget() == point
                        && actor.peekEdibleAction() == MaidEdibleBlockAction.TRY_PLACE,
                    "Old action view revived or copied work memory was discarded");
                f.maid.setSchedule(MaidSchedule.ALL);
                helper.assertTrue(
                    f.core.refreshes() == count[0] + 2 && actor.peekWorkTarget() == point
                        && actor.peekEdibleAction() == MaidEdibleBlockAction.TRY_PLACE
                        && f.maid.getModernCombatMemory()
                            .remaining() == 17,
                    "Schedule refresh while GUI paused lost copied memory or ticked Brain TTL");
                menu[0].onContainerClosed(f.owner);
                menu[0] = null;
                f.owner.openContainer = f.owner.inventoryContainer;
                phase[0] = 2;
            } else if (phase[0] == 2 && actor.rangedBow()
                .getShots() > 0 && pig.getHealth() < 200) {
                    helper.assertTrue(f.core.refreshes() == count[0] + 2, "Task change caused repeated refresh");
                    done[0] = true;
                    System.out.println("TLMNH_REFRESH113_MEMORY_PASS age=" + age + " refreshes=" + f.core.refreshes());
                }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "modern_gui_schedule")
    public static void openContainerKeepsEntityRestrictionLatchWhileBrainActivityWaits(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.IDLE);
        var area = f.maid.getHomeArea();
        place(f, 4.5);
        helper.assertTrue(area.setEnabled(true), "Home setup failed");
        place(f, 14.5);
        helper.assertTrue(area.capture(MaidLifeRules.Activity.IDLE), "Idle anchor setup failed");
        place(f, 4.5);
        f.maid.setSchedule(MaidSchedule.DAY);
        ContainerMaid[] menu = { null };
        helper.afterTest(() -> { if (menu[0] != null) menu[0].onContainerClosed(f.owner); });
        int[] phase = { 0 };
        boolean[] done = { false };
        helper.onEachTick("entity SchedulePos continues independently from paused Brain", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            if (phase[0] == 0 && age == 5) {
                f.owner.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ + 1);
                menu[0] = new ContainerMaid(f.owner.inventory, f.maid);
                f.owner.openContainer = menu[0];
                helper.getWorld()
                    .setWorldTime(12000);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                helper.assertTrue(
                    f.core.getActivities()
                        .active() == com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity.WORK,
                    "Brain activity changed while real container remained open");
                if (age < 40) helper
                    .assertTrue(area.restrictionCenter().x == f.x + 4, "Restriction changed before entity40 latch");
                if (age == 40) {
                    helper.assertTrue(
                        area.restrictionCenter().x == f.x + 14,
                        "GUI pause also stopped independent entity SchedulePos");
                    menu[0].onContainerClosed(f.owner);
                    menu[0] = null;
                    f.owner.openContainer = f.owner.inventoryContainer;
                    phase[0] = 2;
                }
            } else if (phase[0] == 2 && f.core.getActivities()
                .active() == com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity.IDLE) {
                    done[0] = true;
                    System.out.println("TLMNH_REFRESH113_GUI_SCHEDULE_PASS age=" + age);
                }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static void place(ModernCoreTests.Fixture f, double x) {
        f.maid.setPosition(f.x + x, f.y + 1, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, MaidTask task) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 24);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(task);
        if (task != MaidTask.IDLE)
            f.maid.setCurrentItemOrArmor(0, new ItemStack(task == MaidTask.ATTACK ? Items.iron_sword : Items.bow));
        f.maid.setOffhandItem(new ItemStack(Items.arrow, 16));
        f.owner.setPosition(f.x + 9.5, f.y + 1, f.z + 5.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EntityPig target(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        helper.spawnEntity(pig, 9.5, 1, 4.5);
        helper.afterTest(pig::setDead);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        return pig;
    }
}
