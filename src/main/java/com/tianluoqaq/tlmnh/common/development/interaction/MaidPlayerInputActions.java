package com.tianluoqaq.tlmnh.common.development.interaction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidInteractionTransfer;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.compat.backhand.development.player.BackhandAccess;

/** Two actual connected players and native input transactions; only this session's generated materials may move. */
final class MaidPlayerInputActions {

    private static final String KEY = "PlayerInputs", TAG = "TLMNHPlayerInputs";
    private static final Map<String, Attempt> LIVE = new HashMap<>();
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Schema",
            "Stage",
            "Secondary",
            "SecondaryOriginal",
            "PrimaryBase",
            "SecondaryBase",
            "Materials",
            "XPSeed",
            "XPSource",
            "Denied",
            "Wild",
            "AllowedModel"));

    private MaidPlayerInputActions() {}

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static boolean handles(String action) {
        return action.startsWith("world-player-");
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(
            data.hasKey(KEY, 10) && data.getString("Phase")
                .equals("player-inputs"),
            "Unknown player input snapshot retained");
        var row = data.getCompoundTag(KEY);
        for (Object key : row.func_150296_c()) require(FIELDS.contains(key), "Unknown player input field retained");
        require(
            row.hasKey("Schema", 3) && row.getInteger("Schema") == 1
                && row.hasKey("Stage", 8)
                && row.hasKey("Secondary", 8)
                && row.hasKey("SecondaryOriginal", 10),
            "Invalid player input identity retained");
        UUID.fromString(row.getString("Secondary"));
        for (String key : new String[] { "PrimaryBase", "SecondaryBase", "Materials" }) typedList(row, key);
        require(
            row.hasKey("XPSeed", 3) && row.getInteger("XPSeed") >= 0
                && row.hasKey("XPSource", 8)
                && row.hasKey("AllowedModel", 8),
            "Unknown player input material plan retained");
        if (row.hasKey("Denied")) require(row.hasKey("Denied", 10), "Unknown denied-state snapshot retained");
        if (row.hasKey("Wild")) {
            require(row.hasKey("Wild", 8), "Invalid wild actor field");
            UUID.fromString(row.getString("Wild"));
        }
    }

    private static NBTTagList typedList(NBTTagCompound row, String key) {
        require(
            row.hasKey(key, 9) && ((NBTTagList) row.getTag(key)).tagCount() == row.getTagList(key, 10)
                .tagCount(),
            "Unknown player input native list retained: " + key);
        return row.getTagList(key, 10);
    }

    private static Attempt attempt(Session session) {
        validateSnapshot(session.data);
        var value = LIVE.get(session.data.getString("Run"));
        require(
            value != null && value.session == session && MaidCareInteractionFixture.owns(session),
            "Original live two-player input context required; all backups retained");
        return value;
    }

    private static boolean online(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(player);
    }

    static boolean isActivePlayer(Session session, EntityPlayerMP player) {
        if (session == null || !session.data.hasKey(KEY, 10)) return false;
        var value = LIVE.get(session.data.getString("Run"));
        return value != null && value.session == session
            && MaidCareInteractionFixture.owns(session)
            && player == value.secondary
            && value.secondaryMarker == player.getEntityData()
                .getTag(TAG)
            && online(player)
            && player.worldObj == session.player.worldObj
            && AnimationLab.isLab(player.worldObj)
            && session.validNonce(session.maid);
    }

    static boolean playerShape(Session session) {
        if (!session.data.hasKey(KEY)) return session.player.inventory.currentItem == session.slot()
            && session.player.inventory.getItemStack() == null;
        var value = attempt(session);
        return value.selectedOwned(session.player, session.slot()) && value.cursorOwned(session.player);
    }

    static boolean validateActor(Session session) {
        if (!session.data.hasKey(KEY)) return false;
        var value = attempt(session);
        require(
            session.maid.isEntityAlive() && session.validNonce(session.maid)
                && session.maid.worldObj == session.player.worldObj
                && session.maid.worldObj.getEntityByID(session.maid.getEntityId()) == session.maid
                && session.maid.func_152113_b()
                    .equals(session.data.getString("ExpectedOwner"))
                && session.maid.getCustomNameTag()
                    .isEmpty()
                && (session.maid.getModelId()
                    .equals(session.data.getString("ExpectedModel"))
                    || session.maid.getModelId()
                        .equals(value.row.getString("AllowedModel"))),
            "Unknown player input actor successor retained");
        value.validateSecondary();
        session.emptyActor();
        return true;
    }

    static boolean inventoryOwned(Session session) {
        if (!session.data.hasKey(KEY)) return false;
        attempt(session).validateMaterials();
        return true;
    }

    static void verifyInventory(Session session) {
        attempt(session).validateMaterials();
    }

    static boolean protect(Session session) {
        if (!session.data.hasKey(KEY)) return false;
        var value = attempt(session);
        int expected = value.row.getString("Stage")
            .equals("spectator-armed") ? 3 : 0;
        require(
            session.player.theItemInWorldManager.getGameType()
                .getID() == expected,
            "Foreign primary input mode retained");
        protectPlayer(session.player);
        value.validateSecondary();
        protectPlayer(value.secondary);
        return true;
    }

    private static void protectPlayer(EntityPlayerMP player) {
        boolean changed = !player.capabilities.disableDamage || !player.capabilities.allowFlying
            || !player.capabilities.isFlying;
        player.capabilities.disableDamage = player.capabilities.allowFlying = player.capabilities.isFlying = true;
        player.noClip = true;
        player.fallDistance = 0;
        if (changed) player.sendPlayerAbilities();
    }

    static boolean isSpawning(Entity entity) {
        for (var value : LIVE.values()) {
            if (!MaidCareInteractionFixture.owns(value.session) || entity.worldObj != value.session.player.worldObj)
                continue;
            if (entity == value.wild && entity.getEntityData()
                .getString(TAG)
                .equals(value.session.data.getString("Run"))) return true;
            if (!(entity instanceof EntityItem drop)) continue;
            if (value.drops.contains(drop)) return true;
            if (value.admitCursorDrop(drop)) return true;
            var maid = value.session.maid;
            if (!value.row.getString("Stage")
                .startsWith("xp-")
                || !maid.getEntityData()
                    .hasKey(MaidInteractionTransfer.TAG, 10))
                continue;
            var hold = maid.getEntityData()
                .getCompoundTag(MaidInteractionTransfer.TAG);
            if (!hold.getString("Player")
                .equals(
                    value.session.player.getUniqueID()
                        .toString())
                || !hold.getString("Kind")
                    .equals("experience")
                || drop.getDistanceSqToEntity(value.session.player) > 1
                || !ItemStack.areItemStacksEqual(drop.getEntityItem(), ProtectedItemCodec.decodeField(hold, "Output")))
                continue;
            value.drops.add(drop);
            return true;
        }
        return false;
    }

    static void run(Session session, String action) {
        if (action.equals("world-player-prepare")) {
            prepare(session);
            return;
        }
        var value = attempt(session);
        value.validateMaterials();
        switch (action) {
            case "world-player-arm-gui" -> {
                value.clearGenerated();
                value.add("sword", new ItemStack(Items.iron_sword), 1);
                var shield = com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess
                    .woodenShieldItem();
                require(shield != null, "Actual registered native shield required");
                value.add("shield", new ItemStack(shield), 1);
                value.add("helmet", new ItemStack(Items.iron_helmet), 1);
                value.add("chest", new ItemStack(Items.iron_chestplate), 1);
                value.add("legs", new ItemStack(Items.iron_leggings), 1);
                value.add("boots", new ItemStack(Items.iron_boots), 1);
                value.add("compass", new ItemStack(Items.compass), 1);
                value.add("stone", new ItemStack(Items.stick, 32), 32);
                for (var material : value.materials) value.placeEmpty(session.player, material.stack.copy(), false);
                value.row.setString("AllowedModel", "geckolib:winefox_hanfu");
                value.row.setString("Stage", "gui-armed");
            }
            case "world-player-verify-gui" -> {
                require(
                    value.row.getString("Stage")
                        .equals("gui-armed"),
                    "Native GUI input required");
                for (String label : new String[] { "sword", "shield", "helmet", "chest", "legs", "boots", "compass",
                    "stone" }) {
                    int slot = switch (label) {
                        case "sword" -> 6;
                        case "shield" -> 7;
                        case "helmet" -> 11;
                        case "chest" -> 10;
                        case "legs" -> 9;
                        case "boots" -> 8;
                        case "compass" -> 5;
                        default -> 0;
                    };
                    require(
                        value.match(
                            label,
                            session.maid.getMaidInventory()
                                .getStackInSlot(slot)),
                        "Native GUI target slot differs: " + label);
                }
                require(session.player.inventory.getItemStack() == null, "Finish the native cursor transaction first");
                session.data.setString("ExpectedModel", session.maid.getModelId());
                value.row.setString("Stage", "gui-verified");
            }
            case "world-player-arm-nonowner", "world-player-arm-far", "world-player-arm-spectator" -> {
                value.row.setTag("Denied", protectedState(session.maid));
                value.row.setString("Stage", action.endsWith("spectator") ? "spectator-armed" : "deny-armed");
                if (action.endsWith("far")) session.move(true);
                if (action.endsWith("spectator")) session.player.setGameType(GameType.getByID(3));
            }
            case "world-player-verify-denied" -> {
                require(
                    value.row.hasKey("Denied", 10) && value.row.getCompoundTag("Denied")
                        .equals(protectedState(session.maid)),
                    "Rejected real input changed protected maid state");
                if (session.player.theItemInWorldManager.getGameType()
                    .getID() == 3) session.player.setGameType(GameType.SURVIVAL);
                session.move(false);
                value.row.setString("Stage", "deny-verified");
            }
            case "world-player-arm-main-priority" -> {
                value.clearGenerated();
                var apple = value.add("off-apple", new ItemStack(Items.golden_apple), 1);
                value.requireEmptyHandSlots();
                session.player.inventory.setInventorySlotContents(value.offhand(session.player), apple.copy());
                value.row.setString("Stage", "main-priority");
            }
            case "world-player-verify-main-priority" -> {
                require(
                    value.match("off-apple", session.player.inventory.getStackInSlot(value.offhand(session.player)))
                        && session.maid.getActivePotionEffects()
                            .isEmpty()
                        && session.player.openContainer instanceof ContainerMaid container
                        && container.getMaid() == session.maid,
                    "Normal main-hand GUI must precede offhand care without consuming it");
                value.row.setString("Stage", "main-priority-verified");
            }
            case "world-player-arm-wild" -> {
                value.clearGenerated();
                value.requireEmptyHandSlots();
                require(value.wild == null, "Restore this exact wild actor before preparing another");
                var main = value.add("main-stick", new ItemStack(Items.stick), 1);
                var cake = value.add("off-cake", new ItemStack(Items.cake), 1);
                session.player.inventory.setInventorySlotContents(session.slot(), main.copy());
                session.player.inventory.setInventorySlotContents(value.offhand(session.player), cake.copy());
                value.wild = new EntityMaid(session.player.worldObj);
                value.wild.getEntityData()
                    .setString(TAG, session.data.getString("Run"));
                value.wild.getBrainCoordinator()
                    .setSuspended(true);
                value.wild.setPickup(false);
                value.wild.setLocationAndAngles(6.5, 65, -3.5, 90, 0);
                value.row.setString(
                    "Wild",
                    value.wild.getUniqueID()
                        .toString());
                require(
                    session.player.worldObj.spawnEntityInWorld(value.wild),
                    "Exact temporary native wild maid spawn failed");
                value.row.setString("Stage", "wild-armed");
            }
            case "world-player-verify-wild" -> {
                require(
                    value.wild != null && value.wild.isOwner(session.player)
                        && value.match("main-stick", session.player.inventory.getStackInSlot(session.slot()))
                        && session.player.inventory.getStackInSlot(value.offhand(session.player)) == null,
                    "Actual native offhand fallback tame and main-hand restoration required");
                value.row.setString("Stage", "wild-verified");
            }
            case "world-player-arm-xp", "world-player-arm-double-bottle", "world-player-arm-xp-full" -> {
                value.clearGenerated();
                value.requireEmptyHandSlots();
                for (Object raw : new EntityPlayerMP[] { session.player, value.secondary }) {
                    var player = (EntityPlayerMP) raw;
                    for (var stack : player.inventory.mainInventory) require(
                        stack == null || stack.getItem() != Items.experience_bottle,
                        "Keep original untagged XP bottles unchanged; this fixture requires none initially");
                }
                int seed = action.endsWith("double-bottle") ? 36 : 53;
                value.row.setInteger("XPSeed", seed);
                value.row.setString("XPSource", "glass");
                session.maid.setExperience(seed);
                var bottles = value.add("glass", new ItemStack(Items.glass_bottle, 4), 4);
                session.player.inventory.setInventorySlotContents(session.slot(), bottles.copy());
                value.materials.add(new Material("output", new ItemStack(Items.experience_bottle), 0));
                if (action.endsWith("double-bottle")) {
                    var other = value.add("off-glass", new ItemStack(Items.glass_bottle, 3), 3);
                    session.player.inventory.setInventorySlotContents(value.offhand(session.player), other.copy());
                }
                value.row.setString("Stage", action.endsWith("full") ? "xp-full" : "xp-armed");
                if (action.endsWith("full")) value.fillEmpty();
            }
            case "world-player-verify-xp-single", "world-player-verify-double-bottle" -> {
                require(
                    session.maid.getExperience() == value.row.getInteger("XPSeed") - 12 && value.count("output") == 1,
                    "One native use must exchange exactly twelve XP and one bottle");
                value.row.setString("Stage", "xp-single-verified");
            }
            case "world-player-verify-xp-bulk" -> {
                require(
                    value.row.getInteger("XPSeed") == 53 && session.maid.getExperience() == 5
                        && value.count("output") == 4
                        && value.count("glass") == 0,
                    "Native bulk exchange must conserve all four bottles and leave five XP");
                value.row.setString("Stage", "xp-bulk-verified");
            }
            case "world-player-verify-xp-full" -> {
                require(
                    session.maid.getExperience() == value.row.getInteger("XPSeed") - 12 && value.count("output") == 1
                        && value.drops.stream()
                            .anyMatch(
                                drop -> !drop.isDead && drop.delayBeforeCanPickup <= 0
                                    && drop.getEntityItem()
                                        .getItem() == Items.experience_bottle),
                    "Full inventory must retain the one real output after native pickup delay");
                value.row.setString("Stage", "xp-full-verified");
            }
            case "world-player-free-output-slot" -> {
                require(
                    value.row.getString("Stage")
                        .equals("xp-full-verified"),
                    "Verified full-inventory output required");
                int found = -1;
                for (int i = 0; i < session.player.inventory.mainInventory.length; i++)
                    if (value.label(session.player.inventory.mainInventory[i])
                        .equals("filler")) {
                            found = i;
                            break;
                        }
                require(found >= 0, "Exact generated filler slot required");
                var removed = session.player.inventory.mainInventory[found];
                value.material("filler").count -= removed.stackSize;
                session.player.inventory.mainInventory[found] = null;
                value.row.setString("Stage", "xp-pickup");
            }
            case "world-player-approach-output" -> value.approachOutput();
            case "world-player-return-output-camera" -> value.restorePickupCamera();
            case "world-player-arm-cursor-close" -> value.armCursorClose(false);
            case "world-player-arm-cursor-close-full" -> value.armCursorClose(true);
            case "world-player-verify-cursor-return" -> value.verifyCursorReturn();
            case "world-player-verify-cursor-drop" -> value.verifyCursorDrop();
            case "world-player-free-cursor-slot" -> value.freeCursorSlot();
            case "world-player-approach-cursor-drop" -> value.approachOutput();
            case "world-player-verify-cursor-pickup" -> value.verifyCursorPickup();
            case "world-player-clear-cursor-filler" -> value.clearCursorFiller();
            case "world-player-finish-cursor-close" -> value.finishCursorClose();
            case "world-player-verify-output-pickup" -> {
                require(
                    value.count("output") == 1 && value.drops.stream()
                        .noneMatch(drop -> !drop.isDead) && value.countInPlayer("output", session.player) == 1,
                    "Native pickup must move the same single output into the freed slot");
                value.row.setString("Stage", "xp-pickup-verified");
            }
            default -> throw new IllegalArgumentException("Unknown real player input action");
        }
        value.saveMaterials();
        value.validateMaterials();
        value.sync();
        protect(session);
    }

    private static void prepare(Session session) {
        require(
            session.data.getString("Phase")
                .equals("prepared") && !session.data.hasKey(KEY) && session.player.inventory.getItemStack() == null,
            "Separate empty prepared care session required");
        var secondary = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a("TLMNHObserver");
        require(
            online(secondary) && secondary != session.player
                && secondary.worldObj == session.player.worldObj
                && secondary.theItemInWorldManager.getGameType()
                    .getID() == 3
                && secondary.getHealth() == 20
                && secondary.inventory.getItemStack() == null
                && !secondary.getEntityData()
                    .hasKey(TAG),
            "Second actual connected protected spectator without another fixture required");
        session.emptyActor();
        require(
            session.player.inventory.getStackInSlot(offhandSlot(session.player)) == null,
            "Original owner offhand must remain empty for this finite native input fixture");
        require(
            secondary.inventory.getStackInSlot(offhandSlot(secondary)) == null,
            "Original second-player offhand must remain empty for this fixture");
        var row = new NBTTagCompound();
        row.setInteger("Schema", 1);
        row.setString("Stage", "ready");
        row.setString(
            "Secondary",
            secondary.getUniqueID()
                .toString());
        row.setTag("SecondaryOriginal", playerSnapshot(secondary));
        row.setTag("PrimaryBase", playerInventory(session.player));
        row.setTag("Materials", new NBTTagList());
        row.setInteger("XPSeed", 0);
        row.setString("XPSource", "");
        row.setString("AllowedModel", session.maid.getModelId());
        var value = new Attempt(session, secondary, row);
        var hand = secondary.inventory.getStackInSlot(secondary.inventory.currentItem);
        if (hand != null) row.getCompoundTag("SecondaryOriginal")
            .setTag("Hand", ProtectedItemCodec.encode(hand));
        secondary.inventory.setInventorySlotContents(secondary.inventory.currentItem, null);
        row.setTag("SecondaryBase", playerInventory(secondary));
        var marker = new NBTTagCompound();
        for (String key : new String[] { "Owner", "Actor", "Run" }) marker.setString(key, session.data.getString(key));
        marker.setTag(
            "Original",
            row.getCompoundTag("SecondaryOriginal")
                .copy());
        value.secondaryMarker = marker;
        secondary.getEntityData()
            .setTag(TAG, marker);
        session.data.setTag(KEY, row);
        session.data.setString("Phase", "player-inputs");
        LIVE.put(session.data.getString("Run"), value);
        secondary.setGameType(GameType.SURVIVAL);
        value.sync();
        protect(session);
    }

    private static NBTTagCompound protectedState(EntityMaid maid) {
        var result = new NBTTagCompound();
        result.setString("Owner", maid.func_152113_b());
        result.setString("Model", maid.getModelId());
        result.setString(
            "Task",
            maid.getTask()
                .getId());
        result.setString(
            "Schedule",
            maid.getSchedule()
                .name());
        result.setBoolean("Sitting", maid.isSitting());
        var inventory = new NBTTagList();
        for (int slot = 0; slot < maid.getMaidInventory()
            .getSizeInventory(); slot++) {
            var stack = raw(
                maid.getMaidInventory()
                    .getStackInSlot(slot));
            stack.setInteger("Slot", slot);
            inventory.appendTag(stack);
        }
        result.setTag("Inventory", inventory);
        return result;
    }

    private static NBTTagList playerInventory(EntityPlayerMP player) {
        return (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
            .copy();
    }

    private static int offhandSlot(EntityPlayerMP player) {
        try {
            return BackhandAccess.offhandSlot(player);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual Backhand offhand unavailable", failure);
        }
    }

    private static NBTTagCompound playerSnapshot(EntityPlayerMP player) {
        var row = new NBTTagCompound();
        row.setTag("Inventory", playerInventory(player));
        row.setInteger("Selected", player.inventory.currentItem);
        row.setInteger(
            "Mode",
            player.theItemInWorldManager.getGameType()
                .getID());
        row.setFloat("Health", player.getHealth());
        row.setDouble("X", player.posX);
        row.setDouble("Y", player.posY);
        row.setDouble("Z", player.posZ);
        row.setFloat("Yaw", player.rotationYaw);
        row.setFloat("Pitch", player.rotationPitch);
        return row;
    }

    static void beforeClose(Session session) {
        if (!session.data.hasKey(KEY)) return;
        var value = attempt(session);
        value.validateMaterials();
        require(
            value.pickupCamera == null,
            "Return the exact controlled pickup camera before restoring the care session");
        require(
            session.player.inventory.currentItem == session.slot(),
            "Wait for native main-hand restoration before cleanup");
        require(
            !session.maid.getEntityData()
                .hasKey(MaidInteractionTransfer.TAG),
            "Original native payout hold retained");
        value.clearGenerated();
        if (value.wild != null) {
            require(
                value.wild.getEntityData()
                    .getString(TAG)
                    .equals(session.data.getString("Run"))
                    && value.wild.getCustomNameTag()
                        .isEmpty()
                    && (value.wild.func_152113_b()
                        .isEmpty() || value.wild.isOwner(session.player)),
                "Unknown wild input actor successor retained");
            for (int i = 0; i < value.wild.getMaidInventory()
                .getSizeInventory(); i++)
                require(
                    value.wild.getMaidInventory()
                        .getStackInSlot(i) == null,
                    "Wild input actor acquired foreign material; retained");
            value.wild.worldObj.removePlayerEntityDangerously(value.wild);
        }
        var original = value.row.getCompoundTag("SecondaryOriginal");
        require(
            value.secondary.inventory.currentItem == original.getInteger("Selected"),
            "Secondary native hand restoration required");
        if (value.secondary.openContainer != value.secondary.inventoryContainer) {
            require(
                value.secondary.openContainer instanceof ContainerMaid container && container.getMaid() == session.maid,
                "Unknown secondary container retained");
            value.secondary.closeScreen();
        }
        require(
            playerInventory(value.secondary).equals(value.row.getTagList("SecondaryBase", 10)),
            "Original secondary base changed; backup retained");
        var hand = ProtectedItemCodec.decodeField(original, "Hand");
        value.secondary.inventory.setInventorySlotContents(original.getInteger("Selected"), hand);
        require(
            playerInventory(value.secondary).equals(original.getTagList("Inventory", 10)),
            "Complete secondary inventory restore differs; backup retained");
        value.secondary.playerNetServerHandler.setPlayerLocation(
            original.getDouble("X"),
            original.getDouble("Y"),
            original.getDouble("Z"),
            original.getFloat("Yaw"),
            original.getFloat("Pitch"));
        value.secondary.setGameType(GameType.getByID(original.getInteger("Mode")));
        protectPlayer(value.secondary);
        value.secondary.getEntityData()
            .removeTag(TAG);
        session.data.setTag(
            "ExpectedInventory",
            value.row.getTag("PrimaryBase")
                .copy());
        session.data.setTag(
            "ResultInventory",
            value.row.getTag("PrimaryBase")
                .copy());
        session.data.setString("ExpectedModel", session.maid.getModelId());
        if (session.player.theItemInWorldManager.getGameType()
            .getID() == 3) session.player.setGameType(GameType.SURVIVAL);
        session.data.removeTag(KEY);
        session.data.setString("Phase", "prepared");
        LIVE.remove(session.data.getString("Run"));
        value.sync();
    }

    static JsonObject observation(Session session) {
        var value = attempt(session);
        var result = new JsonObject();
        result.addProperty("stage", value.row.getString("Stage"));
        result.addProperty(
            "secondary",
            value.secondary.getUniqueID()
                .toString());
        result.addProperty(
            "secondaryMode",
            value.secondary.theItemInWorldManager.getGameType()
                .getID());
        result.addProperty("experience", session.maid.getExperience());
        result.addProperty(
            "nativeTask",
            session.maid.getTask()
                .getId());
        result.addProperty("primaryInventory", playerInventory(session.player).toString());
        result.addProperty("secondaryInventory", playerInventory(value.secondary).toString());
        result.addProperty(
            "maidInventory",
            protectedState(session.maid).getTagList("Inventory", 10)
                .toString());
        result.addProperty("primaryOffhandSlot", value.offhand(session.player));
        result.addProperty("secondaryOffhandSlot", value.offhand(value.secondary));
        result.addProperty("primaryCursor", raw(session.player.inventory.getItemStack()).toString());
        result.addProperty("secondaryCursor", raw(value.secondary.inventory.getItemStack()).toString());
        result.addProperty("primaryCursorNativeNbtBase64", nativeItem(session.player.inventory.getItemStack()));
        result.addProperty("secondaryCursorNativeNbtBase64", nativeItem(value.secondary.inventory.getItemStack()));
        result.addProperty("primaryInventoryNativeNbtBase64", nativeInventory(playerInventory(session.player)));
        result.addProperty("secondaryInventoryNativeNbtBase64", nativeInventory(playerInventory(value.secondary)));
        result.addProperty(
            "maidInventoryNativeNbtBase64",
            nativeInventory(protectedState(session.maid).getTagList("Inventory", 10)));
        result.add("primaryContainer", containerObservation(session.player));
        result.add("secondaryContainer", containerObservation(value.secondary));
        result.addProperty("primaryWindow", session.player.openContainer.windowId);
        result.addProperty("secondaryWindow", value.secondary.openContainer.windowId);
        result.add("primaryPhysics", physics(session.player));
        result.addProperty("pickupCameraPending", value.pickupCamera != null);
        if (value.pickupCamera != null) {
            result.addProperty("pickupCameraOriginal", value.pickupCamera.toString());
            result.addProperty("pickupCameraTarget", value.pickupTarget.toString());
        }
        result.addProperty(
            "primaryMode",
            session.player.theItemInWorldManager.getGameType()
                .getID());
        var materials = new JsonArray();
        for (var material : value.materials) {
            var row = new JsonObject();
            row.addProperty("label", material.label);
            row.addProperty("actualCount", value.count(material.label));
            row.addProperty("nativePrototype", raw(material.stack).toString());
            materials.add(row);
        }
        result.add("materials", materials);
        if (value.wild != null) {
            result.addProperty(
                "wildUuid",
                value.wild.getUniqueID()
                    .toString());
            result.addProperty("wildEntityId", value.wild.getEntityId());
            result.addProperty("wildOwner", value.wild.func_152113_b());
        }
        var drops = new JsonArray();
        for (var drop : value.drops) {
            var row = new JsonObject();
            row.addProperty(
                "uuid",
                drop.getUniqueID()
                    .toString());
            row.addProperty("entityId", drop.getEntityId());
            row.addProperty("dead", drop.isDead);
            row.addProperty("registered", drop.worldObj.getEntityByID(drop.getEntityId()) == drop);
            row.addProperty("pickupDelay", drop.delayBeforeCanPickup);
            row.addProperty("nativeItem", raw(drop.getEntityItem()).toString());
            row.addProperty("nativeOwner", drop.func_145798_i());
            row.addProperty("nativeThrower", drop.func_145800_j());
            row.add("physics", physics(drop));
            row.addProperty("distanceSquaredToPlayer", drop.getDistanceSqToEntity(session.player));
            row.addProperty(
                "intersectsNativePlayerPickupBox",
                session.player.boundingBox.expand(1, .5, 1)
                    .intersectsWith(drop.boundingBox));
            var nativeNbt = new NBTTagCompound();
            drop.writeToNBT(nativeNbt);
            row.addProperty("nativeEntityNbt", nativeNbt.toString());
            drops.add(row);
        }
        result.add("drops", drops);
        if (value.cursorClose != null) {
            var close = value.cursorClose;
            var row = new JsonObject();
            row.addProperty("windowId", close.windowId);
            row.addProperty("currentContainerWindowId", close.window.windowId);
            row.addProperty("armedAtWorldTick", close.armedAt);
            row.addProperty("fullInventory", close.fullInventory);
            row.addProperty("slotFreed", close.slotFreed);
            row.addProperty("dropObserved", close.drop != null);
            row.addProperty("returnVerified", close.returnVerified);
            row.addProperty("originalCursorNativeNbtBase64", nativeItem(close.cursor));
            row.addProperty(
                "containerIdentity",
                close.window.getClass()
                    .getName() + "@" + Integer.toHexString(System.identityHashCode(close.window)));
            if (close.drop != null) {
                row.addProperty(
                    "dropUuid",
                    close.drop.getUniqueID()
                        .toString());
                row.addProperty("dropEntityId", close.drop.getEntityId());
                row.addProperty("originalDropItemNativeNbtBase64", nativeItem(close.cursor));
                row.addProperty("nativeEntityAtJoin", close.nativeAtJoin.toString());
                row.addProperty("nativeEntityAtJoinBase64", nativeBinary(close.nativeAtJoin));
                row.addProperty("dropDead", close.drop.isDead);
            }
            result.add("cursorClose", row);
        }
        return result;
    }

    private static JsonObject containerObservation(EntityPlayerMP player) {
        var result = new JsonObject();
        var container = player.openContainer;
        result.addProperty("windowId", container.windowId);
        result.addProperty(
            "containerClass",
            container.getClass()
                .getName());
        result.addProperty(
            "containerIdentity",
            container.getClass()
                .getName() + "@"
                + Integer.toHexString(System.identityHashCode(container)));
        result.addProperty("cursorNativeNbtBase64", nativeItem(player.inventory.getItemStack()));
        var slots = new JsonArray();
        for (Object raw : container.inventorySlots) {
            var slot = (Slot) raw;
            var row = new JsonObject();
            row.addProperty("containerSlot", slot.slotNumber);
            row.addProperty("inventorySlot", slot.getSlotIndex());
            row.addProperty(
                "inventoryClass",
                slot.inventory.getClass()
                    .getName());
            row.addProperty("nativeNbtBase64", nativeItem(slot.getStack()));
            slots.add(row);
        }
        result.add("slots", slots);
        return result;
    }

    private static String nativeItem(ItemStack stack) {
        return stack == null ? "" : nativeBinary(raw(stack));
    }

    private static String nativeInventory(NBTTagList inventory) {
        var tag = new NBTTagCompound();
        tag.setTag("Inventory", inventory.copy());
        return nativeBinary(tag);
    }

    private static String nativeBinary(NBTTagCompound tag) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode native two-player input observation", failure);
        }
    }

    private static JsonObject physics(Entity entity) {
        var out = new JsonObject();
        out.addProperty("x", entity.posX);
        out.addProperty("y", entity.posY);
        out.addProperty("z", entity.posZ);
        out.addProperty("motionX", entity.motionX);
        out.addProperty("motionY", entity.motionY);
        out.addProperty("motionZ", entity.motionZ);
        out.addProperty("noClip", entity.noClip);
        out.addProperty("onGround", entity.onGround);
        out.addProperty("dead", entity.isDead);
        out.addProperty("dimension", entity.dimension);
        out.addProperty(
            "registered",
            entity.worldObj.getEntityByID(entity.getEntityId()) == entity && entity.worldObj.loadedEntityList.stream()
                .anyMatch(value -> value == entity));
        var box = entity.boundingBox;
        var bounds = new JsonArray();
        for (double value : new double[] { box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ })
            bounds.add(new com.google.gson.JsonPrimitive(value));
        out.add("boxMinMax", bounds);
        return out;
    }

    private static NBTTagCompound raw(ItemStack stack) {
        return stack == null ? new NBTTagCompound()
            : (NBTTagCompound) stack.copy()
                .writeToNBT(new NBTTagCompound())
                .copy();
    }

    private static NBTTagCompound signature(ItemStack stack) {
        var raw = raw(stack);
        raw.removeTag("Count");
        return raw;
    }

    private static final class Material {

        final String label;
        final ItemStack stack;
        int count;

        Material(String label, ItemStack stack, int count) {
            this.label = label;
            this.stack = stack;
            this.count = count;
        }
    }

    private static final class Attempt {

        final Session session;
        final EntityPlayerMP secondary;
        final NBTTagCompound row;
        final List<Material> materials = new ArrayList<>();
        final List<EntityItem> drops = new ArrayList<>();
        NBTTagCompound secondaryMarker;
        NBTTagCompound pickupCamera, pickupTarget;
        CursorClose cursorClose;
        EntityMaid wild;

        Attempt(Session session, EntityPlayerMP secondary, NBTTagCompound row) {
            this.session = session;
            this.secondary = secondary;
            this.row = row;
        }

        void approachOutput() {
            boolean closeOutput = cursorClose != null && (row.getString("Stage")
                .equals("gui-cursor-pickup")
                || row.getString("Stage")
                    .equals("gui-cursor-close-armed"));
            require(
                (row.getString("Stage")
                    .equals("xp-pickup") || closeOutput) && pickupCamera == null,
                "One verified original output and an unmodified controlled camera required");
            var player = session.player;
            require(
                Math.abs(player.posX - 4.5) < 1e-7 && Math.abs(player.posY - 65) < 1e-7
                    && Math.abs(player.posZ + 1.5) < 1e-7,
                "Foreign native input camera retained");
            EntityItem target = null;
            for (var drop : drops) if (!drop.isDead) {
                require(target == null, "Ambiguous native output retained");
                target = drop;
            }
            var exact = target;
            require(
                target != null && target.worldObj == player.worldObj
                    && target.worldObj.getEntityByID(target.getEntityId()) == target
                    && target.worldObj.loadedEntityList.stream()
                        .anyMatch(value -> value == exact)
                    && (closeOutput
                        ? target == cursorClose.drop
                            && ItemStack.areItemStacksEqual(target.getEntityItem(), cursorClose.cursor)
                        : target.getEntityItem()
                            .getItem() == Items.experience_bottle && target.getEntityItem().stackSize == 1)
                    && target.delayBeforeCanPickup <= 0
                    && target.getDistanceSqToEntity(session.maid) <= 64,
                "Exact live original output in the controlled scene required");
            require(
                Double.isFinite(target.posX) && Double.isFinite(target.posY)
                    && Double.isFinite(target.posZ)
                    && target.posY >= 64.9
                    && target.posY <= 66.5,
                "Native output moved outside the controlled floor; retained");
            pickupCamera = camera(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
            pickupTarget = camera(
                target.posX,
                target.boundingBox.minY,
                target.posZ,
                player.rotationYaw,
                player.rotationPitch);
            player.playerNetServerHandler.setPlayerLocation(
                pickupTarget.getDouble("X"),
                pickupTarget.getDouble("Y"),
                pickupTarget.getDouble("Z"),
                pickupTarget.getFloat("Yaw"),
                pickupTarget.getFloat("Pitch"));
        }

        void armCursorClose(boolean fullInventory) {
            require(
                cursorClose == null && row.getString("Stage")
                    .equals("gui-armed"),
                "Finish the previous exact cursor transaction before staging another");
            var player = session.player;
            require(
                player.openContainer instanceof ContainerMaid window && window.getMaid() == session.maid
                    && window.windowId > 0
                    && window.canModifyWith(player)
                    && online(player)
                    && player.inventory.currentItem == session.slot()
                    && player.getHealth() == 20,
                "Actual owned native maid window and original main hand required");
            var cursor = player.inventory.getItemStack();
            var stored = session.maid.getMaidInventory()
                .getStackInSlot(0);
            require(
                label(cursor).equals("stone") && cursor.stackSize == 16
                    && label(stored).equals("stone")
                    && stored.stackSize == 16
                    && countInPlayer("stone", player) == 0
                    && drops.stream()
                        .noneMatch(drop -> !drop.isDead),
                "Exactly the ordinary half-stack cursor and its original stored remainder required");
            cursorClose = new CursorClose(
                (ContainerMaid) player.openContainer,
                cursor.copy(),
                player.worldObj.getTotalWorldTime(),
                fullInventory);
            if (fullInventory) fillEmpty();
            row.setString("Stage", "gui-cursor-close-armed");
        }

        boolean admitCursorDrop(EntityItem drop) {
            var close = cursorClose;
            var player = session.player;
            if (close == null || close.drop != null
                || !row.getString("Stage")
                    .equals("gui-cursor-close-armed")
                || !online(player)
                || player.openContainer != close.window
                || close.window.windowId != close.windowId
                || !close.window.canModifyWith(player)
                || close.window.getMaid() != session.maid
                || !session.validNonce(session.maid)
                || drop.worldObj != player.worldObj
                || player.worldObj.getTotalWorldTime() - close.armedAt < 0
                || player.worldObj.getTotalWorldTime() - close.armedAt > 200
                || !ItemStack.areItemStacksEqual(player.inventory.getItemStack(), close.cursor)
                || !ItemStack.areItemStacksEqual(drop.getEntityItem(), close.cursor)
                || drop.delayBeforeCanPickup != 40
                || drop.isDead
                || Math.abs(drop.posX - player.posX) > 1e-7
                || Math.abs(drop.posZ - player.posZ) > 1e-7
                || Math.abs(drop.posY - (player.posY - .30000001192092896 + player.getEyeHeight())) > 1e-7)
                return false;
            // Native Container.onContainerClosed calls ForgeHooks.onPlayerTossEvent before clearing its cursor.
            // This one authenticated object is admitted; no other lab item or replacement is authorized.
            close.drop = drop;
            close.nativeAtJoin = new NBTTagCompound();
            drop.writeToNBT(close.nativeAtJoin);
            drops.add(drop);
            return true;
        }

        void verifyCursorDrop() {
            var close = cursorClose;
            require(
                close != null && row.getString("Stage")
                    .equals("gui-cursor-close-armed")
                    && close.drop != null
                    && session.player.openContainer == session.player.inventoryContainer
                    && session.player.inventory.getItemStack() == null,
                "Native close must create the same single full-NBT cursor drop and leave an empty cursor");
            require(
                close.drop.isDead ? !close.fullInventory && countInPlayer("stone", session.player) == 16
                    : close.drop.worldObj.getEntityByID(close.drop.getEntityId()) == close.drop
                        && ItemStack.areItemStacksEqual(close.drop.getEntityItem(), close.cursor),
                "The original cursor drop or its natural owner pickup must remain exact");
            if (close.fullInventory) {
                for (var stack : session.player.inventory.mainInventory)
                    require(stack != null, "Full native inventory must remain full before releasing one slot");
                require(
                    close.drop.delayBeforeCanPickup <= 0,
                    "Observe the original native pickup delay before the full-inventory check");
                require(
                    pickupCamera != null && session.player.boundingBox.expand(1, .5, 1)
                        .intersectsWith(close.drop.boundingBox),
                    "The actual full player must overlap the original live drop during its native pickup window");
            }
            row.setString("Stage", close.fullInventory ? "gui-cursor-full-waiting" : "gui-cursor-pickup");
        }

        void freeCursorSlot() {
            require(
                cursorClose != null && cursorClose.fullInventory
                    && !cursorClose.slotFreed
                    && row.getString("Stage")
                        .equals("gui-cursor-full-waiting"),
                "Verified original full-inventory cursor drop required");
            int slot = -1;
            for (int i = 0; i < session.player.inventory.mainInventory.length; i++)
                if (label(session.player.inventory.mainInventory[i]).equals("filler")) {
                    slot = i;
                    break;
                }
            require(slot >= 0, "Exact generated filler slot required");
            var removed = session.player.inventory.mainInventory[slot];
            material("filler").count -= removed.stackSize;
            session.player.inventory.mainInventory[slot] = null;
            cursorClose.slotFreed = true;
            row.setString("Stage", "gui-cursor-pickup");
        }

        void verifyCursorReturn() {
            var close = cursorClose;
            require(
                close != null && row.getString("Stage")
                    .equals("gui-cursor-close-armed")
                    && !close.fullInventory
                    && close.drop == null
                    && session.player.openContainer == session.player.inventoryContainer
                    && session.player.inventory.getItemStack() == null
                    && countInPlayer("stone", session.player) == 16
                    && countInPlayer("stone", secondary) == 0
                    && ItemStack.areItemStacksEqual(
                        session.maid.getMaidInventory()
                            .getStackInSlot(0),
                        close.cursor)
                    && drops.stream()
                        .noneMatch(drop -> !drop.isDead),
                "Native close must return the exact cursor stack without creating a drop or changing its remainder");
            int exact = 0;
            for (var stack : session.player.inventory.mainInventory)
                if (ItemStack.areItemStacksEqual(stack, close.cursor)) exact++;
            require(exact == 1, "Exactly one complete native cursor stack must return to the actual owner's inventory");
            // The installed Hodgepodge closes by returning the native toss stack and canceling that toss on success.
            // This verifies the observed result; it never inserts, moves or replaces an inventory item.
            close.returnVerified = true;
            row.setString("Stage", "gui-cursor-picked");
        }

        void verifyCursorPickup() {
            var close = cursorClose;
            require(
                close != null && row.getString("Stage")
                    .equals("gui-cursor-pickup")
                    && close.drop != null
                    && close.drop.isDead
                    && close.drop.worldObj.getEntityByID(close.drop.getEntityId()) != close.drop
                    && countInPlayer("stone", session.player) == 16
                    && countInPlayer("stone", secondary) == 0
                    && session.player.inventory.getItemStack() == null,
                "The same original item entity must be naturally picked up by the actual owner");
            int exact = 0;
            for (var stack : session.player.inventory.mainInventory)
                if (ItemStack.areItemStacksEqual(stack, close.cursor)) exact++;
            require(exact == 1, "One complete typed cursor stack must reach the player's native inventory");
            row.setString("Stage", "gui-cursor-picked");
        }

        void clearCursorFiller() {
            require(
                cursorClose != null && row.getString("Stage")
                    .equals("gui-cursor-picked"),
                "Verified native cursor completion required before removing this fixture's filler");
            if (!cursorClose.fullInventory) return;
            for (int i = 0; i < session.player.inventory.mainInventory.length; i++) {
                var stack = session.player.inventory.mainInventory[i];
                if (label(stack).equals("filler")) {
                    material("filler").count -= stack.stackSize;
                    session.player.inventory.mainInventory[i] = null;
                }
            }
            require(material("filler").count == 0, "Unknown filler successor retained");
        }

        void finishCursorClose() {
            require(
                cursorClose != null && row.getString("Stage")
                    .equals("gui-cursor-picked")
                    && pickupCamera == null
                    && (cursorClose.returnVerified && !cursorClose.fullInventory && cursorClose.drop == null
                        || !cursorClose.returnVerified && cursorClose.drop != null && cursorClose.drop.isDead)
                    && countInPlayer("stone", session.player) == 0
                    && session.player.inventory.getItemStack() == null
                    && match(
                        "stone",
                        session.maid.getMaidInventory()
                            .getStackInSlot(0)),
                "Native full-NBT recombination and original camera required before closing this plan");
            if (cursorClose.fullInventory) require(
                material("filler").count == 0,
                "Remove only this fixture's remaining filler before another native input");
            cursorClose = null;
            row.setString("Stage", "gui-armed");
        }

        void restorePickupCamera() {
            if (pickupCamera == null) return;
            var player = session.player;
            require(
                player.worldObj == session.maid.worldObj && online(player)
                    && player.getDistanceSq(
                        pickupTarget.getDouble("X"),
                        pickupTarget.getDouble("Y"),
                        pickupTarget.getDouble("Z")) < .0001,
                "Unknown pickup-camera successor retained with the original snapshot");
            player.playerNetServerHandler.setPlayerLocation(
                pickupCamera.getDouble("X"),
                pickupCamera.getDouble("Y"),
                pickupCamera.getDouble("Z"),
                pickupCamera.getFloat("Yaw"),
                pickupCamera.getFloat("Pitch"));
            pickupCamera = pickupTarget = null;
        }

        static NBTTagCompound camera(double x, double y, double z, float yaw, float pitch) {
            var out = new NBTTagCompound();
            out.setDouble("X", x);
            out.setDouble("Y", y);
            out.setDouble("Z", z);
            out.setFloat("Yaw", yaw);
            out.setFloat("Pitch", pitch);
            return out;
        }

        int offhand(EntityPlayerMP player) {
            return offhandSlot(player);
        }

        boolean selectedOwned(EntityPlayerMP player, int original) {
            String stage = row.getString("Stage");
            return player.inventory.currentItem == original || player.inventory.currentItem == offhand(player)
                && (stage.contains("xp") || stage.startsWith("wild"));
        }

        boolean cursorOwned(EntityPlayerMP player) {
            return player.inventory.getItemStack() == null || !label(player.inventory.getItemStack()).isEmpty();
        }

        void validateSecondary() {
            require(
                online(secondary) && secondary.worldObj == session.player.worldObj
                    && secondary.getEntityData()
                        .getTag(TAG) == secondaryMarker
                    && secondary.getUniqueID()
                        .toString()
                        .equals(row.getString("Secondary"))
                    && secondary.getHealth() == row.getCompoundTag("SecondaryOriginal")
                        .getFloat("Health")
                    && secondary.theItemInWorldManager.getGameType()
                        .getID() == 0
                    && selectedOwned(
                        secondary,
                        row.getCompoundTag("SecondaryOriginal")
                            .getInteger("Selected"))
                    && cursorOwned(secondary),
                "Unknown actual secondary player successor retained");
        }

        String label(ItemStack stack) {
            if (stack == null) return "";
            for (var material : materials) {
                if (signature(material.stack).equals(signature(stack))) return material.label;
            }
            return "";
        }

        Material material(String label) {
            for (var material : materials) {
                if (material.label.equals(label)) return material;
            }
            throw new IllegalStateException("Unknown generated material");
        }

        boolean match(String label, ItemStack stack) {
            return stack != null && signature(material(label).stack).equals(signature(stack))
                && stack.stackSize == expected(material(label));
        }

        int spent() {
            if (row.getString("XPSource")
                .isEmpty()) return 0;
            int difference = row.getInteger("XPSeed") - session.maid.getExperience();
            require(
                difference >= 0 && difference % 12 == 0 && difference <= 48,
                "Unknown native experience successor retained");
            return difference / 12;
        }

        int expected(Material material) {
            if (material.label.equals("output")) return spent();
            if (material.label.equals(row.getString("XPSource"))) return material.count - spent();
            if (material.label.equals("off-cake") && wild != null && wild.isOwner(session.player))
                return material.count - 1;
            return material.count;
        }

        ItemStack add(String label, ItemStack source, int count) {
            var stack = source.copy();
            if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setString(TAG, session.data.getString("Run"));
            stack.getTagCompound()
                .setString("InputMaterial", label);
            stack.stackSize = Math.max(1, count);
            materials.add(new Material(label, stack.copy(), count));
            return stack;
        }

        void requireEmptyHandSlots() {
            require(
                session.player.inventory.getStackInSlot(session.slot()) == null
                    && session.player.inventory.getStackInSlot(offhand(session.player)) == null,
                "Original native hand slots must be empty before staging");
        }

        void placeEmpty(EntityPlayerMP player, ItemStack stack, boolean includeHand) {
            for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
                if ((includeHand || slot != player.inventory.currentItem) && slot != offhand(player)
                    && player.inventory.mainInventory[slot] == null) {
                    player.inventory.mainInventory[slot] = stack;
                    return;
                }
            }
            throw new IllegalStateException("Enough original empty native player slots required");
        }

        void fillEmpty() {
            var filler = add("filler", new ItemStack(Item.getItemFromBlock(net.minecraft.init.Blocks.stone), 64), 0);
            int count = 0;
            for (int slot = 0; slot < session.player.inventory.mainInventory.length; slot++) {
                if (session.player.inventory.mainInventory[slot] == null) {
                    var stack = filler.copy();
                    stack.stackSize = 64;
                    session.player.inventory.mainInventory[slot] = stack;
                    count += 64;
                }
            }
            material("filler").count = count;
        }

        void saveMaterials() {
            var list = new NBTTagList();
            for (var material : materials) {
                var data = new NBTTagCompound();
                data.setString("Label", material.label);
                data.setInteger("Count", material.count);
                data.setTag("Stack", ProtectedItemCodec.encode(material.stack));
                list.appendTag(data);
            }
            row.setTag("Materials", list);
        }

        void collectPlayer(EntityPlayerMP player, NBTTagList base, Map<String, Integer> counts) {
            var original = new HashMap<Integer, NBTTagCompound>();
            for (int index = 0; index < base.tagCount(); index++) {
                var tag = base.getCompoundTagAt(index);
                require(
                    original.put(tag.getByte("Slot") & 255, tag) == null,
                    "Duplicate original native inventory slot retained");
            }
            var actual = playerInventory(player);
            var seen = new HashSet<Integer>();
            for (int index = 0; index < actual.tagCount(); index++) {
                var tag = actual.getCompoundTagAt(index);
                int slot = tag.getByte("Slot") & 255;
                require(seen.add(slot), "Duplicate native player inventory slot retained");
                if (original.containsKey(slot)) {
                    require(
                        original.get(slot)
                            .equals(tag),
                        "Original player inventory slot changed; retained");
                } else collect(ItemStack.loadItemStackFromNBT((NBTTagCompound) tag.copy()), counts);
            }
            require(seen.containsAll(original.keySet()), "Original player material disappeared; retained");
            collect(player.inventory.getItemStack(), counts);
        }

        void collect(ItemStack stack, Map<String, Integer> counts) {
            if (stack == null) return;
            String label = label(stack);
            require(!label.isEmpty() && stack.stackSize > 0, "Unknown generated-material successor retained");
            counts.put(label, counts.getOrDefault(label, 0) + stack.stackSize);
        }

        Map<String, Integer> counts() {
            var counts = new HashMap<String, Integer>();
            collectPlayer(session.player, row.getTagList("PrimaryBase", 10), counts);
            collectPlayer(secondary, row.getTagList("SecondaryBase", 10), counts);
            for (int slot = 0; slot < session.maid.getMaidInventory()
                .getSizeInventory(); slot++)
                collect(
                    session.maid.getMaidInventory()
                        .getStackInSlot(slot),
                    counts);
            for (var drop : drops) {
                if (drop.isDead) continue;
                require(
                    drop.worldObj == session.player.worldObj && drop.worldObj.getEntityByID(drop.getEntityId()) == drop
                        && drop.getEntityItem() != null,
                    "Unknown native output entity successor retained");
                collect(drop.getEntityItem(), counts);
            }
            return counts;
        }

        int count(String label) {
            return counts().getOrDefault(label, 0);
        }

        int countInPlayer(String label, EntityPlayerMP player) {
            int count = 0;
            for (var stack : player.inventory.mainInventory) {
                if (label(stack).equals(label)) count += stack.stackSize;
            }
            return count;
        }

        void validateMaterials() {
            validateSecondary();
            var counts = counts();
            for (var material : materials) require(
                counts.getOrDefault(material.label, 0) == expected(material),
                "Native material count differs: " + material.label);
        }

        void clearGenerated() {
            validateMaterials();
            require(
                !session.maid.getEntityData()
                    .hasKey(MaidInteractionTransfer.TAG),
                "Native pending payout remains held");
            for (var player : new EntityPlayerMP[] { session.player, secondary }) {
                for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
                    if (!label(player.inventory.mainInventory[slot]).isEmpty())
                        player.inventory.mainInventory[slot] = null;
                }
                if (player.inventory.getItemStack() != null) {
                    require(cursorOwned(player), "Unknown cursor retained");
                    player.inventory.setItemStack(null);
                }
            }
            for (int slot = 0; slot < session.maid.getMaidInventory()
                .getSizeInventory(); slot++) {
                if (session.maid.getMaidInventory()
                    .getStackInSlot(slot) != null)
                    session.maid.getMaidInventory()
                        .setInventorySlotContents(slot, null);
            }
            for (var drop : drops) {
                if (drop.isDead) continue;
                require(
                    !label(drop.getEntityItem()).isEmpty() && drop.worldObj.getEntityByID(drop.getEntityId()) == drop,
                    "Unknown output object retained");
                drop.worldObj.removePlayerEntityDangerously(drop);
            }
            drops.clear();
            cursorClose = null;
            materials.clear();
            row.setTag("Materials", new NBTTagList());
            row.setString("XPSource", "");
            row.setInteger("XPSeed", 0);
            session.maid.setExperience(0);
            require(
                playerInventory(session.player).equals(row.getTagList("PrimaryBase", 10))
                    && playerInventory(secondary).equals(row.getTagList("SecondaryBase", 10)),
                "Original native player bases differ; backups retained");
        }

        void sync() {
            for (var player : new EntityPlayerMP[] { session.player, secondary }) {
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                if (player.openContainer != player.inventoryContainer) player.openContainer.detectAndSendChanges();
                player.sendPlayerAbilities();
            }
        }
    }

    private static final class CursorClose {

        final ContainerMaid window;
        final int windowId;
        final ItemStack cursor;
        final long armedAt;
        final boolean fullInventory;
        EntityItem drop;
        NBTTagCompound nativeAtJoin;
        boolean slotFreed;
        boolean returnVerified;

        CursorClose(ContainerMaid window, ItemStack cursor, long armedAt, boolean fullInventory) {
            this.window = window;
            this.windowId = window.windowId;
            this.cursor = cursor;
            this.armedAt = armedAt;
            this.fullInventory = fullInventory;
        }
    }
}
