package com.tianluoqaq.tlmnh.qa;

import java.util.Collections;
import java.util.HashMap;

import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SetAttackListMessage;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.network.MaidAttackListPayload;
import com.tianluoqaq.tlmnh.common.network.MaidTaskConfigNetwork;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class TaskConfigTests {

    private TaskConfigTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "task_config_packet")
    public static void nativeWindowRejectsForgedMalformedAndUnknownTypesThenSavesExactlyOnce(GameTestHelper helper) {
        var f = fixture(helper);
        var menu = open(helper, f, 71);
        var zombie = MaidCombatEntities.id(new EntityZombie(helper.getWorld()));
        var groups = new HashMap<ResourceLocation, MonsterType>();
        groups.put(zombie, MonsterType.FRIENDLY);
        var request = new SetAttackListMessage(MaidAttackListPayload.from(menu, groups, true));
        var encoded = Unpooled.buffer();
        request.toBytes(encoded);
        try {
            var wrongWindow = encoded.copy();
            wrongWindow.setInt(1, menu.windowId + 1);
            helper.assertFalse(
                MaidTaskConfigNetwork.apply(f.owner, decode(wrongWindow)),
                "Wrong window wrote target data");
            var wrongSession = encoded.copy();
            wrongSession.setLong(5, 72);
            helper.assertFalse(
                MaidTaskConfigNetwork.apply(f.owner, decode(wrongSession)),
                "Wrong editor nonce wrote target data");
            var wrongUuid = encoded.copy();
            wrongUuid.setLong(21, wrongUuid.getLong(21) ^ 1);
            helper.assertFalse(
                MaidTaskConfigNetwork.apply(f.owner, decode(wrongUuid)),
                "Wrong entity UUID wrote target data");
            var oversize = encoded.copy();
            oversize.setShort(39, MaidAttackListPayload.MAX_ENTRIES + 1);
            rejects(helper, oversize, "Oversize target count was decoded");
            var wrongEnum = encoded.copy();
            wrongEnum.setByte(wrongEnum.writerIndex() - 1, 127);
            rejects(helper, wrongEnum, "Unknown target category was decoded");
            var duplicate = encoded.copy();
            duplicate.setShort(39, 2);
            duplicate.writeBytes(encoded, 41, encoded.writerIndex() - 41);
            rejects(helper, duplicate, "Duplicate target IDs were decoded");
            helper.assertTrue(
                f.maid.getAttackListData()
                    .attackGroups()
                    .isEmpty(),
                "Rejected input partially changed settings");
            var unknown = Collections.singletonMap(new ResourceLocation("qa:not_registered"), MonsterType.HOSTILE);
            helper.assertFalse(
                MaidTaskConfigNetwork
                    .apply(f.owner, new SetAttackListMessage(MaidAttackListPayload.from(menu, unknown, true))),
                "Unregistered new type was stored");
            f.owner.inventory.setItemStack(new ItemStack(Items.diamond));
            helper.assertFalse(MaidTaskConfigNetwork.apply(f.owner, request), "Cursor item did not block a save");
            f.owner.inventory.setItemStack(null);
            helper.assertTrue(
                MaidTaskConfigNetwork.apply(f.owner, decode(encoded.copy())),
                "Valid native editor save was rejected");
            helper.assertTrue(
                f.maid.getAttackListData()
                    .attackGroups()
                    .equals(groups),
                "Valid edit was not atomically installed");
            helper.assertFalse(
                new TaskAttack().canAttack(f.maid, new EntityZombie(helper.getWorld())),
                "Stored friendly override did not reach actual attack predicates");
            helper
                .assertFalse(MaidTaskConfigNetwork.apply(f.owner, request), "Consumed editor accepted a repeated save");
            helper.succeed();
        } finally {
            encoded.release();
        }
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "task_config_persistence")
    public static void closeThenSavePreservesUnknownNbtAndHandsWhileExpiredAndChangedSessionsCannotOverwrite(
        GameTestHelper helper) {
        var f = fixture(helper);
        var zombie = MaidCombatEntities.id(new EntityZombie(helper.getWorld()));
        f.maid.getAttackListData()
            .attackGroups()
            .put(zombie, MonsterType.HOSTILE);
        var saved = new NBTTagCompound();
        f.maid.writeToNBT(saved);
        var root = saved.getCompoundTag("MaidTaskDataMaps");
        root.setString("unknown_task", "retain-foreign-task-data");
        root.getCompoundTag("touhou_little_maid:maid_attack_list")
            .getCompoundTag("attack_groups")
            .setString("qa:future", "future_type");
        f.maid.readFromNBT(saved);
        var main = f.maid.getHeldItem()
            .copy();
        var off = f.maid.getOffhandItem()
            .copy();
        var menu = open(helper, f, 81);
        var remove = new SetAttackListMessage(MaidAttackListPayload.from(menu, Collections.emptyMap(), true));
        f.owner.closeContainer();
        helper.assertTrue(
            f.owner.openContainer != menu && MaidTaskConfigNetwork.apply(f.owner, remove),
            "Real close before queued save discarded the edit");
        var again = new NBTTagCompound();
        f.maid.writeToNBT(again);
        var after = again.getCompoundTag("MaidTaskDataMaps");
        var persisted = after.getCompoundTag("touhou_little_maid:maid_attack_list")
            .getCompoundTag("attack_groups");
        helper.assertTrue(
            !persisted.hasKey(zombie.toString()) && "future_type".equals(persisted.getString("qa:future"))
                && "retain-foreign-task-data".equals(after.getString("unknown_task")),
            "Editing known target IDs overwrote unknown NBT or retained a removed known type");
        menu = open(helper, f, 82);
        var expired = new SetAttackListMessage(
            MaidAttackListPayload.from(menu, Collections.singletonMap(zombie, MonsterType.HOSTILE), true));
        f.owner.closeContainer();
        int start = f.maid.ticksExisted;
        helper.succeedWhen(() -> {
            helper.assertTrue(
                ItemStack.areItemStacksEqual(main, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem())
                    && f.maid.getHealth() == 20,
                "Editing changed actual hands or health");
            if (f.maid.ticksExisted < start + 22) return false;
            helper
                .assertFalse(MaidTaskConfigNetwork.apply(f.owner, expired), "Expired closed window overwrote settings");
            var changed = open(helper, f, 83);
            var old = new SetAttackListMessage(
                MaidAttackListPayload.from(changed, Collections.singletonMap(zombie, MonsterType.HOSTILE), true));
            var cow = MaidCombatEntities.id(new EntityCow(helper.getWorld()));
            f.maid.getAttackListData()
                .attackGroups()
                .put(cow, MonsterType.FRIENDLY);
            helper
                .assertFalse(MaidTaskConfigNetwork.apply(f.owner, old), "Old baseline overwrote newer target settings");
            helper.assertTrue(
                f.maid.getAttackListData()
                    .attackGroups()
                    .equals(Collections.singletonMap(cow, MonsterType.FRIENDLY)),
                "Rejected stale baseline lost current values");
            f.owner.closeContainer();
            var task = open(helper, f, 84);
            var revoked = new SetAttackListMessage(MaidAttackListPayload.from(task, Collections.emptyMap(), true));
            f.maid.setTask(MaidTask.RANGED_ATTACK);
            helper.assertFalse(
                MaidTaskConfigNetwork.apply(f.owner, revoked),
                "Changed task retained old editor authority");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "task_config_navigation")
    public static void editAndTaskChangeCommitInOrderAndBothOriginalTaskPageFactoriesRemainGuarded(
        GameTestHelper helper) {
        var f = fixture(helper);
        var menu = open(helper, f, 91);
        var cow = MaidCombatEntities.id(new EntityCow(helper.getWorld()));
        var groups = Collections.singletonMap(cow, MonsterType.HOSTILE);
        var transaction = new SetAttackListMessage(
            MaidAttackListPayload.from(menu, groups, true),
            ContainerMaid.ACTION_IDLE);
        helper.assertTrue(
            MaidTaskConfigNetwork.apply(f.owner, decode(encode(transaction))) && f.maid.getTask() == MaidTask.IDLE
                && f.maid.getAttackListData()
                    .attackGroups()
                    .equals(groups),
            "Task switch ran before the target edit or lost the saved data");
        // FakePlayer.openGui本身为空；实际FML路由另行调用，不能把它当作真实客户端页面切换
        FMLNetworkHandler.openGui(
            f.owner,
            TLMNotHard.INSTANCE,
            CommonProxy.GUI_MAID_DEFAULT_TASK_CONFIG,
            f.maid.worldObj,
            f.maid.getEntityId(),
            0,
            92);
        helper.assertTrue(
            f.owner.openContainer instanceof TaskConfigContainer idle && !idle.attackPage(),
            "Original idle settings factory did not install its native container");
        helper.assertFalse(
            MaidTaskConfigNetwork.apply(f.owner, transaction),
            "Reopened page accepted the old transaction");
        var proxy = new CommonProxy();
        helper.assertTrue(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_ATTACK_CONFIG,
                f.owner,
                f.maid.worldObj,
                f.maid.getEntityId(),
                0,
                93) == null,
            "Idle task opened the attack-only settings factory");
        helper.assertTrue(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_DEFAULT_TASK_CONFIG,
                f.owner,
                f.maid.worldObj,
                f.maid.getEntityId(),
                0,
                0) == null,
            "Zero session opened a task editor");
        helper.succeed();
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setSitting(true);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.stick, 5));
        var off = new ItemStack(Items.golden_apple, 1, 1);
        off.setStackDisplayName("QA-target-settings-preserved");
        f.maid.setOffhandItem(off);
        helper.afterTest(() -> f.owner.closeContainer());
        return f;
    }

    private static TaskConfigContainer open(GameTestHelper helper, ModernCoreTests.Fixture f, long session) {
        FMLNetworkHandler.openGui(
            f.owner,
            TLMNotHard.INSTANCE,
            CommonProxy.GUI_MAID_ATTACK_CONFIG,
            f.maid.worldObj,
            f.maid.getEntityId(),
            (int) (session >>> 32),
            (int) session);
        helper.assertTrue(f.owner.openContainer instanceof TaskConfigContainer, "Native FML attack page did not open");
        var menu = (TaskConfigContainer) f.owner.openContainer;
        helper.assertTrue(
            menu.canInteractWith(f.owner) && menu.session() == session,
            "Native editor ownership/nonce differs");
        return menu;
    }

    private static ByteBuf encode(SetAttackListMessage message) {
        var buffer = Unpooled.buffer();
        message.toBytes(buffer);
        return buffer;
    }

    private static SetAttackListMessage decode(ByteBuf buffer) {
        try {
            var decoded = new SetAttackListMessage();
            decoded.fromBytes(buffer);
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void rejects(GameTestHelper helper, ByteBuf buffer, String failure) {
        boolean rejected = false;
        try {
            decode(buffer);
        } catch (io.netty.handler.codec.DecoderException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, failure);
    }
}
