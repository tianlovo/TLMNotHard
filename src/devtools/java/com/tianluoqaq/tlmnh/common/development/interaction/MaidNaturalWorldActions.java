package com.tianluoqaq.tlmnh.common.development.interaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.network.MaidSoundNetwork;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

/** 复用照料会话的临时角色与玩家原槽保全；只准备原生交互/自然tick的输入 */
final class MaidNaturalWorldActions {

    private static final String KEY = "WorldActions";
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList("Schema", "Stage", "Inventory", "MealIntermediate", "MealResult"));
    private static final Set<String> STAGES = new HashSet<>(
        Arrays.asList("equipped", "carry-ready", "carried", "meal", "meal-finished"));

    private MaidNaturalWorldActions() {}

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(data.hasKey(KEY, Constants.NBT.TAG_COMPOUND), "Unknown world-action snapshot retained");
        require(
            data.getString("Phase")
                .equals("world-actions"),
            "Unknown world-action phase retained");
        var world = data.getCompoundTag(KEY);
        for (Object key : world.func_150296_c()) require(FIELDS.contains(key), "Unknown world-action field retained");
        require(
            world.hasKey("Schema", Constants.NBT.TAG_INT) && world.getInteger("Schema") == 1
                && world.hasKey("Stage", Constants.NBT.TAG_STRING)
                && STAGES.contains(world.getString("Stage")),
            "Unknown world-action stage retained");
        for (String key : new String[] { "Inventory", "MealIntermediate", "MealResult" }) require(
            world.hasKey(key, Constants.NBT.TAG_LIST)
                && ((NBTTagList) world.getTag(key)).tagCount() == world.getTagList(key, Constants.NBT.TAG_COMPOUND)
                    .tagCount(),
            "Unknown world-action inventory retained");
    }

    private static NBTTagList inventory(Session session) {
        var result = new NBTTagList();
        var inventory = session.maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var item = inventory.getStackInSlot(slot);
            if (item != null) {
                var tag = ProtectedItemCodec.encode(item);
                tag.setByte("Slot", (byte) slot);
                result.appendTag(tag);
            }
        }
        return result;
    }

    static void verifyInventory(Session session) {
        validateSnapshot(session.data);
        var world = session.data.getCompoundTag(KEY);
        var actual = inventory(session);
        require(
            actual.equals(world.getTagList("Inventory", Constants.NBT.TAG_COMPOUND)) || world.getString("Stage")
                .equals("meal")
                && (actual.equals(world.getTagList("MealResult", Constants.NBT.TAG_COMPOUND))
                    || actual.equals(world.getTagList("MealIntermediate", Constants.NBT.TAG_COMPOUND))),
            "Unknown world-action equipment successor retained");
    }

    private static ItemStack owned(Session session, Item item) {
        var stack = new ItemStack(item);
        var tag = new NBTTagCompound();
        tag.setString("TLMNHNaturalWorld", session.data.getString("Run"));
        stack.setTagCompound(tag);
        return stack;
    }

    private static void unmounted(Session session) {
        require(
            session.maid.ridingEntity == null && session.maid.riddenByEntity == null
                && session.player.riddenByEntity == null,
            "Release the exact temporary passenger first");
    }

    static void run(Session session, String action) {
        var maid = session.maid;
        if (action.equals("world-equipped-bedrock") || action.equals("world-equipped-gecko")) {
            require(
                session.data.getString("Phase")
                    .equals("prepared") || session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND),
                "Separate prepared world-action session required");
            unmounted(session);
            restore(session);
            maid.getBrainCoordinator()
                .setSuspended(true);
            require(
                maid.getItemUseState()
                    .peek() == null,
                "Foreign item-use lease retained");
            var world = new NBTTagCompound();
            world.setInteger("Schema", 1);
            world.setString("Stage", "equipped");
            world.setTag("Inventory", inventory(session));
            world.setTag("MealIntermediate", inventory(session));
            world.setTag("MealResult", inventory(session));
            session.data.setTag(KEY, world);
            var model = action.endsWith("gecko") ? "geckolib:winefox_hanfu" : "touhou_little_maid:hakurei_reimu";
            maid.setModelId(model);
            session.data.setString("ExpectedModel", model);
            maid.setHunger(20);
            maid.setSitting(false);
            maid.setLocationAndAngles(4.5, 65, .5, 180, 0);
            maid.rotationYawHead = maid.prevRotationYawHead = maid.renderYawOffset = maid.prevRenderYawOffset = 180;
            maid.getMaidInventory()
                .setInventorySlotContents(MaidInventoryLayout.MAIN_HAND, owned(session, Items.iron_sword));
            maid.getMaidInventory()
                .setInventorySlotContents(5, owned(session, Items.compass));
            maid.getConfigManager()
                .setShowBackItem(true);
            world.setTag("Inventory", inventory(session));
            world.setTag("MealIntermediate", inventory(session));
            world.setTag("MealResult", inventory(session));
            session.data.setString("Phase", "world-actions");
            session.hand(null, null);
            return;
        }
        require(
            session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND),
            "Prepare this session's world-action materials first");
        var world = session.data.getCompoundTag(KEY);
        switch (action) {
            case "world-arm-carry" -> {
                unmounted(session);
                require(
                    world.getString("Stage")
                        .equals("equipped"),
                    "Clean equipped carry input required");
                session.hand(new ItemStack(Items.saddle), null);
                // Saddle interactions do not consume the item, including its complete fixture tag.
                session.data.setTag(
                    "ResultInventory",
                    session.data.getTag("ExpectedInventory")
                        .copy());
                world.setString("Stage", "carry-ready");
            }
            case "world-verify-carried" -> {
                require(
                    world.getString("Stage")
                        .equals("carry-ready") && maid.ridingEntity == session.player
                        && session.player.riddenByEntity == maid,
                    "Actual native owner/passenger relation required");
                require(
                    session.data.getTag("ExpectedInventory")
                        .equals(session.data.getTag("ResultInventory")),
                    "Saddle material must remain unchanged");
                world.setString("Stage", "carried");
            }
            case "world-verify-released" -> {
                require(
                    world.getString("Stage")
                        .equals("carried"),
                    "Verified carried actor required");
                unmounted(session);
                session.hand(null, null);
                maid.setLocationAndAngles(4.5, 65, .5, 180, 0);
                maid.motionX = maid.motionY = maid.motionZ = 0;
                world.setString("Stage", "equipped");
            }
            case "world-arm-meal" -> {
                unmounted(session);
                require(
                    world.getString("Stage")
                        .equals("equipped")
                        && maid.getItemUseState()
                            .peek() == null,
                    "Idle native meal input required");
                session.hand(null, null);
                maid.setHealth(10);
                maid.getMaidInventory()
                    .setInventorySlotContents(MaidInventoryLayout.OFF_HAND, owned(session, Items.mushroom_stew));
                world.setTag("Inventory", inventory(session));
                var after = new NBTTagList();
                var intermediate = new NBTTagList();
                var bowl = ProtectedItemCodec.encode(new ItemStack(Items.bowl));
                bowl.setByte("Slot", (byte) 0);
                after.appendTag(bowl);
                var before = world.getTagList("Inventory", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < before.tagCount(); i++) {
                    var item = before.getCompoundTagAt(i);
                    if ((item.getByte("Slot") & 255) != 7) {
                        after.appendTag(item.copy());
                        intermediate.appendTag(item.copy());
                    }
                }
                world.setTag("MealIntermediate", intermediate);
                world.setTag("MealResult", after);
                world.setString("Stage", "meal");
                var selected = maid.getBrainCoordinator()
                    .selectProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM);
                require(
                    selected == MaidPolicyRuntime.SelectionResult.SELECTED
                        || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED,
                    "Actual full faithful factory required");
                maid.getBrainCoordinator()
                    .setSuspended(false);
            }
            case "world-verify-meal" -> {
                require(
                    world.getString("Stage")
                        .equals("meal")
                        && inventory(session).equals(world.getTagList("MealResult", Constants.NBT.TAG_COMPOUND))
                        && !maid.getMealController()
                            .isEating()
                        && maid.getItemUseState()
                            .peek() == null
                        && !maid.hasPose(MaidPose.USING_ITEM)
                        && !maid.hasPose(MaidPose.USING_OFFHAND)
                        && maid.getHealth() >= 10,
                    "Natural soup use, bowl and released lease required");
                maid.getBrainCoordinator()
                    .setSuspended(true);
                world.setTag("Inventory", inventory(session));
                world.setString("Stage", "meal-finished");
            }
            default -> sound(session, action);
        }
        verifyInventory(session);
    }

    private static void sound(Session session, String action) {
        var maid = session.maid;
        unmounted(session);
        require(
            !session.data.getCompoundTag(KEY)
                .getString("Stage")
                .equals("meal"),
            "Finish natural meal before the sound branch");
        switch (action) {
            case "world-sound-default", "world-sound-alternate" -> {
                var pack = action.endsWith("alternate") ? "littlemaid_peco" : "touhou_little_maid";
                require(
                    BuiltinSoundCatalog.get(pack) != null
                        && BuiltinSoundCatalog.get(pack).events.get("maid.mode.attack") > 0,
                    "Retained native voice pack and shared nonempty event required");
                maid.setSoundPackId(pack);
                maid.setSilent(false);
                maid.getConfigManager()
                    .setSoundFreq(1);
            }
            case "world-sound-voice" -> maid.playSound(SoundUtil.name("mode.attack"), 1, 1);
            case "world-sound-credit" -> maid.playSound(SoundUtil.name("credit"), 1, 1);
            case "world-sound-ambient" -> {
                maid.setTask(MaidTask.ATTACK);
                maid.playLivingSound();
            }
            case "world-sound-move" -> maid.setPosition(maid.posX + 2, maid.posY, maid.posZ);
            case "world-sound-frequency-0" -> maid.getConfigManager()
                .setSoundFreq(0);
            case "world-sound-frequency-1" -> maid.getConfigManager()
                .setSoundFreq(1);
            case "world-sound-silent" -> maid.setSilent(true);
            case "world-sound-unmute" -> maid.setSilent(false);
            case "world-sound-hurt" -> {
                float health = maid.getHealth();
                require(maid.attackEntityFrom(DamageSource.outOfWorld, .25F), "Native hurt callback required");
                maid.setHealth(health);
            }
            case "world-sound-stale-uuid" -> MaidSoundNetwork.send(
                maid,
                new PlayMaidSoundMessage(
                    maid.dimension,
                    maid.getEntityId(),
                    UUID.randomUUID(),
                    maid.getSoundPackId(),
                    "maid.credit"));
            default -> throw new IllegalArgumentException("Unknown owned natural-world action");
        }
    }

    static void restore(Session session) {
        if (!session.data.hasKey(KEY)) return;
        verifyInventory(session);
        if (session.maid.ridingEntity != null || session.player.riddenByEntity != null) {
            require(
                session.maid.ridingEntity == session.player && session.player.riddenByEntity == session.maid,
                "Unknown riding successor retained");
            session.maid.mountEntity(null);
        }
        require(session.maid.riddenByEntity == null, "Foreign passenger retained");
        session.maid.getBrainCoordinator()
            .setSuspended(true);
        session.maid.getMealController()
            .cancel();
        require(
            session.maid.getItemUseState()
                .peek() == null,
            "Foreign item-use lease retained");
        verifyInventory(session);
        for (int i = 0; i < session.maid.getMaidInventory()
            .getSizeInventory(); i++)
            session.maid.getMaidInventory()
                .setInventorySlotContents(i, null);
        session.data.removeTag(KEY);
    }

    static JsonObject observation(Session session) {
        var result = MaidVisibleStateSnapshot.capture(session.maid);
        result.addProperty(
            "stage",
            session.data.getCompoundTag(KEY)
                .getString("Stage"));
        result.addProperty(
            "eating",
            session.maid.getMealController()
                .isEating());
        result.addProperty("usingItem", session.maid.hasPose(MaidPose.USING_ITEM));
        result.addProperty("usingOffhand", session.maid.hasPose(MaidPose.USING_OFFHAND));
        result.addProperty("nativeInventory", inventory(session).toString());
        result.addProperty(
            "ridingOwner",
            session.maid.ridingEntity == session.player && session.player.riddenByEntity == session.maid);
        result.addProperty("x", session.maid.posX);
        result.addProperty("y", session.maid.posY);
        result.addProperty("z", session.maid.posZ);
        return result;
    }
}
