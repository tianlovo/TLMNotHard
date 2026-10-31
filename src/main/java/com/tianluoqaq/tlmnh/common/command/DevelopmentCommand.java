package com.tianluoqaq.tlmnh.common.command;

import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.BuiltinModelProbe;
import com.tianluoqaq.tlmnh.common.development.MaidArrowPhysicsProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBowProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBrainProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCombatProbe;
import com.tianluoqaq.tlmnh.common.development.MaidContainerProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowCoreAdapterProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowTeleportProbe;
import com.tianluoqaq.tlmnh.common.development.MaidGeckoLibraryProbe;
import com.tianluoqaq.tlmnh.common.development.MaidHomeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidNamingProbe;
import com.tianluoqaq.tlmnh.common.development.MaidNativePhaseProbe;
import com.tianluoqaq.tlmnh.common.development.MaidPersistenceProbe;
import com.tianluoqaq.tlmnh.common.development.MaidPolicyLifecycleProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldNativeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidTelemetryProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleProbe;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleRestartProbe;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

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
        // 状态与匹配UUID的中止必须可在事务占用期间到达，不受下面的普通命令门禁吞掉
        if (args.length == 1 && "shield-runtime-status".equals(args[0])) {
            sender.addChatMessage(
                new ChatComponentTranslation(
                    "commands.tlmnhdev.shield_runtime_status",
                    MaidShieldRuntimeProbe.status()
                        .toString()));
            return;
        }
        if (args.length == 2 && "shield-runtime-abort".equals(args[0])) {
            MaidShieldRuntimeProbe.abort(args[1]);
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.shield_runtime_aborting"));
            return;
        }
        if (MaidBowRuntimeProbe.isFixtureReserved() || MaidLifeRuntimeProbe.isFixtureReserved()
            || MaidShieldRuntimeProbe.isActive())
            throw new IllegalStateException("A natural runtime experiment owns the development fixture");
        if (args.length == 1 && "shield-runtime-test".equals(args[0])) {
            String run = MaidShieldRuntimeProbe.start((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.shield_runtime_started", run));
            return;
        }
        if (args.length == 1 && "shield-native-test".equals(args[0])) {
            MaidShieldNativeProbe.run((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 1 && "follow-core-adapter-test".equals(args[0])) {
            MaidFollowCoreAdapterProbe.run((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 1 && "follow-fallback-test".equals(args[0])) {
            MaidFollowCoreAdapterProbe.runFallback((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 1 && "navigation-stop-test".equals(args[0])) {
            MaidFollowCoreAdapterProbe.runNavigationStop((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 1 && "native-ai-phase-test".equals(args[0])) {
            MaidNativePhaseProbe.run((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 2 && "native-ai-phase-test".equals(args[0]) && "early_component".equals(args[1])) {
            MaidNativePhaseProbe.run((WorldServer) sender.getEntityWorld(), true);
            return;
        }
        if (args.length == 2 && "follow-teleport-replay".equals(args[0])) {
            MaidFollowTeleportProbe.run((WorldServer) sender.getEntityWorld(), args[1]);
            return;
        }
        if ((args.length == 1 || args.length == 2) && "bow-runtime-test".equals(args[0])) {
            MaidBowRuntimeProbe.start((WorldServer) sender.getEntityWorld(), recordingRequested(args));
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.bow_runtime_started"));
            return;
        }
        if ((args.length == 1 || args.length == 2) && "life-runtime-test".equals(args[0])) {
            MaidLifeRuntimeProbe.start((WorldServer) sender.getEntityWorld(), recordingRequested(args));
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.life_runtime_started"));
            return;
        }
        if (args.length == 1 && "gecko-library-test".equals(args[0])) {
            MaidGeckoLibraryProbe.run();
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.gecko_library_passed"));
            return;
        }
        if (args.length == 1 && "telemetry-native-test".equals(args[0])) {
            MaidTelemetryProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.telemetry_native_passed"));
            return;
        }
        if (args.length == 1 && "policy-lifecycle-test".equals(args[0])) {
            MaidPolicyLifecycleProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.policy_lifecycle_passed"));
            return;
        }
        if (args.length == 1 && "lifecycle-persist-create".equals(args[0])) {
            MaidLifecycleRestartProbe.create((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lifecycle_restart_pending"));
            return;
        }
        if (args.length == 1 && "lifecycle-persist-check".equals(args[0])) {
            MaidLifecycleRestartProbe.verify((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lifecycle_restart_passed"));
            return;
        }
        if (args.length == 1 && "lifecycle-test".equals(args[0])) {
            MaidLifecycleProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lifecycle_passed"));
            return;
        }
        if (args.length == 1 && "bow-core-test".equals(args[0])) {
            MaidBowProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.bow_core_passed"));
            return;
        }
        if (args.length == 1 && "life-test".equals(args[0])) {
            MaidLifeProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.life_passed"));
            return;
        }
        if (args.length == 1 && "home-test".equals(args[0])) {
            MaidHomeProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.home_passed"));
            return;
        }
        if (args.length == 1 && "lab-world-test".equals(args[0])) {
            AnimationLab.verifyWorld();
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lab_world_passed"));
            return;
        }
        if (args.length >= 1 && args.length <= 3 && "lab-enter".equals(args[0])) {
            var player = args.length >= 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender);
            AnimationLab.enter(player, args.length == 3 ? args[2] : "geckolib:winefox_hanfu");
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lab_entered"));
            return;
        }
        if (args.length >= 1 && args.length <= 2 && "lab-exit".equals(args[0])) {
            AnimationLab.leave(args.length == 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender));
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lab_left"));
            return;
        }
        if (args.length == 2 && "lab-observer-test".equals(args[0])) {
            AnimationLab.verifyObserver(getPlayer(sender, args[1]));
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.lab_observer_passed"));
            return;
        }
        if (args.length == 2 && "lab-open-original-gui".equals(args[0])) {
            AnimationLab.openOriginalGui(getPlayer(sender, args[1]));
            return;
        }
        if (args.length == 2 && "lab-open-gui".equals(args[0])) {
            AnimationLab.openOwnerGui(getPlayer(sender, args[1]));
            return;
        }
        if (args.length == 1 && "model-test".equals(args[0])) {
            BuiltinModelProbe.run(sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.model_passed"));
            return;
        }
        if (args.length == 1 && "interaction-test".equals(args[0])) {
            MaidInteractionProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.interaction_passed"));
            return;
        }
        if (args.length == 1 && "sound-test".equals(args[0])) {
            MaidCoreInteractionProbe.runSound((WorldServer) sender.getEntityWorld());
            return;
        }
        if (args.length == 1 && "core-interactions-test".equals(args[0])) {
            MaidCoreInteractionProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.core_interactions_passed"));
            return;
        }
        if (args.length == 1 && "brain-test".equals(args[0])) {
            MaidBrainProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.brain_passed"));
            return;
        }
        if (args.length == 1 && "action-control-test".equals(args[0])) {
            MaidBrainProbe.runActionControl((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.brain_passed"));
            return;
        }
        if (args.length == 1 && "owner-sensor-test".equals(args[0])) {
            MaidBrainProbe.runOwnerSensor((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.brain_passed"));
            return;
        }
        if (args.length == 1 && "danger-sensor-test".equals(args[0])) {
            MaidBrainProbe.runDangerSensor((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.brain_passed"));
            return;
        }
        if (args.length == 1 && "naming-test".equals(args[0])) {
            MaidNamingProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.naming_passed"));
            return;
        }
        if (args.length == 1 && "combat-core-test".equals(args[0])) {
            MaidCombatProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.combat_core_passed"));
            return;
        }
        if (args.length == 1 && "arrow-physics-test".equals(args[0])) {
            MaidArrowPhysicsProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.arrow_physics_passed"));
            return;
        }
        if (args.length == 2 && "follow-runtime-test".equals(args[0])) {
            MaidFollowRuntimeProbe.start(getPlayer(sender, args[1]));
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.follow_runtime_started"));
            return;
        }
        if (args.length >= 1 && args.length <= 3 && "spawn".equals(args[0])) {
            var player = args.length >= 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender);
            var maid = new EntityMaid(player.worldObj);
            if (args.length == 3) {
                if (!BuiltinModelCatalog.contains(args[2])) throw new WrongUsageException(getCommandUsage(sender));
                maid.setModelId(args[2]);
            }
            maid.bindTo(player.getUniqueID());
            maid.setPosition(player.posX + 1.5D, player.posY, player.posZ);
            if (!player.worldObj.spawnEntityInWorld(maid))
                throw new IllegalStateException("TLMNH development maid spawn rejected");
            TLMNotHard.LOG.info(
                "TLMNH_DEV_MAID_SPAWN uuid={} model={} dimension={}",
                maid.getUniqueID(),
                maid.getModelId(),
                maid.dimension);
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.spawned"));
            return;
        }
        if (args.length == 1 && "container-test".equals(args[0])) {
            MaidContainerProbe.run((WorldServer) sender.getEntityWorld());
            sender.addChatMessage(new ChatComponentTranslation("commands.tlmnhdev.container_passed"));
            return;
        }
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

    private boolean recordingRequested(String[] args) {
        if (args.length == 1 || "record".equals(args[1])) return true;
        if ("plain".equals(args[1])) return false;
        throw new WrongUsageException("commands.tlmnhdev.usage");
    }

    private static void testState(ICommandSender sender) {
        var original = new EntityMaid(sender.getEntityWorld());
        var owner = UUID.fromString("58103183-d81d-4208-9efe-67e06ba30a12");
        require(original.bindTo(owner), "first owner binding");
        require(!original.bindTo(UUID.randomUUID()), "owner transfer blocked");
        original.setCustomNameTag("TLMNH state test");
        original.setHealth(13.5F);
        original.setSitting(true);
        for (var pose : MaidPose.values()) {
            require(!original.hasPose(pose), "initial pose " + pose);
            original.setPose(pose, true);
            require(original.hasPose(pose), "active pose " + pose);
        }
        original.setPose(MaidPose.BEGGING, false);
        require(!original.hasPose(MaidPose.BEGGING) && original.hasPose(MaidPose.SLEEPING), "independent pose flags");
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
        for (var pose : MaidPose.values()) require(!restored.hasPose(pose), "transient pose " + pose);
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
