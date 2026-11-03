package com.tianluoqaq.tlmnh.common.development.interaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
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
            "OriginalMetadata"));
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

    private MaidModelLifecycleActions() {}

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static boolean handles(String action) {
        return action.startsWith("world-life-");
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(data.hasKey(KEY, 10), "Unknown model lifecycle snapshot retained");
        require(
            data.getString("Phase")
                .equals("model-lifecycle"),
            "Unknown lifecycle session phase retained");
        var state = data.getCompoundTag(KEY);
        for (Object key : state.func_150296_c())
            require(FIELDS.contains(key), "Unknown model lifecycle field retained");
        require(
            state.hasKey("Schema", 3) && state.getInteger("Schema") == 1
                && state.hasKey("Stage", 8)
                && STAGES.contains(state.getString("Stage")),
            "Invalid lifecycle stage retained");
        for (String key : new String[] { "Source", "Model" })
            require(state.hasKey(key, 8), "Invalid lifecycle identity");
        UUID.fromString(state.getString("Source"));
        for (String key : new String[] { "Token", "Grave", "Candidate" }) if (state.hasKey(key)) {
            require(state.hasKey(key, 8), "Invalid lifecycle UUID type");
            UUID.fromString(state.getString(key));
        }
        for (String key : new String[] { "SourceNative", "CandidateNative", "EmptyTile", "FilledTile",
            "RevivedNative" }) if (state.hasKey(key)) require(state.hasKey(key, 10), "Invalid lifecycle raw snapshot");
        require(
            state.hasKey("OriginalMetadata", 3) && state.getInteger("OriginalMetadata") == 0,
            "Unknown original air metadata retained");
        if (state.hasKey("LedgerSchema")) require(state.hasKey("LedgerSchema", 3), "Unknown ledger schema retained");
        if (state.hasKey("FilmInventory")) require(
            state.hasKey("FilmInventory", 9)
                && ((NBTTagList) state.getTag("FilmInventory")).tagCount() == state.getTagList("FilmInventory", 10)
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
        var entries = all.getTagList("Entries", 10);
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
            actual.equals(state.getCompoundTag("EmptyTile"))
                || state.hasKey("FilledTile", 10) && actual.equals(state.getCompoundTag("FilledTile")),
            "Unknown shrine tile successor retained");
        return tile;
    }

    static boolean transitional(Session session) {
        if (!session.data.hasKey(KEY, 10)) return false;
        var stage = state(session).getString("Stage");
        return !stage.equals("ready") && !stage.equals("revived") && !stage.equals("restart");
    }

    static boolean allowedHealth(Session session) {
        if (!session.data.hasKey(KEY, 10)) return false;
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
            || !session.data.hasKey(KEY, 10)
            || entity.worldObj != session.player.worldObj) return false;
        var state = state(session);
        var ledger = MaidLifecycleData.get(entity.worldObj);
        var token = ledger.deathToken(UUID.fromString(state.getString("Source")));
        if (token == null || !session.player.getUniqueID()
            .equals(ledger.owner(token, MaidLifecycleData.Kind.FILM))) return false;
        if (entity instanceof MaidTombstoneEntity grave) {
            if (!token.equals(grave.getToken()) || !session.player.getUniqueID()
                .equals(grave.getOwnerId())) return false;
            if (state.hasKey("Grave", 8) && !state.getString("Grave")
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
        return session.data.hasKey(KEY, 10) && state(session).getString("Stage")
            .equals("restart");
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
        session.maid.getBrainCoordinator()
            .setSuspended(true);
        session.player.setGameType(GameType.SURVIVAL);
        session.protect();
    }

    static void restore(Session session) {
        if (!session.data.hasKey(KEY)) return;
        var stage = state(session).getString("Stage");
        require(
            stage.equals("ready") || stage.equals("revived"),
            "Finish the original native lifecycle before cleanup; snapshot retained");
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
        return result;
    }
}
