package com.tianluoqaq.tlmnh.common.development.interaction;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidServerLoadProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.protection.Sha256Hex;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** Real death/claim/shrine inputs for one owned temporary actor; never creates a replacement maid. */
final class MaidModelLifecycleActions {

    static final String KEY = "ModelLifecycle";
    private static final String MODEL = "geckolib:winefox_magical_a12dc19190c076d8d24aff8714515c8e";
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Schema",
            "Stage",
            "Source",
            "Model",
            "SourceNative",
            "Token",
            "Grave",
            "Candidate",
            "CandidateNative",
            "EmptyTile",
            "FilledTile",
            "FilmInventory",
            "RevivedNative",
            "LedgerSchema",
            "OriginalMetadata",
            "Long"));
    private static final Set<String> STAGES = new HashSet<>(
        Arrays.asList(
            "ready",
            "dead",
            "claim-armed",
            "claimed",
            "insert-armed",
            "inserted",
            "revive-armed",
            "revived",
            "restart"));
    private static Session owner;
    private static Session longOwner;
    private static MaidServerLoadProbe longProbe;
    private static JsonObject lastLongLoad;
    private static LongActivities longActivities;

    private MaidModelLifecycleActions() {}

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static boolean handles(String action) {
        return action.startsWith("world-life-");
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(data.hasKey(KEY, Constants.NBT.TAG_COMPOUND), "Unknown model lifecycle snapshot retained");
        require(
            data.getString("Phase")
                .equals("model-lifecycle"),
            "Unknown lifecycle session phase retained");
        var state = data.getCompoundTag(KEY);
        for (Object key : state.func_150296_c())
            require(FIELDS.contains(key), "Unknown model lifecycle field retained");
        require(
            state.hasKey("Schema", Constants.NBT.TAG_INT) && state.getInteger("Schema") == 1
                && state.hasKey("Stage", Constants.NBT.TAG_STRING)
                && STAGES.contains(state.getString("Stage")),
            "Invalid lifecycle stage retained");
        for (String key : new String[] { "Source", "Model" })
            require(state.hasKey(key, Constants.NBT.TAG_STRING), "Invalid lifecycle identity");
        UUID.fromString(state.getString("Source"));
        for (String key : new String[] { "Token", "Grave", "Candidate" }) if (state.hasKey(key)) {
            require(state.hasKey(key, Constants.NBT.TAG_STRING), "Invalid lifecycle UUID type");
            UUID.fromString(state.getString(key));
        }
        for (String key : new String[] { "SourceNative", "CandidateNative", "EmptyTile", "FilledTile",
            "RevivedNative" })
            if (state.hasKey(key))
                require(state.hasKey(key, Constants.NBT.TAG_COMPOUND), "Invalid lifecycle raw snapshot");
        require(
            state.hasKey("OriginalMetadata", Constants.NBT.TAG_INT) && state.getInteger("OriginalMetadata") == 0,
            "Unknown original air metadata retained");
        if (state.hasKey("LedgerSchema"))
            require(state.hasKey("LedgerSchema", Constants.NBT.TAG_INT), "Unknown ledger schema retained");
        if (state.hasKey("Long")) {
            require(state.hasKey("Long", Constants.NBT.TAG_COMPOUND), "Unknown long-session data retained");
            var record = state.getCompoundTag("Long");
            for (Object key : record.func_150296_c()) require(
                Arrays
                    .asList(
                        "Profile",
                        "StartedAt",
                        "Ticks",
                        "Stage",
                        "Configured",
                        "Activity",
                        "SleepCount",
                        "MealCount",
                        "MeleeCount",
                        "TailSampling",
                        "Resources",
                        "ResourcesCleared",
                        "FinalNative",
                        "CleanupRecovery")
                    .contains(key),
                "Unknown long-session field retained");
            require(
                record.hasKey("Profile", Constants.NBT.TAG_STRING) && Arrays.asList("faithful_tlm", "enhanced")
                    .contains(record.getString("Profile"))
                    && record.hasKey("StartedAt", Constants.NBT.TAG_LONG)
                    && record.hasKey("Ticks", Constants.NBT.TAG_INT)
                    && (record.getInteger("Ticks") == 9000 || record.getInteger("Ticks") == 24000)
                    && record.hasKey("Stage", Constants.NBT.TAG_STRING)
                    && Arrays.asList("running", "finished")
                        .contains(record.getString("Stage")),
                "Invalid long-session plan retained");
            if (record.getInteger("Ticks") == 24000) {
                require(
                    record.hasKey("Configured", Constants.NBT.TAG_BYTE) && record.getByte("Configured") == 1
                        && record.getString("Profile")
                            .equals("enhanced")
                        && record.hasKey("Activity", Constants.NBT.TAG_STRING)
                        && Arrays.asList("idle", "sleep", "wake", "meal", "melee")
                            .contains(record.getString("Activity")),
                    "Unknown configured long-session plan retained");
                for (String counter : new String[] { "SleepCount", "MealCount", "MeleeCount" }) require(
                    record.hasKey(counter, Constants.NBT.TAG_INT) && record.getInteger(counter) >= 0,
                    "Invalid activity counter retained");
                for (String flag : new String[] { "TailSampling", "ResourcesCleared" }) require(
                    record.hasKey(flag, Constants.NBT.TAG_BYTE)
                        && (record.getByte(flag) == 0 || record.getByte(flag) == 1),
                    "Unknown long-session flag retained");
                require(
                    record.hasKey("Resources", Constants.NBT.TAG_COMPOUND),
                    "Missing finite long-session resources retained");
                var resources = record.getCompoundTag("Resources");
                for (Object key : resources.func_150296_c()) require(
                    Arrays.asList("BedFoot", "BedHead", "Soup", "Sword", "TargetNative")
                        .contains(key),
                    "Unknown resource field retained");
                for (String field : new String[] { "BedFoot", "BedHead", "Soup", "Sword" }) require(
                    resources.hasKey(field, Constants.NBT.TAG_COMPOUND),
                    "Missing finite resource snapshot retained");
                if (resources.hasKey("TargetNative")) require(
                    resources.hasKey("TargetNative", Constants.NBT.TAG_COMPOUND),
                    "Unknown target snapshot retained");
                if (record.hasKey("FinalNative")) require(
                    record.hasKey("FinalNative", Constants.NBT.TAG_COMPOUND),
                    "Unknown final native snapshot retained");
                if (record.hasKey("CleanupRecovery")) {
                    require(
                        record.hasKey("CleanupRecovery", Constants.NBT.TAG_COMPOUND),
                        "Unknown cleanup recovery retained");
                    var proof = record.getCompoundTag("CleanupRecovery");
                    require(
                        proof.func_150296_c()
                            .size() == 6,
                        "Unknown cleanup recovery fields retained");
                    for (String field : new String[] { "OriginalCandidate", "OriginalRun", "CheckpointSha256",
                        "RecoveredByCandidate" })
                        require(
                            proof.hasKey(field, Constants.NBT.TAG_STRING),
                            "Invalid cleanup recovery identity retained");
                    require(
                        proof.hasKey("NativeTick", Constants.NBT.TAG_LONG)
                            && proof.hasKey("RawCpuAvailable", Constants.NBT.TAG_BYTE)
                            && proof.getByte("RawCpuAvailable") == 0,
                        "Unknown recovery observation retained");
                    require(
                        proof.getString("OriginalRun")
                            .equals(data.getString("Run"))
                            && proof.getString("OriginalCandidate")
                                .matches("[0-9a-f]{64}")
                            && proof.getString("RecoveredByCandidate")
                                .matches("[0-9a-f]{64}")
                            && proof.getString("CheckpointSha256")
                                .matches("[0-9a-f]{64}"),
                        "Invalid recovery source binding retained");
                }
            } else require(!record.hasKey("Configured"), "Unknown old long-session extension retained");
        }
        if (state.hasKey("FilmInventory")) require(
            state.hasKey("FilmInventory", Constants.NBT.TAG_LIST)
                && ((NBTTagList) state.getTag("FilmInventory")).tagCount()
                    == state.getTagList("FilmInventory", Constants.NBT.TAG_COMPOUND)
                        .tagCount(),
            "Invalid lifecycle player inventory");
    }

    private static NBTTagCompound state(Session session) {
        validateSnapshot(session.data);
        return session.data.getCompoundTag(KEY);
    }

    private static NBTTagCompound snapshot(Entity entity) {
        var tag = new NBTTagCompound();
        var type = EntityList.getEntityString(entity);
        require(type != null, "Registered actual entity required");
        tag.setString("id", type);
        entity.writeToNBT(tag);
        return (NBTTagCompound) tag.copy();
    }

    private static NBTTagCompound entry(Session session) {
        var all = new NBTTagCompound();
        MaidLifecycleData.get(session.player.worldObj)
            .writeToNBT(all);
        var state = state(session);
        var entries = all.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
        NBTTagCompound result = null;
        for (int i = 0; i < entries.tagCount(); i++) {
            var row = entries.getCompoundTagAt(i);
            if (!row.getString("Subject")
                .equals(state.getString("Source"))) continue;
            require(
                result == null && row.getString("Owner")
                    .equals(session.data.getString("Owner"))
                    && row.getString("Kind")
                        .equals("FILM"),
                "Unknown source ledger successor retained");
            result = row;
        }
        require(result != null, "Native death ledger entry required");
        return result;
    }

    private static ItemStack film(Session session) {
        return MaidLifecycleItems.credential(
            MaidResurrectionContent.FILM,
            UUID.fromString(state(session).getString("Token")),
            session.player.getUniqueID());
    }

    private static MaidShrineTile shrine(Session session) {
        var world = session.player.worldObj;
        require(
            world.getBlock(7, 65, 0) == MaidResurrectionContent.SHRINE && world.getBlockMetadata(7, 65, 0) == 0
                && world.getTileEntity(7, 65, 0) instanceof MaidShrineTile,
            "Original fixture shrine changed; retained");
        var tile = (MaidShrineTile) world.getTileEntity(7, 65, 0);
        var actual = new NBTTagCompound();
        tile.writeToNBT(actual);
        var state = state(session);
        require(
            actual.equals(state.getCompoundTag("EmptyTile")) || state.hasKey("FilledTile", Constants.NBT.TAG_COMPOUND)
                && actual.equals(state.getCompoundTag("FilledTile")),
            "Unknown shrine tile successor retained");
        return tile;
    }

    static boolean transitional(Session session) {
        if (!session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)) return false;
        var stage = state(session).getString("Stage");
        return !stage.equals("ready") && !stage.equals("revived") && !stage.equals("restart");
    }

    static boolean allowedHealth(Session session) {
        if (!session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)) return false;
        var stage = state(session).getString("Stage");
        return (stage.equals("revive-armed") || stage.equals("revived")) && session.player.getHealth() == .25F
            && entry(session).getBoolean("Consumed");
    }

    static boolean validateActor(Session session) {
        if (!transitional(session)) return false;
        var state = state(session);
        require(
            session.maid.getUniqueID()
                .toString()
                .equals(state.getString("Source")) && session.validNonce(session.maid)
                && session.maid.worldObj == session.player.worldObj
                && session.maid.func_152113_b()
                    .equals(session.data.getString("Owner"))
                && session.maid.getModelId()
                    .equals(state.getString("Model"))
                && session.maid.getHealth() <= 0
                && session.maid.getCustomNameTag()
                    .isEmpty(),
            "Unknown original death actor successor retained");
        session.emptyActor();
        shrine(session);
        return true;
    }

    static boolean isSpawning(Entity entity) {
        var session = owner;
        if (session == null || !MaidCareInteractionFixture.owns(session)
            || !session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)
            || entity.worldObj != session.player.worldObj) return false;
        var state = state(session);
        if (longActivities != null && longActivities.session == session && longActivities.ownsTarget(entity))
            return true;
        var ledger = MaidLifecycleData.get(entity.worldObj);
        var token = ledger.deathToken(UUID.fromString(state.getString("Source")));
        if (token == null || !session.player.getUniqueID()
            .equals(ledger.owner(token, MaidLifecycleData.Kind.FILM))) return false;
        if (entity instanceof MaidTombstoneEntity grave) {
            if (!token.equals(grave.getToken()) || !session.player.getUniqueID()
                .equals(grave.getOwnerId())) return false;
            if (state.hasKey("Grave", Constants.NBT.TAG_STRING) && !state.getString("Grave")
                .equals(
                    grave.getUniqueID()
                        .toString()))
                return false;
            var publication = entry(session).getCompoundTag("TombstonePublication");
            if (!publication.getString("Stage")
                .equals("PREPARED")
                || !publication.getCompoundTag("Snapshot")
                    .equals(snapshot(grave)))
                return false;
            state.setString(
                "Grave",
                grave.getUniqueID()
                    .toString());
            return true;
        }
        if (!(entity instanceof EntityMaid maid) || !state.getString("Stage")
            .equals("revive-armed") || !session.maid.isDead || entity.worldObj.loadedEntityList.contains(session.maid))
            return false;
        var intent = ledger.redemption(token);
        if (!intent.getString("Actor")
            .equals(session.data.getString("Owner"))
            || !intent.getString("Candidate")
                .equals(
                    maid.getUniqueID()
                        .toString())
            || !intent.getCompoundTag("Maid")
                .equals(snapshot(maid))
            || !maid.func_152113_b()
                .equals(session.data.getString("Owner"))
            || !maid.getModelId()
                .equals(state.getString("Model")))
            return false;
        for (Object value : entity.worldObj.loadedEntityList) if (value instanceof Entity other && other != entity
            && other.getUniqueID()
                .equals(entity.getUniqueID()))
            return false;
        // The pending candidate remains untouched: its original publication owns every native byte.
        state.setString(
            "Candidate",
            maid.getUniqueID()
                .toString());
        state.setTag(
            "CandidateNative",
            intent.getCompoundTag("Maid")
                .copy());
        return true;
    }

    static void run(Session session, String action) {
        owner = session;
        if (action.equals("world-life-prepare")) {
            require(
                !session.data.hasKey(KEY) && session.data.getString("Phase")
                    .equals("prepared"),
                "Separate empty prepared care session required");
            session.emptyActor();
            session.hand(null, null);
            var world = session.player.worldObj;
            require(
                world.isAirBlock(7, 65, 0) && world.getTileEntity(7, 65, 0) == null
                    && world.getBlockMetadata(7, 65, 0) == 0
                    && world.isAirBlock(7, 66, 0)
                    && world.isAirBlock(7, 67, 0),
                "Empty dedicated shrine location required");
            var state = new NBTTagCompound();
            state.setInteger("Schema", 1);
            state.setString("Stage", "ready");
            state.setString(
                "Source",
                session.maid.getUniqueID()
                    .toString());
            state.setString("Model", MODEL);
            state.setInteger("OriginalMetadata", world.getBlockMetadata(7, 65, 0));
            session.data.setTag(KEY, state);
            session.data.setString("Phase", "model-lifecycle");
            session.maid.setModelId(MODEL);
            session.maid.setHealth(session.maid.getMaxHealth());
            session.maid.setSitting(true);
            session.data.setString("ExpectedModel", MODEL);
            require(
                world.setBlock(7, 65, 0, MaidResurrectionContent.SHRINE, 0, 3),
                "Native fixture shrine placement failed");
            var empty = new NBTTagCompound();
            ((MaidShrineTile) world.getTileEntity(7, 65, 0)).writeToNBT(empty);
            state.setTag("EmptyTile", empty);
            state.setTag("SourceNative", snapshot(session.maid));
            return;
        }
        var state = state(session);
        if (action.startsWith("world-life-activity-")) {
            requireLong(session);
            require(
                longActivities != null && longActivities.session == session,
                "Original current-process finite activity resources required");
            longActivities.action(action.substring("world-life-activity-".length()));
            return;
        }
        switch (action) {
            case "world-life-die" -> {
                require(
                    state.getString("Stage")
                        .equals("ready"),
                    "Original living source required");
                shrine(session);
                state.setString("Stage", "dead");
                session.maid.setHealth(0);
                session.maid.onDeath(DamageSource.outOfWorld);
                var row = entry(session);
                require(
                    row.getBoolean("Delivered") && row.getCompoundTag("DeathIngress")
                        .getString("Stage")
                        .equals("COMMITTED"),
                    "Actual source death must be durably committed");
                state.setString("Token", row.getString("Token"));
                require(
                    !state.getString("Grave")
                        .isEmpty(),
                    "Native exact tombstone publication required");
            }
            case "world-life-arm-claim" -> {
                require(
                    state.getString("Stage")
                        .equals("dead") && session.maid.isDead
                        && !session.player.worldObj.loadedEntityList.contains(session.maid),
                    "Wait for native source death removal");
                var planning = new InventoryPlayer(session.player);
                require(
                    planning.getClass() == session.player.inventory.getClass(),
                    "Native inventory planning class changed");
                for (int i = 0; i < planning.mainInventory.length; i++)
                    planning.mainInventory[i] = ItemStack.copyItemStack(session.player.inventory.mainInventory[i]);
                for (int i = 0; i < planning.armorInventory.length; i++)
                    planning.armorInventory[i] = ItemStack.copyItemStack(session.player.inventory.armorInventory[i]);
                planning.currentItem = session.slot();
                var input = film(session);
                require(
                    planning.addItemStackToInventory(input) && input.stackSize == 0,
                    "Native full inventory claim plan required");
                var expected = planning.writeToNBT(new NBTTagList());
                state.setTag("FilmInventory", expected.copy());
                session.data.setTag("ResultInventory", expected);
                state.setString("Stage", "claim-armed");
            }
            case "world-life-verify-claim" -> {
                require(
                    state.getString("Stage")
                        .equals("claim-armed"),
                    "Actual C02 claim required");
                session.resultInventory();
                require(
                    MaidLifecycleData.get(session.player.worldObj)
                        .tombstoneEmpty(UUID.fromString(state.getString("Token"))),
                    "Claim ledger must be empty");
                session.data.setTag(
                    "ExpectedInventory",
                    session.data.getTag("ResultInventory")
                        .copy());
                session.data.removeTag("ResultInventory");
                state.setString("Stage", "claimed");
            }
            case "world-life-arm-insert" -> {
                require(
                    state.getString("Stage")
                        .equals("claimed"),
                    "Verified native claim required");
                int found = -1;
                for (int i = 0; i < session.player.inventory.mainInventory.length; i++)
                    if (ItemStack.areItemStacksEqual(session.player.inventory.mainInventory[i], film(session))) {
                        require(found == -1, "Ambiguous exact film retained");
                        found = i;
                    }
                require(
                    found >= 0
                        && (found == session.slot() || session.player.inventory.getStackInSlot(session.slot()) == null),
                    "Original empty selected slot required");
                var stack = session.player.inventory.mainInventory[found];
                session.player.inventory.mainInventory[found] = null;
                session.player.inventory.setInventorySlotContents(session.slot(), stack);
                session.data.setTag("ExpectedInventory", playerInventory(session));
                var after = (NBTTagList) playerInventory(session).copy();
                var without = new NBTTagList();
                for (int i = 0; i < after.tagCount(); i++) if ((after.getCompoundTagAt(i)
                    .getByte("Slot") & 255) != session.slot()) without.appendTag(
                        after.getCompoundTagAt(i)
                            .copy());
                session.data.setTag("ResultInventory", without);
                var filled = (NBTTagCompound) state.getCompoundTag("EmptyTile")
                    .copy();
                filled.setTag("StorageItem", ProtectedItemCodec.encode(film(session)));
                state.setTag("FilledTile", filled);
                state.setString("Stage", "insert-armed");
                session.sync();
            }
            case "world-life-verify-insert" -> {
                require(
                    state.getString("Stage")
                        .equals("insert-armed")
                        && ItemStack.areItemStacksEqual(shrine(session).getFilm(), film(session)),
                    "Actual C08 shrine insert required");
                session.resultInventory();
                session.data.setTag(
                    "ExpectedInventory",
                    session.data.getTag("ResultInventory")
                        .copy());
                session.data.removeTag("ResultInventory");
                state.setString("Stage", "inserted");
            }
            case "world-life-arm-revive" -> {
                require(
                    state.getString("Stage")
                        .equals("inserted") && session.player.getHealth() == session.data.getFloat("OriginalHealth")
                        && !session.player.capabilities.isCreativeMode
                        && session.player.getCurrentEquippedItem() == null,
                    "Normal survival empty-hand resurrection input required");
                state.setString("Stage", "revive-armed");
            }
            case "world-life-verify-revived" -> {
                require(
                    state.getString("Stage")
                        .equals("revive-armed") && entry(session).getBoolean("Consumed")
                        && MaidLifecycleData.get(session.player.worldObj)
                            .redemption(UUID.fromString(state.getString("Token")))
                            .hasNoTags()
                        && session.player.getHealth() == .25F
                        && shrine(session).getFilm() == null,
                    "Actual committed resurrection and native life cost required");
                EntityMaid actual = null;
                for (Object value : session.player.worldObj.loadedEntityList)
                    if (value instanceof EntityMaid candidate && candidate.getUniqueID()
                        .toString()
                        .equals(state.getString("Candidate"))) {
                            require(
                                actual == null && candidate.isEntityAlive()
                                    && !MaidLifecycleService.isPendingRedemption(candidate),
                                "Unique settled native candidate required");
                            actual = candidate;
                        }
                require(
                    actual != null && actual.func_152113_b()
                        .equals(session.data.getString("Owner"))
                        && actual.getModelId()
                            .equals(state.getString("Model")),
                    "Original model/owner resurrection required");
                state.setTag("RevivedNative", snapshot(actual));
                // Only after the production ledger has consumed the credential may the fixture own its nonce.
                require(
                    !actual.getEntityData()
                        .hasKey(MaidCareInteractionFixture.TAG),
                    "Foreign candidate fixture data retained");
                actual.getEntityData()
                    .setString(MaidCareInteractionFixture.TAG, session.data.getString("Run"));
                actual.setSitting(true);
                actual.getBrainCoordinator()
                    .setSuspended(true);
                session.maid = actual;
                session.data.setString(
                    "Actor",
                    actual.getUniqueID()
                        .toString());
                state.setString("Stage", "revived");
                session.player.setHealth(session.data.getFloat("OriginalHealth"));
                var ledger = new NBTTagCompound();
                MaidLifecycleData.get(session.player.worldObj)
                    .writeToNBT(ledger);
                state.setInteger("LedgerSchema", ledger.getInteger("Schema"));
            }
            case "world-life-long-f", "world-life-long-n", "world-life-long-configured" -> {
                require(
                    state.getString("Stage")
                        .equals("revived") && longProbe == null
                        && (!state.hasKey("Long") || state.getCompoundTag("Long")
                            .getString("Stage")
                            .equals("finished")),
                    "Settled original revived actor and a separate timed segment required");
                boolean configured = action.equals("world-life-long-configured");
                require(
                    !configured || MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
                    "Normal startup behavior.profile=enhanced required; no development selection is made");
                var profile = action.endsWith("-f") ? MaidBrainProfile.FAITHFUL_TLM : MaidBrainProfile.ENHANCED;
                session.maid.setTask(MaidTask.IDLE);
                session.maid.setSchedule(MaidSchedule.DAY);
                session.maid.setSitting(false);
                if (configured) {
                    var runtime = session.maid.getBrainCoordinator()
                        .getPolicyRuntime();
                    require(
                        runtime != null && runtime.isReady()
                            && runtime.getProfile() == MaidBrainProfile.ENHANCED
                            && runtime.legacyDispatchCount() == 0,
                        "Normal complete Enhanced factory must already resolve from server configuration");
                } else {
                    var selected = session.maid.getBrainCoordinator()
                        .selectProfileForDevelopment(profile);
                    require(
                        selected == MaidPolicyRuntime.SelectionResult.SELECTED
                            || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED,
                        "Actual complete long-session factory required");
                }
                session.maid.getBrainCoordinator()
                    .setSuspended(false);
                var record = new NBTTagCompound();
                record.setString("Profile", profile.getId());
                record.setLong("StartedAt", session.maid.worldObj.getTotalWorldTime());
                record.setInteger("Ticks", configured ? 24000 : 9000);
                record.setString("Stage", "running");
                state.setTag("Long", record);
                if (configured) {
                    record.setBoolean("Configured", true);
                    record.setString("Activity", "idle");
                    record.setInteger("SleepCount", 0);
                    record.setInteger("MealCount", 0);
                    record.setInteger("MeleeCount", 0);
                    record.setBoolean("TailSampling", false);
                    record.setBoolean("ResourcesCleared", false);
                    // This constructor only supplies finite inputs; all actions run in native server updates.
                    longActivities = new LongActivities(session, record);
                }
                try {
                    longProbe = MaidServerLoadProbe.start(
                        session.data.getString("Run"),
                        "two-client-" + profile.getId(),
                        (WorldServer) session.maid.worldObj);
                    longOwner = session;
                    lastLongLoad = null;
                    int listed = 0;
                    for (Object entity : session.maid.worldObj.loadedEntityList)
                        if (entity instanceof EntityMaid living && !living.isDead) listed++;
                    longProbe.phase(profile.getId(), listed);
                } catch (Exception failure) {
                    throw new IllegalStateException(
                        "Native server load observation unavailable; no wall-clock replacement",
                        failure);
                }
            }
            case "world-life-long-arm-relogin" -> {
                requireLong(session);
                require(!session.data.getBoolean("AwaitLogin"), "Original reconnect already armed");
                session.data.setBoolean("Rejoined", false);
                session.data.setBoolean("AwaitLogin", true);
            }
            case "world-life-long-verify-relogin" -> {
                requireLong(session);
                require(
                    !session.data.getBoolean("AwaitLogin") && session.data.getBoolean("Rejoined"),
                    "Actual native owner reconnect required");
            }
            case "world-life-long-finish" -> {
                requireLong(session);
                var record = state.getCompoundTag("Long");
                require(
                    session.maid.worldObj.getTotalWorldTime() - record.getLong("StartedAt")
                        >= record.getInteger("Ticks"),
                    "The planned number of actual server ticks is required");
                if (record.getBoolean("Configured")) {
                    require(
                        record.getInteger("SleepCount") >= 2 && record.getInteger("MealCount") >= 2
                            && record.getInteger("MeleeCount") >= 2
                            && record.getString("Activity")
                                .equals("idle"),
                        "Two complete natural sleep/meal/melee sequences required; untriggered work is not coverage");
                    longActivities.clear();
                    longActivities = null;
                    record.setBoolean("ResourcesCleared", true);
                }
                closeLong(session);
                record.setString("Stage", "finished");
                record.setTag("FinalNative", snapshot(session.maid));
                session.maid.getBrainCoordinator()
                    .setSuspended(true);
                session.maid.setSitting(true);
            }
            case "world-life-arm-restart" -> {
                require(
                    state.getString("Stage")
                        .equals("revived") && session.maid.isSitting(),
                    "Settled sitting non-scene actor required");
                session.inventoryOwned();
                state.setString("Stage", "restart");
                session.data.setBoolean("AwaitLogin", true);
                session.player.playerNetServerHandler.setPlayerLocation(
                    session.data.getDouble("OriginalX"),
                    session.data.getDouble("OriginalY"),
                    session.data.getDouble("OriginalZ"),
                    session.data.getFloat("OriginalYaw"),
                    session.data.getFloat("OriginalPitch"));
                session.data.setDouble("ExpectedX", session.data.getDouble("OriginalX"));
                session.data.setDouble("ExpectedY", session.data.getDouble("OriginalY"));
                session.data.setDouble("ExpectedZ", session.data.getDouble("OriginalZ"));
                session.player.setGameType(GameType.getByID(3));
                session.sync();
            }
            default -> throw new IllegalArgumentException("Unknown model lifecycle action");
        }
    }

    private static NBTTagList playerInventory(Session session) {
        return (NBTTagList) session.player.inventory.writeToNBT(new NBTTagList())
            .copy();
    }

    static boolean restart(Session session) {
        return session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND) && state(session).getString("Stage")
            .equals("restart");
    }

    static boolean allowsRelogin(Session session) {
        if (!session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)) return false;
        var state = state(session);
        return state.getString("Stage")
            .equals("revived") && state.hasKey("Long", Constants.NBT.TAG_COMPOUND)
            && state.getCompoundTag("Long")
                .getString("Stage")
                .equals("running")
            && longOwner == session
            && longProbe != null;
    }

    /** One explicit cold finish of a previously completed long run; does not recreate input or CPU samples. */
    static void recoverCompletedLong(Session session) {
        require(
            longProbe == null && longOwner == null && longActivities == null,
            "Cold recovery requires a fresh JVM; live recorder ownership is never replaced");
        var state = state(session);
        var record = state.getCompoundTag("Long");
        require(
            state.getString("Stage")
                .equals("revived") && record.getBoolean("Configured")
                && record.getString("Stage")
                    .equals("running")
                && record.getInteger("Ticks") == 24000
                && record.getString("Activity")
                    .equals("idle")
                && record.getBoolean("TailSampling")
                && !record.getBoolean("ResourcesCleared")
                && !record.hasKey("CleanupRecovery")
                && record.getInteger("SleepCount") == 2
                && record.getInteger("MealCount") == 2
                && record.getInteger("MeleeCount") == 2
                && session.maid.worldObj.getTotalWorldTime() - record.getLong("StartedAt") >= 24000,
            "Only the original completed24000-tick plan with six actual activities is recoverable");
        require(
            MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED && session.validNonce(session.maid)
                && session.maid.isEntityAlive()
                && session.maid.worldObj == session.player.worldObj
                && session.maid.worldObj.getEntityByID(session.maid.getEntityId()) == session.maid
                && session.maid.getModelId()
                    .equals(state.getString("Model"))
                && entry(session).getBoolean("Consumed")
                && shrine(session).getFilm() == null,
            "Original current-world settled actor/model/consumed film and empty shrine required");
        for (int slot = 0; slot < session.maid.getMaidInventory()
            .getSizeInventory(); slot++)
            require(
                session.maid.getMaidInventory()
                    .getStackInSlot(slot) == null,
                "Unknown retained actor material");
        require(
            session.maid.getMaidInventory()
                .recovery()
                .write()
                .tagCount() == 0,
            "Unknown recovery material retained");
        session.inventoryOwned();
        require(
            session.player.isEntityAlive() && session.player.getHealth() == session.data.getFloat("OriginalHealth")
                && session.player.inventory.currentItem == session.slot()
                && session.player.inventory.getItemStack() == null
                && session.player.openContainer == session.player.inventoryContainer
                && session.player.getDistanceSq(
                    session.data.getDouble("ExpectedX"),
                    session.data.getDouble("ExpectedY"),
                    session.data.getDouble("ExpectedZ")) < .01,
            "Original protected player position/health/selection/container required; no inventory or camera overwrite");
        var checkpointSha = System.getProperty("tlmnh.careLongRecoveryCheckpointSha256", "");
        var checkpointPath = System.getProperty("tlmnh.careLongRecoveryCheckpoint", "");
        require(
            checkpointSha.matches("[0-9a-f]{64}") && !checkpointPath.isEmpty(),
            "Exact stopped native checkpoint required");
        NBTTagCompound checkpoint;
        String currentCandidate;
        try {
            var path = Paths.get(checkpointPath);
            require(
                Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files.size(path) <= 8 * 1024 * 1024,
                "Ordinary bounded read-only checkpoint file required");
            var raw = Files.readAllBytes(path);
            require(checkpointSha.equals(Sha256Hex.ofBytes(raw)), "Stopped checkpoint bytes changed");
            try (var input = new DataInputStream(new ByteArrayInputStream(raw))) {
                checkpoint = CompressedStreamTools.read(input);
                require(input.available() == 0, "Checkpoint trailing bytes retained");
            }
            currentCandidate = DevelopmentArtifactIdentity
                .capture(MaidCareInteractionFixture.class, MaidModelLifecycleActions.class)
                .get("artifactSha256")
                .getAsString();
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Cannot bind original stopped cleanup checkpoint; all source retained",
                failure);
        }
        require(
            checkpoint.func_150296_c()
                .size() == 9 && checkpoint.hasKey("Schema", Constants.NBT.TAG_INT)
                && checkpoint.getInteger("Schema") == 1,
            "Unknown one-run checkpoint format retained");
        for (String key : new String[] { "OriginalCandidate", "OriginalRun", "Owner", "Actor", "SourceReportSha256" })
            require(checkpoint.hasKey(key, Constants.NBT.TAG_STRING), "Invalid original checkpoint identity type");
        for (String key : new String[] { "Care", "ActorNative", "BedHead" }) require(
            checkpoint.hasKey(key, Constants.NBT.TAG_COMPOUND),
            "Complete original stopped native payload required");
        String originalCandidate = checkpoint.getString("OriginalCandidate");
        require(
            originalCandidate.matches("[0-9a-f]{64}") && !originalCandidate.equals(currentCandidate)
                && checkpoint.getString("SourceReportSha256")
                    .matches("[0-9a-f]{64}")
                && checkpoint.getString("OriginalRun")
                    .equals(session.data.getString("Run"))
                && checkpoint.getString("Owner")
                    .equals(session.data.getString("Owner"))
                && checkpoint.getString("Actor")
                    .equals(session.data.getString("Actor"))
                && checkpoint.getCompoundTag("Care")
                    .equals(session.data),
            "Exact old candidate/run/actor/full Care snapshot required; candidates are not merged into one pass");
        var expected = checkpoint.getCompoundTag("ActorNative");
        var actual = snapshot(session.maid);
        var fields = new HashSet<Object>(expected.func_150296_c());
        fields.addAll(actual.func_150296_c());
        for (Object rawKey : fields) {
            String key = (String) rawKey;
            if (Arrays
                .asList(
                    "Pos",
                    "Motion",
                    "Rotation",
                    "Air",
                    "OnGround",
                    "Fire",
                    "Age",
                    "InLove",
                    "HurtTime",
                    "AttackTime",
                    "DeathTime",
                    "FallDistance",
                    "PortalCooldown",
                    "FavorabilityManagerCounter")
                .contains(key)) continue;
            require(
                Objects.equals(expected.getTag(key), actual.getTag(key)),
                "Stopped actor protected/unknown native field changed: " + key);
        }
        var beforeCounters = expected.getCompoundTag("FavorabilityManagerCounter");
        var afterCounters = actual.getCompoundTag("FavorabilityManagerCounter");
        require(
            expected.hasKey("FavorabilityManagerCounter", Constants.NBT.TAG_COMPOUND)
                && actual.hasKey("FavorabilityManagerCounter", Constants.NBT.TAG_COMPOUND)
                && beforeCounters.func_150296_c()
                    .equals(afterCounters.func_150296_c()),
            "Unknown native counter structure retained");
        for (Object rawKey : beforeCounters.func_150296_c()) {
            String key = (String) rawKey;
            require(
                beforeCounters.hasKey(key, Constants.NBT.TAG_INT) && afterCounters.hasKey(key, Constants.NBT.TAG_INT)
                    && afterCounters.getInteger(key) >= 0
                    && afterCounters.getInteger(key) <= beforeCounters.getInteger(key),
                "Native counter changed beyond legal decay: " + key);
        }
        require(
            checkpoint.getCompoundTag("BedHead")
                .equals(
                    record.getCompoundTag("Resources")
                        .getCompoundTag("BedHead")
                        .getCompoundTag("Tile")),
            "Actual stopped bed did not retain the original full empty tile payload");
        // All source guards precede mode/fixture mutations; no player inventory is rewritten.
        session.player.setGameType(GameType.SURVIVAL);
        session.protect();
        session.maid.getBrainCoordinator()
            .getPolicyRuntime();
        longActivities = new LongActivities(session, record, true);
        longActivities.verify();
        longActivities.clear();
        longActivities = null;
        record.setBoolean("ResourcesCleared", true);
        record.setString("Stage", "finished");
        record.setTag("FinalNative", snapshot(session.maid));
        var proof = new NBTTagCompound();
        proof.setString("OriginalCandidate", originalCandidate);
        proof.setString("OriginalRun", checkpoint.getString("OriginalRun"));
        proof.setString("CheckpointSha256", checkpointSha);
        proof.setString("RecoveredByCandidate", currentCandidate);
        proof.setLong("NativeTick", session.maid.worldObj.getTotalWorldTime());
        proof.setBoolean("RawCpuAvailable", false);
        record.setTag("CleanupRecovery", proof);
        session.maid.setSitting(true);
        owner = session;
    }

    static void resumed(Session session) {
        require(
            restart(session) && session.validNonce(session.maid)
                && session.maid.isSitting()
                && session.maid.getModelId()
                    .equals(state(session).getString("Model"))
                && entry(session).getBoolean("Consumed"),
            "Original native non-scene actor and consumed ledger required");
        owner = session;
        session.data.setBoolean("AwaitLogin", false);
        state(session).setString("Stage", "revived");
        boolean configured = state(session).hasKey("Long", Constants.NBT.TAG_COMPOUND)
            && state(session).getCompoundTag("Long")
                .getBoolean("Configured");
        if (configured) {
            require(
                MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
                "Restart must retain the normal Enhanced startup configuration");
            var saved = state(session).getCompoundTag("Long")
                .getCompoundTag("FinalNative");
            var loaded = snapshot(session.maid);
            for (String key : new String[] { "OwnerUUID", "ModelId", "SoundPackId", "MaidInventory", "Equipment",
                "TLMNHOffhand", "TLMNHRecovery", "MaidExperience", "MaidFavorability", "TLMNH", "ForgeData" })
                require(
                    Objects.equals(saved.getTag(key), loaded.getTag(key)),
                    "Restart changed protected complete native field: " + key);
            // Native favorability counters continue to decay even while the actor is sitting/suspended.
            // The final live snapshot precedes root's normal save/stop, so it is not the disk-time counter.
            require(
                saved.hasKey("FavorabilityManagerCounter", Constants.NBT.TAG_COMPOUND)
                    && loaded.hasKey("FavorabilityManagerCounter", Constants.NBT.TAG_COMPOUND),
                "Typed native favorability counters required");
            var beforeCounters = saved.getCompoundTag("FavorabilityManagerCounter");
            var afterCounters = loaded.getCompoundTag("FavorabilityManagerCounter");
            require(
                beforeCounters.func_150296_c()
                    .equals(afterCounters.func_150296_c()),
                "Counter keys changed across restart");
            for (Object rawKey : beforeCounters.func_150296_c()) {
                String key = (String) rawKey;
                require(
                    beforeCounters.hasKey(key, Constants.NBT.TAG_INT)
                        && afterCounters.hasKey(key, Constants.NBT.TAG_INT)
                        && afterCounters.getInteger(key) >= 0
                        && afterCounters.getInteger(key) <= beforeCounters.getInteger(key),
                    "Counter changed beyond its normal monotone decay: " + key);
            }
            var runtime = session.maid.getBrainCoordinator()
                .getPolicyRuntime();
            require(
                runtime != null && runtime.isReady()
                    && runtime.getProfile() == MaidBrainProfile.ENHANCED
                    && runtime.legacyDispatchCount() == 0,
                "Restart must resolve the normal complete Enhanced factory without development selection");
            session.maid.setSitting(false);
        }
        session.maid.getBrainCoordinator()
            .setSuspended(!configured);
        session.player.setGameType(GameType.SURVIVAL);
        session.protect();
    }

    static void restore(Session session) {
        if (!session.data.hasKey(KEY)) return;
        var stage = state(session).getString("Stage");
        require(
            stage.equals("ready") || stage.equals("revived"),
            "Finish the original native lifecycle before cleanup; snapshot retained");
        if (longActivities != null && longActivities.session == session) {
            longActivities.clear();
            state(session).getCompoundTag("Long")
                .setBoolean("ResourcesCleared", true);
            longActivities = null;
        }
        closeLong(session);
        session.maid.getBrainCoordinator()
            .setSuspended(true);
        var tile = shrine(session);
        require(tile.getFilm() == null, "Unknown or unconsumed shrine material retained");
        require(session.player.worldObj.setBlockToAir(7, 65, 0), "Owned empty shrine removal failed");
        session.data.removeTag(KEY);
        session.data.setString("Phase", "prepared");
        if (owner == session) owner = null;
    }

    static JsonObject observation(Session session) {
        var result = new JsonObject();
        var state = state(session);
        result.addProperty("stage", state.getString("Stage"));
        for (String key : new String[] { "Source", "Model", "Token", "Grave", "Candidate" })
            result.addProperty(key, state.getString(key));
        int graveId = -1;
        for (Object value : session.player.worldObj.loadedEntityList)
            if (value instanceof MaidTombstoneEntity grave && grave.getUniqueID()
                .toString()
                .equals(state.getString("Grave"))) {
                    require(graveId == -1, "Ambiguous original tombstone retained");
                    graveId = grave.getEntityId();
                }
        result.addProperty("graveEntityId", graveId);
        result.addProperty("shrineX", 7);
        result.addProperty("shrineY", 65);
        result.addProperty("shrineZ", 0);
        result.addProperty("playerHealth", session.player.getHealth());
        result.addProperty("nativeModelId", session.maid.getModelId());
        result.addProperty("nativeActor", snapshot(session.maid).toString());
        result.addProperty(
            "sourceNative",
            state.getCompoundTag("SourceNative")
                .toString());
        result.addProperty(
            "revivedNative",
            state.getCompoundTag("RevivedNative")
                .toString());
        result.addProperty("ledgerSchema", state.getInteger("LedgerSchema"));
        result.addProperty("restartOwner", session.data.getString("Owner"));
        result.addProperty("restartActor", session.data.getString("Actor"));
        result.addProperty("restartRun", session.data.getString("Run"));
        var currentRuntime = session.maid.getBrainCoordinator()
            .peekPolicyRuntime();
        result.addProperty(
            "runtimeProfile",
            currentRuntime == null ? "none"
                : currentRuntime.getProfile()
                    .getId());
        result.addProperty("legacyDispatches", currentRuntime == null ? -1 : currentRuntime.legacyDispatchCount());
        result.addProperty("modernDispatches", currentRuntime == null ? -1 : currentRuntime.modernDispatchCount());
        if (state.hasKey("Long", Constants.NBT.TAG_COMPOUND)) {
            var record = state.getCompoundTag("Long");
            var timed = new JsonObject();
            timed.addProperty("stage", record.getString("Stage"));
            timed.addProperty("selectedProfile", record.getString("Profile"));
            timed.addProperty("elapsedTicks", session.maid.worldObj.getTotalWorldTime() - record.getLong("StartedAt"));
            timed.addProperty("requiredTicks", record.getInteger("Ticks"));
            var runtime = session.maid.getBrainCoordinator()
                .peekPolicyRuntime();
            timed.addProperty(
                "actualProfile",
                runtime == null ? "none"
                    : runtime.getProfile()
                        .getId());
            timed.addProperty("controlledFullProfiledActors", 1);
            timed.addProperty("crossRestartContinuousPolicy", false);
            timed.addProperty("configuredEntry", record.getBoolean("Configured"));
            if (record.hasKey("CleanupRecovery", Constants.NBT.TAG_COMPOUND)) {
                var proof = record.getCompoundTag("CleanupRecovery");
                var binding = new JsonObject();
                for (String key : new String[] { "OriginalCandidate", "OriginalRun", "CheckpointSha256",
                    "RecoveredByCandidate" }) binding.addProperty(key, proof.getString(key));
                binding.addProperty("NativeTick", proof.getLong("NativeTick"));
                binding.addProperty("RawCpuAvailable", false);
                timed.add("cleanupRecovery", binding);
            }
            timed.addProperty("activity", record.getString("Activity"));
            timed.addProperty("sampleTailOnly", record.getBoolean("TailSampling"));
            for (String counter : new String[] { "SleepCount", "MealCount", "MeleeCount" })
                timed.addProperty(counter, record.getInteger(counter));
            if (longActivities != null && longActivities.session == session)
                timed.add("activityNative", longActivities.observation());
            if (longOwner == session && longProbe != null) timed.addProperty("nativeLoadRecording", true);
            else if (lastLongLoad != null) timed.add("nativeLoad", lastLongLoad);
            result.add("longSession", timed);
        }
        return result;
    }

    private static void requireLong(Session session) {
        var state = state(session);
        require(
            state.getString("Stage")
                .equals("revived") && state.hasKey("Long", Constants.NBT.TAG_COMPOUND)
                && state.getCompoundTag("Long")
                    .getString("Stage")
                    .equals("running")
                && longOwner == session
                && longProbe != null
                && session.validNonce(session.maid),
            "Original current-process long session required");
        var runtime = session.maid.getBrainCoordinator()
            .peekPolicyRuntime();
        require(
            runtime != null && runtime.isReady()
                && runtime.getProfile()
                    .getId()
                    .equals(
                        state.getCompoundTag("Long")
                            .getString("Profile")),
            "Long-session factory changed; no automatic policy fallback accepted");
    }

    /** The normal Care limit remains unchanged; only this exact bounded current plan extends it. */
    static int sessionTickLimit(Session session) {
        if (!session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)) return 12000;
        var state = state(session);
        if (!state.hasKey("Long", Constants.NBT.TAG_COMPOUND) || !state.getCompoundTag("Long")
            .getBoolean("Configured")) return 12000;
        var record = state.getCompoundTag("Long");
        require(
            record.getInteger("Ticks") == 24000 && MaidCareInteractionFixture.owns(session)
                && session.validNonce(session.maid),
            "Unknown extended session retained");
        return 30000;
    }

    static boolean verifyLongInventory(Session session) {
        if (!session.data.hasKey(KEY, Constants.NBT.TAG_COMPOUND)) return false;
        var state = state(session);
        if (!state.hasKey("Long", Constants.NBT.TAG_COMPOUND)) return false;
        var record = state.getCompoundTag("Long");
        if (!record.getBoolean("Configured") || record.getBoolean("ResourcesCleared")) return false;
        require(
            longActivities != null && longActivities.session == session,
            "Interrupted finite activity resources retained; no replacement or inferred cleanup");
        longActivities.verify();
        return true;
    }

    static void tickLong(Session session) {
        if (longOwner != session || longProbe == null || longActivities == null) return;
        requireLong(session);
        longActivities.observe();
        var record = state(session).getCompoundTag("Long");
        if (!record.getBoolean("TailSampling")
            && session.maid.worldObj.getTotalWorldTime() - record.getLong("StartedAt") >= 23000) {
            int listed = 0;
            for (Object entity : session.maid.worldObj.loadedEntityList)
                if (entity instanceof EntityMaid living && !living.isDead) listed++;
            // Keep the existing bounded raw recorder; the final live ticks are explicitly an unsampled phase.
            longProbe.phase("configured-enhanced-live-tail", listed, false);
            record.setBoolean("TailSampling", true);
        }
    }

    /** Finite inputs within the existing Long plan, not a second session or an action executor. */
    private static final class LongActivities {

        final Session session;
        NBTTagCompound record;
        final MaidPolicyRuntime runtime;
        final ItemStack soup, sword;
        final TileEntityMaidBed bed;
        final NBTTagCompound bedRaw;
        NBTTagCompound expectedResources, targetData;
        EntityCow target;
        long meleeBefore;
        boolean sleepSeen, mealSeen;
        float firstMealHealth = Float.NaN;

        LongActivities(Session session, NBTTagCompound record) {
            this(session, record, false);
        }

        LongActivities(Session session, NBTTagCompound record, boolean retained) {
            this.session = session;
            this.record = record;
            runtime = session.maid.getBrainCoordinator()
                .peekPolicyRuntime();
            soup = marked(Items.mushroom_stew);
            sword = marked(Items.iron_sword);
            var food = MaidFoodProfile.inspect(soup);
            require(
                food != null && food.total > 0 && food.duration > 0,
                "Actual target nutrition and native use duration required; no guaranteed heal is assumed");
            var world = session.maid.worldObj;
            if (retained) {
                require(
                    world.getChunkProvider()
                        .chunkExists(0, -1) && world.getBlock(6, 65, -1) == MaidLifeContent.maidBed
                        && world.getBlockMetadata(6, 65, -1) == 1
                        && world.getChunkFromChunkCoords(0, -1)
                            .getTileEntityUnsafe(6, 65, 15) == null
                        && world.getBlock(5, 65, -1) == MaidLifeContent.maidBed
                        && world.getBlockMetadata(5, 65, -1) == 9,
                    "Only the existing recorded complete empty bed may be recovered");
            } else {
                for (int x : new int[] { 5, 6 }) require(
                    world.blockExists(x, 65, -1) && world.isAirBlock(x, 65, -1)
                        && world.getBlockMetadata(x, 65, -1) == 0
                        && world.getTileEntity(x, 65, -1) == null
                        && world.getBlock(x, 64, -1)
                            .getMaterial()
                            .isSolid(),
                    "Original loaded air pair on the existing native lab floor required");
                // This exact layout has direction 1: foot(6,-1), head(5,-1). No sleepAt or pose writes.
                require(world.setBlock(6, 65, -1, MaidLifeContent.maidBed, 1, 2), "Finite bed foot placement failed");
                require(world.setBlock(5, 65, -1, MaidLifeContent.maidBed, 9, 2), "Finite bed head placement failed");
            }
            var existingHead = world.getChunkFromChunkCoords(0, -1)
                .getTileEntityUnsafe(5, 65, 15);
            require(existingHead instanceof TileEntityMaidBed, "Actual existing native maid-bed tile required");
            bed = (TileEntityMaidBed) existingHead;
            require(bed.complete(), "Complete original finite bed required");
            bedRaw = new NBTTagCompound();
            bed.writeToNBT(bedRaw);
            var resources = new NBTTagCompound();
            resources.setTag("Soup", ProtectedItemCodec.encode(soup));
            resources.setTag("Sword", ProtectedItemCodec.encode(sword));
            var foot = new NBTTagCompound();
            foot.setInteger("X", 6);
            foot.setInteger("Y", 65);
            foot.setInteger("Z", -1);
            foot.setInteger("Block", Block.getIdFromBlock(MaidLifeContent.maidBed));
            foot.setInteger("Metadata", 1);
            var head = (NBTTagCompound) foot.copy();
            head.setInteger("X", 5);
            head.setInteger("Metadata", 9);
            head.setTag("Tile", bedRaw.copy());
            resources.setTag("BedFoot", foot);
            resources.setTag("BedHead", head);
            if (retained) {
                var existing = record.getCompoundTag("Resources");
                for (String key : new String[] { "Soup", "Sword", "BedFoot", "BedHead" }) require(
                    resources.getTag(key)
                        .equals(existing.getTag(key)),
                    "Unknown retained full resource payload: " + key);
                expectedResources = (NBTTagCompound) existing.copy();
            } else {
                record.setTag("Resources", resources);
                expectedResources = (NBTTagCompound) resources.copy();
                session.maid.setPickup(false);
                session.maid.setHealth(session.maid.getMaxHealth());
            }
        }

        ItemStack marked(Item item) {
            var stack = new ItemStack(item);
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setString(KEY, session.data.getString("Run"));
            return stack;
        }

        ModernFollowCoreActor actor() {
            var core = session.maid.getBrainCoordinator()
                .peekEnhancedCore();
            require(
                core != null && runtime == session.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    && runtime.isReady()
                    && runtime.getProfile() == MaidBrainProfile.ENHANCED
                    && runtime.legacyDispatchCount() == 0,
                "Original normal Enhanced root required; no development profile fallback");
            return core.actor();
        }

        String activity() {
            return record.getString("Activity");
        }

        boolean ownsTarget(Entity entity) {
            return target != null && entity == target
                && entity.worldObj == session.maid.worldObj
                && entity.getEntityData()
                    .getString(KEY)
                    .equals(session.data.getString("Run"))
                && MaidCareInteractionFixture.owns(session)
                && session.validNonce(session.maid);
        }

        void verify() {
            var currentRecord = state(session).getCompoundTag("Long");
            if (currentRecord != record) {
                // Native login installs the saved player-tag copy. It must be the complete exact prior plan.
                require(record.equals(currentRecord), "Unknown long-plan login successor retained");
                record = currentRecord;
            }
            require(
                MaidCareInteractionFixture.owns(session) && session.validNonce(session.maid)
                    && expectedResources.equals(record.getCompoundTag("Resources")),
                "Unknown finite resource plan retained");
            actor();
            var world = session.maid.worldObj;
            var current = new NBTTagCompound();
            bed.writeToNBT(current);
            int headMeta = world.getBlockMetadata(5, 65, -1);
            require(
                world.getBlock(6, 65, -1) == MaidLifeContent.maidBed && world.getBlockMetadata(6, 65, -1) == 1
                    && world.getTileEntity(6, 65, -1) == null
                    && world.getBlock(5, 65, -1) == MaidLifeContent.maidBed
                    && world.getTileEntity(5, 65, -1) == bed
                    && (headMeta == 9 || headMeta == 13 && bed.isOwnedBy(session.maid))
                    && current.equals(bedRaw),
                "Unknown complete bed successor retained");
            if (target != null) require(
                ownsTarget(target) && !target.isDead
                    && target.isEntityAlive()
                    && world.getEntityByID(target.getEntityId()) == target
                    && world.loadedEntityList.contains(target)
                    && targetData.equals(target.getEntityData())
                    && target.getCustomNameTag()
                        .isEmpty()
                    && target.getMaxHealth() == 200
                    && target.getHealth() > 0
                    && target.getHealth() <= 200
                    && target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getBaseValue() == 0
                    && target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .getBaseValue() == 1,
                "Unknown owned target successor retained");
            int soupCopies = 0, bowls = 0;
            for (int slot = 0; slot < session.maid.getMaidInventory()
                .getSizeInventory(); slot++) {
                var actual = session.maid.getMaidInventory()
                    .getStackInSlot(slot);
                if (actual == null) continue;
                if (activity().equals("meal") && (slot == 0 || slot == 6)
                    && ItemStack.areItemStacksEqual(actual, soup)) {
                    soupCopies++;
                    continue;
                }
                if (activity().equals("meal") && slot == 0
                    && ItemStack.areItemStacksEqual(actual, new ItemStack(Items.bowl))) {
                    bowls++;
                    continue;
                }
                if (activity().equals("melee") && slot == 6) {
                    var expected = sword.copy();
                    require(
                        actual.getItemDamage() >= 0
                            && actual.getItemDamage() <= (actor().meleeNativeAccepted() - meleeBefore) * 2,
                        "Unexpected weapon wear retained");
                    expected.setItemDamage(actual.getItemDamage());
                    require(ItemStack.areItemStacksEqual(expected, actual), "Unknown full weapon successor retained");
                    continue;
                }
                throw new IllegalStateException("Unknown finite actor inventory successor retained");
            }
            if (activity().equals("meal"))
                require(soupCopies + bowls == 1, "Exactly one original soup or its native bowl must remain");
            if (activity().equals("melee")) require(
                session.maid.getMaidInventory()
                    .getStackInSlot(6) != null,
                "Original finite weapon cannot disappear");
        }

        void observe() {
            verify();
            sleepSeen |= activity().equals("sleep") && session.maid.hasPose(MaidPose.SLEEPING)
                && bed.isOwnedBy(session.maid);
            if (activity().equals("meal") && session.maid.getMealController()
                .isEating()) {
                if (!mealSeen) {
                    var profile = MaidFoodProfile.inspect(soup);
                    firstMealHealth = session.maid.getHealth();
                    require(
                        profile != null && (firstMealHealth == 2 || firstMealHealth
                            == Math.min(session.maid.getMaxHealth(), 2 + Math.max(profile.total / 5, 1))),
                        "Start-heal must match the actual nutrition probability, not guaranteed healing");
                }
                mealSeen = true;
            }
        }

        void action(String action) {
            verify();
            var maid = session.maid;
            switch (action) {
                case "sleep" -> {
                    require(
                        activity().equals("idle") && !maid.getMealController()
                            .isEating(),
                        "Complete prior activity first");
                    maid.setLocationAndAngles(4.5, 65, -1.5, 180, 0);
                    maid.setTask(MaidTask.IDLE);
                    maid.setSchedule(MaidSchedule.NIGHT);
                    sleepSeen = false;
                    record.setString("Activity", "sleep");
                }
                case "wake" -> {
                    require(activity().equals("sleep") && sleepSeen, "Actual natural sleep and owned lease required");
                    maid.setSchedule(MaidSchedule.DAY);
                    record.setString("Activity", "wake");
                }
                case "meal" -> {
                    require(
                        activity().equals("idle") && !maid.hasPose(MaidPose.SLEEPING),
                        "Complete prior activity first");
                    maid.setTask(MaidTask.IDLE);
                    maid.setSchedule(MaidSchedule.DAY);
                    maid.setHealth(2);
                    record.setString("Activity", "meal");
                    mealSeen = false;
                    firstMealHealth = Float.NaN;
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, soup.copy());
                }
                case "melee" -> {
                    require(
                        activity().equals("idle") && target == null && !maid.hasPose(MaidPose.SLEEPING),
                        "Original empty target scope required");
                    meleeBefore = actor().meleeNativeAccepted();
                    maid.setTask(MaidTask.IDLE);
                    maid.setSchedule(MaidSchedule.DAY);
                    maid.setLocationAndAngles(4.5, 65, .5, 180, 0);
                    maid.setHealth(maid.getMaxHealth());
                    record.setString("Activity", "melee");
                    maid.getMaidInventory()
                        .setInventorySlotContents(6, sword.copy());
                    target = new EntityCow(maid.worldObj);
                    target.getEntityData()
                        .setString(KEY, session.data.getString("Run"));
                    target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                        .setBaseValue(200);
                    target.setHealth(200);
                    target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .setBaseValue(0);
                    target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .setBaseValue(1);
                    target.setLocationAndAngles(6.5, 65, .5, 180, 0);
                    require(
                        session.player.getDistanceSqToEntity(target) < 64,
                        "Normal visible threat must remain within the owner's native eight-block range");
                    require(
                        maid.worldObj.spawnEntityInWorld(target)
                            && maid.worldObj.getEntityByID(target.getEntityId()) == target,
                        "Actual exact target registration required");
                    targetData = (NBTTagCompound) target.getEntityData()
                        .copy();
                    record.getCompoundTag("Resources")
                        .setTag("TargetNative", snapshot(target));
                    expectedResources = (NBTTagCompound) record.getCompoundTag("Resources")
                        .copy();
                    maid.setTask(MaidTask.ATTACK);
                    require(
                        maid.attackEntityFrom(DamageSource.causeMobDamage(target), 1),
                        "Actual source threat registration required; no direct combat task start");
                }
                case "complete" -> {
                    if (activity().equals("wake")) {
                        require(
                            sleepSeen && !maid.hasPose(MaidPose.SLEEPING)
                                && !bed.isOwnedBy(maid)
                                && maid.getSleepingBedDirection() == -1,
                            "Normal wake must release the original bed lease");
                        record.setInteger("SleepCount", record.getInteger("SleepCount") + 1);
                    } else if (activity().equals("meal")) {
                        require(
                            mealSeen && !maid.getMealController()
                                .isEating()
                                && ItemStack.areItemStacksEqual(
                                    maid.getMaidInventory()
                                        .getStackInSlot(0),
                                    new ItemStack(Items.bowl)),
                            "Actual soup consumption and the single native bowl required");
                        maid.getMaidInventory()
                            .setInventorySlotContents(0, null);
                        record.setInteger("MealCount", record.getInteger("MealCount") + 1);
                        maid.setHealth(maid.getMaxHealth());
                    } else if (activity().equals("melee")) {
                        require(
                            target != null && target.getHealth() < 200 && actor().meleeNativeAccepted() > meleeBefore,
                            "Actual native melee hit and finite weapon use required");
                        maid.setTask(MaidTask.IDLE);
                        maid.worldObj.removePlayerEntityDangerously(target);
                        target = null;
                        targetData = null;
                        maid.getMaidInventory()
                            .setInventorySlotContents(6, null);
                        record.setInteger("MeleeCount", record.getInteger("MeleeCount") + 1);
                    } else throw new IllegalStateException("No complete activity to acknowledge");
                    record.setString("Activity", "idle");
                }
                default -> throw new IllegalArgumentException("Unknown finite long-session input");
            }
        }

        JsonObject observation() {
            var out = new JsonObject();
            out.addProperty("activity", activity());
            out.addProperty("sleepObserved", sleepSeen);
            out.addProperty("sleeping", session.maid.hasPose(MaidPose.SLEEPING));
            out.addProperty("bedLeaseOwned", bed.isOwnedBy(session.maid));
            out.addProperty("mealObserved", mealSeen);
            out.addProperty(
                "eating",
                session.maid.getMealController()
                    .isEating());
            out.addProperty("firstMealHealth", Float.isNaN(firstMealHealth) ? -1 : firstMealHealth);
            out.addProperty("health", session.maid.getHealth());
            out.addProperty("nativeMeleeAccepted", actor().meleeNativeAccepted());
            out.addProperty("nativeMeleeAcceptedSinceInput", actor().meleeNativeAccepted() - meleeBefore);
            out.addProperty("targetHealth", target == null ? -1 : target.getHealth());
            out.addProperty(
                "finiteResourcesNative",
                record.getCompoundTag("Resources")
                    .toString());
            return out;
        }

        void clear() {
            verify(); // Verify the complete plan and known materials before any cleanup mutates state.
            session.maid.setTask(MaidTask.IDLE);
            session.maid.setSchedule(MaidSchedule.DAY);
            session.maid.getBrainCoordinator()
                .setSuspended(true);
            verify();
            require(
                !session.maid.hasPose(MaidPose.SLEEPING) && !bed.isOwnedBy(session.maid)
                    && session.maid.getItemUseState()
                        .peek() == null,
                "Normal cancellation must release item and bed leases");
            if (target != null) {
                require(ownsTarget(target) && targetData.equals(target.getEntityData()), "Unknown target retained");
                target.worldObj.removePlayerEntityDangerously(target);
                target = null;
            }
            for (int slot = 0; slot < session.maid.getMaidInventory()
                .getSizeInventory(); slot++)
                if (session.maid.getMaidInventory()
                    .getStackInSlot(slot) != null)
                    session.maid.getMaidInventory()
                        .setInventorySlotContents(slot, null);
            // Exact empty fixture bed, originally air. Suppress no material: no item was paid for these cells.
            var world = session.maid.worldObj;
            world.removeTileEntity(5, 65, -1);
            require(
                world.getBlock(5, 65, -1) == MaidLifeContent.maidBed && world.getBlockMetadata(5, 65, -1) == 9
                    && world.getChunkFromChunkCoords(0, -1)
                        .getTileEntityUnsafe(5, 65, 15) == null,
                "Unknown bed-head cleanup successor retained");
            require(
                world.setBlock(5, 65, -1, Blocks.air, 0, 2) && world.isAirBlock(5, 65, -1)
                    && world.getTileEntity(5, 65, -1) == null,
                "Exact finite bed-head cleanup failed");
            require(
                world.getBlock(6, 65, -1) == MaidLifeContent.maidBed && world.getBlockMetadata(6, 65, -1) == 1
                    && world.getTileEntity(6, 65, -1) == null,
                "Unknown bed-foot cleanup successor retained");
            require(
                world.setBlock(6, 65, -1, Blocks.air, 0, 2) && world.isAirBlock(6, 65, -1)
                    && world.getTileEntity(6, 65, -1) == null,
                "Exact finite bed-foot cleanup failed");
        }
    }

    private static void closeLong(Session session) {
        if (longOwner != session || longProbe == null) return;
        longProbe.close();
        lastLongLoad = longProbe.snapshot();
        longProbe = null;
        longOwner = null;
    }
}
