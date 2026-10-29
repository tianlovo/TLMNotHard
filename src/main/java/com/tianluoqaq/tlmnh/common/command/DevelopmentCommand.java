package com.tianluoqaq.tlmnh.common.command;

import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.MaidPersistenceProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

/** 只在显式 JVM 开关下注册，供真实 Forge 环境验证实体和 NBT */
public final class DevelopmentCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "tlmnhdev";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "commands.tlmnhdev.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 1 && "state-test".equals(args[0])) {
            testState(sender);
            return;
        }
        if (args.length == 1 && "persist-create".equals(args[0])) {
            MaidPersistenceProbe.create(sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.persist_created"));
            return;
        }
        if (args.length == 1 && "persist-check".equals(args[0])) {
            MaidPersistenceProbe.verify(sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.persist_passed"));
            return;
        }
        throw new WrongUsageException(getCommandUsage(sender));
    }

    private static void testState(ICommandSender sender) {
        var original = new EntityMaid(sender.getEntityWorld());
        var owner = UUID.fromString("58103183-d81d-4208-9efe-67e06ba30a12");
        require(original.bindTo(owner), "first owner binding");
        require(!original.bindTo(UUID.randomUUID()), "owner transfer blocked");
        original.setCustomNameTag("TLMNH state test");
        original.setHealth(13.5F);
        original.setSitting(true);
        original.setTask(MaidTask.RANGED_ATTACK);
        original.setSchedule(MaidSchedule.NIGHT);
        original.setExperience(155);
        original.setHunger(17);
        original.setFavorability(23);
        original.setPickupItems(false);
        original.setPickupExperience(true);
        var inventory = original.getMaidInventory();
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
            var stack = new ItemStack(Items.arrow, slot + 1);
            stack.setStackDisplayName("slot-" + slot);
            inventory.setInventorySlotContents(slot, stack);
        }
        inventory.setInventorySlotContents(MaidInventory.MAIN_HAND, new ItemStack(Items.bow, 1, 11));
        inventory.setInventorySlotContents(MaidInventory.OFF_HAND, new ItemStack(Items.golden_apple, 3));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START, new ItemStack(Items.iron_boots, 1, 4));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 1, new ItemStack(Items.iron_leggings));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 2, new ItemStack(Items.iron_chestplate));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 3, new ItemStack(Items.iron_helmet));
        require(
            !inventory.isItemValidForSlot(MaidInventory.ARMOR_START, new ItemStack(Items.iron_helmet)),
            "armor slot restriction");

        var snapshot = new NBTTagCompound();
        original.writeToNBT(snapshot);
        var restored = new EntityMaid(sender.getEntityWorld());
        restored.readFromNBT(snapshot);
        require(
            restored.getUniqueID()
                .equals(original.getUniqueID()),
            "entity UUID");
        require(
            restored.func_152113_b()
                .equals(owner.toString()) && restored.isTamed(),
            "owner UUID");
        require(restored.isSitting(), "sitting");
        require(restored.getHealth() == original.getHealth(), "health");
        require(
            restored.getCustomNameTag()
                .equals(original.getCustomNameTag()),
            "name");
        require(
            restored.getModelId()
                .equals(original.getModelId()),
            "model ID");
        require(
            restored.getTask() == MaidTask.RANGED_ATTACK && restored.getSchedule() == MaidSchedule.NIGHT,
            "task and schedule");
        require(
            restored.getExperience() == 155 && restored.getHunger() == 17 && restored.getFavorability() == 23,
            "life state");
        require(!restored.canPickupItems() && restored.canPickupExperience(), "pickup flags");
        for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) {
            require(
                ItemStack.areItemStacksEqual(
                    inventory.getStackInSlot(slot),
                    restored.getMaidInventory()
                        .getStackInSlot(slot)),
                "inventory slot " + slot);
        }
        var offhand = restored.getOffhandItem();
        offhand.stackSize--;
        restored.syncOffhand();
        require(
            restored.getDataWatcher()
                .getWatchableObjectItemStack(27).stackSize == 2,
            "offhand in-place update");

        // 缺省和损坏枚举不能使区块读取崩溃，扩展区中的未知数据仍保留
        snapshot.setString("MaidScheduleMode", "invalid-schedule");
        snapshot.setString("MaidTask", "invalid-task");
        snapshot.setInteger(EntityMaid.EXPERIENCE_TAG, -1);
        snapshot.getCompoundTag("TLMNH")
            .setString("FutureField", "retained");
        restored.readFromNBT(snapshot);
        require(
            restored.getSchedule() == MaidSchedule.DAY && restored.getTask() == MaidTask.IDLE,
            "invalid enum fallback");
        require(restored.getExperience() == 0, "negative XP fallback");
        var next = new NBTTagCompound();
        restored.writeToNBT(next);
        require(
            "retained".equals(
                next.getCompoundTag("TLMNH")
                    .getString("FutureField")),
            "unknown extension retention");
        TLMNotHard.LOG.info(
            "TLMNH_STATE_TEST_PASS slots={} owner=immutable offhand=dirty-tracked nbt=roundtrip",
            MaidInventory.TOTAL_SIZE);
        sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.passed"));
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH state test failed: " + name);
    }
}
