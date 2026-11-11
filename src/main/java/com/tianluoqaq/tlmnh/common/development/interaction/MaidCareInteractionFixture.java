package com.tianluoqaq.tlmnh.common.development.interaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real client care inputs use a fresh empty actor; the existing scene and its equipment remain untouched. */
public final class MaidCareInteractionFixture {

    static final String TAG = "TLMNHCareInteractionFixture";
    private static final String REIMU = "touhou_little_maid:hakurei_reimu", HANFU = "geckolib:winefox_hanfu";
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Schema",
            "Run",
            "Owner",
            "Actor",
            "OriginalMode",
            "Slot",
            "OriginalHealth",
            "OriginalInventory",
            "ExpectedInventory",
            "HandPresent",
            "OriginalHand",
            "OriginalX",
            "OriginalY",
            "OriginalZ",
            "OriginalYaw",
            "OriginalPitch",
            "ExpectedX",
            "ExpectedY",
            "ExpectedZ",
            "ExpectedOwner",
            "ExpectedModel",
            "Phase",
            "PotionEffects",
            "MilkRemaining",
            "DenyHealth",
            "ResultInventory",
            "CheckpointCounter",
            "CheckpointTime",
            "AwaitLogin",
            "Rejoined",
            "WorldActions",
            "ModelLifecycle",
            "NaturalShield",
            "WorldPresentation",
            "PlayerInputs"));
    private static Session active;

    private MaidCareInteractionFixture() {}

    static boolean owns(Session session) {
        return active == session;
    }

    public static boolean isActivePlayer(EntityPlayerMP player) {
        if (MaidPlayerInputActions.isActivePlayer(active, player)) return true;
        if (active == null) return recoveryObserver(player);
        return player != null && currentLabWorld(player.worldObj)
            && (active.player == player
                && (active.pendingActor && active.pendingActorWorld == player.worldObj
                    || player.worldObj == active.maid.worldObj && active.validNonce(active.maid))
                && player.getEntityData()
                    .getTag(TAG) == active.data
                || active.rejectedPlayer == player && player.getUniqueID()
                    .toString()
                    .equals(active.data.getString("Owner"))
                    && player.theItemInWorldManager.getGameType()
                        .getID() == 3);
    }

    public static boolean isSpawning(Entity entity) {
        if (currentLabWorld(entity.worldObj)
            && (MaidModelLifecycleActions.isSpawning(entity) || MaidNaturalShieldActions.isSpawning(entity)
                || MaidPlayerInputActions.isSpawning(entity)
                || MaidWorldPresentationActions.isSpawning(entity)))
            return true;
        if (!(entity instanceof EntityMaid maid) || !currentLabWorld(maid.worldObj)) return false;
        if (active == null) {
            if (!recoveryIdentity(
                maid.func_152113_b(),
                maid.getUniqueID()
                    .toString(),
                maid.getEntityData()
                    .getString(TAG)))
                return false;
            for (Object value : maid.worldObj.loadedEntityList) if (value instanceof EntityMaid other && other != maid
                && other.getUniqueID()
                    .equals(maid.getUniqueID()))
                return false;
            return true;
        }
        if (!active.validNonce(maid)) return false;
        if (maid != active.maid && (!active.data.getBoolean("AwaitLogin") && active.rejectedPlayer == null
            || currentLabWorld(active.maid.worldObj) && loaded(active.maid))) return false;
        // A normal dimension unload replaces WorldServer too; only the currently registered lab may own the nonce.
        for (Object value : maid.worldObj.loadedEntityList) if (value instanceof EntityMaid other && other != maid
            && other.getUniqueID()
                .equals(maid.getUniqueID()))
            return false;
        return true;
    }

    private static boolean currentLabWorld(World world) {
        return DevelopmentFlags.isDevCommandsEnabled() && world instanceof WorldServer serverWorld
            && !world.isRemote
            && AnimationLab.isLab(world)
            && world.provider.dimensionId == AnimationLab.DIMENSION
            && DimensionManager.getWorld(AnimationLab.DIMENSION) == serverWorld
            && serverWorld.func_73046_m() == MinecraftServer.getServer();
    }

    private static boolean recoveryIdentity(String owner, String actor, String run) {
        if (!DevelopmentFlags.isDevCommandsEnabled() || owner == null || actor == null || run == null) return false;
        try {
            return UUID.fromString(owner)
                .toString()
                .equals(owner)
                && UUID.fromString(actor)
                    .toString()
                    .equals(actor)
                && UUID.fromString(run)
                    .toString()
                    .equals(run)
                && owner.equals(System.getProperty("tlmnh.careRestoreOwner"))
                && actor.equals(System.getProperty("tlmnh.careRestoreActor"))
                && run.equals(System.getProperty("tlmnh.careRestoreRun"));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean recoveryObserver(EntityPlayerMP player) {
        if (!online(player) || !currentLabWorld(player.worldObj)
            || player.theItemInWorldManager.getGameType()
                .getID() != 3
            || !player.getEntityData()
                .hasKey(TAG, Constants.NBT.TAG_COMPOUND))
            return false;
        var saved = player.getEntityData()
            .getCompoundTag(TAG);
        return saved.hasKey("Schema", Constants.NBT.TAG_INT) && saved.getInteger("Schema") == 1
            && saved.hasKey("Owner", Constants.NBT.TAG_STRING)
            && saved.hasKey("Actor", Constants.NBT.TAG_STRING)
            && saved.hasKey("Run", Constants.NBT.TAG_STRING)
            && player.getUniqueID()
                .toString()
                .equals(saved.getString("Owner"))
            && recoveryIdentity(saved.getString("Owner"), saved.getString("Actor"), saved.getString("Run"));
    }

    public static void run(EntityPlayerMP player, String action) {
        require(
            DevelopmentFlags.isDevCommandsEnabled() && online(player) && currentLabWorld(player.worldObj),
            "Real connected development lab player required");
        if (action.equals("prepare")) {
            require(
                active == null && !player.getEntityData()
                    .hasKey(TAG) && !MaidNamingInteractionFixture.isActivePlayer(player),
                "Restore the previous interaction fixture first");
            AnimationLab.verifyObserver(player);
            var anchor = AnimationLab.ownedSceneMaid(player);
            require(
                anchor.isOwner(player) && player.openContainer == player.inventoryContainer
                    && player.inventory.getItemStack() == null,
                "Closed original owner scene required");
            var maid = new EntityMaid(player.worldObj);
            var data = new NBTTagCompound();
            data.setInteger("Schema", 1);
            data.setString(
                "Run",
                UUID.randomUUID()
                    .toString());
            data.setString(
                "Owner",
                player.getUniqueID()
                    .toString());
            data.setString(
                "Actor",
                maid.getUniqueID()
                    .toString());
            data.setInteger(
                "OriginalMode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            data.setInteger("Slot", player.inventory.currentItem);
            data.setFloat("OriginalHealth", player.getHealth());
            data.setTag("OriginalInventory", inventory(player));
            data.setTag("ExpectedInventory", inventory(player));
            // Et Futurum intentionally hides getCurrentItem while spectating; snapshot the actual slot.
            var hand = player.inventory.getStackInSlot(player.inventory.currentItem);
            data.setBoolean("HandPresent", hand != null);
            if (hand != null) data.setTag("OriginalHand", ProtectedItemCodec.encode(hand));
            for (String axis : new String[] { "X", "Y", "Z" }) data.setDouble(
                "Original" + axis,
                axis.equals("X") ? player.posX : axis.equals("Y") ? player.posY : player.posZ);
            data.setFloat("OriginalYaw", player.rotationYaw);
            data.setFloat("OriginalPitch", player.rotationPitch);
            data.setDouble("ExpectedX", player.posX);
            data.setDouble("ExpectedY", player.posY);
            data.setDouble("ExpectedZ", player.posZ);
            data.setString(
                "ExpectedOwner",
                player.getUniqueID()
                    .toString());
            data.setString("ExpectedModel", REIMU);
            data.setString("Phase", "prepared");
            require(
                data.getInteger("OriginalMode") == 3 && data.getInteger("Slot") >= 0 && data.getInteger("Slot") < 9,
                "Protected spectator and native main hand required");
            maid.getEntityData()
                .setString(TAG, data.getString("Run"));
            require(maid.bindTo(player.getUniqueID()), "Fresh care actor owner required");
            maid.setModelId(REIMU);
            maid.setHunger(20);
            maid.setPickupItems(false);
            maid.setPickupExperience(false);
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.func_110163_bv();
            maid.setFavorability(73);
            maid.getFavorabilityManager()
                .apply(Type.WORK_MEAL);
            maid.getFavorabilityManager()
                .refreshAttributes();
            maid.setHealth(10);
            maid.setLocationAndAngles(4.5, 65, .5, 180, 0);
            maid.rotationYawHead = maid.prevRotationYawHead = maid.renderYawOffset = maid.prevRenderYawOffset = 180;
            require(
                player.worldObj.getBlock(4, 64, 0)
                    .getMaterial()
                    .isSolid()
                    && player.worldObj.getCollidingBoundingBoxes(maid, maid.boundingBox)
                        .isEmpty()
                    && player.worldObj.checkNoEntityCollision(maid.boundingBox),
                "Existing empty lab floor required");
            var session = new Session(player, maid, data);
            active = session;
            player.getEntityData()
                .setTag(TAG, data);
            FMLCommonHandler.instance()
                .bus()
                .register(session);
            try {
                require(player.worldObj.spawnEntityInWorld(maid), "Native care actor spawn rejected");
                player.setGameType(GameType.SURVIVAL);
                session.protect();
                session.move(false);
                session.hand(null, null);
                session.record("prepare");
            } catch (RuntimeException | LinkageError failure) {
                if (!loaded(maid) && player.getEntityData()
                    .getTag(TAG) == data
                    && player.worldObj == maid.worldObj
                    && inventory(player).equals(data.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND))
                    && player.getDistanceSq(
                        data.getDouble("ExpectedX"),
                        data.getDouble("ExpectedY"),
                        data.getDouble("ExpectedZ")) < .01
                    && (player.theItemInWorldManager.getGameType()
                        .getID() == 0
                        || player.theItemInWorldManager.getGameType()
                            .getID() == 3)) {
                    player.playerNetServerHandler.setPlayerLocation(
                        data.getDouble("OriginalX"),
                        data.getDouble("OriginalY"),
                        data.getDouble("OriginalZ"),
                        data.getFloat("OriginalYaw"),
                        data.getFloat("OriginalPitch"));
                    player.setGameType(GameType.getByID(3));
                    player.capabilities.disableDamage = true;
                    player.capabilities.allowFlying = true;
                    player.capabilities.isFlying = true;
                    player.noClip = true;
                    player.sendPlayerAbilities();
                    player.getEntityData()
                        .removeTag(TAG);
                    FMLCommonHandler.instance()
                        .bus()
                        .unregister(session);
                    if (active == session) active = null;
                } else session.abort(failure);
                throw failure;
            }
            return;
        }
        var session = active;
        if (session == null
            && (action.equals("restore") || action.equals("world-life-resume")
                || action.equals("world-life-recover-long-finish"))
            && player.getEntityData()
                .hasKey(TAG, Constants.NBT.TAG_COMPOUND)) {
            var saved = player.getEntityData()
                .getCompoundTag(TAG);
            EntityMaid found = null;
            for (Object entity : player.worldObj.loadedEntityList) if (entity instanceof EntityMaid candidate
                && candidate.getUniqueID()
                    .toString()
                    .equals(saved.getString("Actor"))
                && candidate.getEntityData()
                    .getString(TAG)
                    .equals(saved.getString("Run"))) {
                        require(found == null, "Ambiguous retained care actor");
                        found = candidate;
                    }
            require(
                found != null && !saved.getString("Run")
                    .isEmpty(),
                "Load the original retained care actor; no replacement is spawned");
            session = new Session(player, found, saved);
            active = session;
            if (action.equals("world-life-resume")) {
                session.validateSnapshot();
                require(
                    recoveryObserver(player),
                    "Exact startup recovery identity and protected original player required");
                session.validate();
                session.inventoryOwned();
                MaidModelLifecycleActions.resumed(session);
                FMLCommonHandler.instance()
                    .bus()
                    .register(session);
                session.record(action);
                return;
            }
        }
        require(session != null, "No live care fixture; restore its retained snapshot first");
        if (action.equals("world-life-recover-long-finish")) {
            require(
                session.player == player && recoveryObserver(player),
                "Exact startup recovery tuple and original protected connected player required");
            session.validateSnapshot();
            try {
                MaidModelLifecycleActions.recoverCompletedLong(session);
                session.validate();
                session.inventoryOwned();
                FMLCommonHandler.instance()
                    .bus()
                    .register(session);
                session.record(action);
            } catch (RuntimeException | LinkageError failure) {
                protectObserver(player);
                throw failure;
            }
            return;
        }
        if (session.player != player) session.attach(player);
        if (action.equals("restore")) {
            require(!session.pendingActor, "Original care actor is still loading; session retained");
            var loaded = session.loadedActor(player.worldObj);
            require(loaded != null, "Loaded original care actor required for cleanup; snapshot retained");
            session.maid = loaded;
            MaidWorldPresentationActions.recover(session);
            session.close();
            return;
        }
        require(session.rejectedPlayer == null, "Restore the rejected care session before continuing");
        if (session.pendingActor) {
            require(action.equals("status"), "Wait for the original native care actor to finish loading");
            session.record(action);
            return;
        }
        session.validate();
        session.inventoryOwned();
        if (action.equals("status")) {
            session.record(action);
            return;
        }
        if (MaidModelLifecycleActions.handles(action)) {
            MaidModelLifecycleActions.run(session, action);
            session.record(action);
            return;
        }
        if (MaidNaturalShieldActions.handles(action)) {
            MaidNaturalShieldActions.run(session, action);
            session.record(action);
            return;
        }
        if (MaidPlayerInputActions.handles(action)) {
            MaidPlayerInputActions.run(session, action);
            session.record(action);
            return;
        }
        if (MaidWorldPresentationActions.handles(action)) {
            MaidWorldPresentationActions.run(session, action);
            session.record(action);
            return;
        }
        if (action.startsWith("world-")) {
            MaidNaturalWorldActions.run(session, action);
            session.record(action);
            return;
        }
        switch (action) {
            case "arm-apple" -> {
                session.requirePhase("prepared");
                require(
                    session.maid.getActivePotionEffects()
                        .isEmpty(),
                    "Fresh actor effects required");
                session.arm("apple", new ItemStack(Items.golden_apple), null);
            }
            case "verify-apple" -> {
                session.requirePhase("apple-armed");
                session.resultInventory();
                session.effect(Potion.regeneration.id, 1, 100);
                session.effect(Potion.field_76444_x.id, 0, 2400);
                require(session.maid.getHealth() > 10, "Actual natural golden-apple regeneration required");
                session.finish("apple");
            }
            case "arm-potion" -> {
                session.requirePhase("apple-verified");
                var potion = new ItemStack(Items.potionitem, 1, 8194);
                var effects = ((ItemPotion) Items.potionitem).getEffects(potion);
                require(
                    !ItemPotion.isSplash(potion.getItemDamage()) && effects != null && !effects.isEmpty(),
                    "Native drinkable potion required");
                var list = new NBTTagList();
                for (Object value : effects)
                    list.appendTag(((PotionEffect) value).writeCustomPotionEffectToNBT(new NBTTagCompound()));
                session.data.setTag("PotionEffects", list);
                session.arm("potion", potion, new ItemStack(Items.glass_bottle));
            }
            case "verify-potion" -> {
                session.requirePhase("potion-armed");
                session.resultInventory();
                var effects = session.data.getTagList("PotionEffects", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < effects.tagCount(); i++) {
                    var effect = PotionEffect.readCustomPotionEffectFromNBT(effects.getCompoundTagAt(i));
                    require(
                        effect != null && !Potion.potionTypes[effect.getPotionID()].isInstant(),
                        "Reviewed sustained native potion required");
                    session.effect(effect.getPotionID(), effect.getAmplifier(), effect.getDuration());
                }
                session.finish("potion");
            }
            case "arm-milk" -> {
                session.requirePhase("potion-verified");
                var remaining = new NBTTagList();
                var milk = new ItemStack(Items.milk_bucket);
                for (Object value : session.maid.getActivePotionEffects()) {
                    var effect = (PotionEffect) value;
                    if (!effect.isCurativeItem(milk))
                        remaining.appendTag(effect.writeCustomPotionEffectToNBT(new NBTTagCompound()));
                }
                session.data.setTag("MilkRemaining", remaining);
                session.arm("milk", milk, new ItemStack(Items.bucket));
            }
            case "verify-milk" -> {
                session.requirePhase("milk-armed");
                session.resultInventory();
                var retained = session.data.getTagList("MilkRemaining", Constants.NBT.TAG_COMPOUND);
                require(
                    session.maid.getActivePotionEffects()
                        .size() == retained.tagCount(),
                    "Native milk cure result differs");
                for (int i = 0; i < retained.tagCount(); i++) {
                    var effect = PotionEffect.readCustomPotionEffectFromNBT(retained.getCompoundTagAt(i));
                    require(effect != null, "Native retained effect missing");
                    session.effect(effect.getPotionID(), effect.getAmplifier(), effect.getDuration());
                }
                session.finish("milk");
            }
            case "arm-distance", "arm-owner" -> {
                require(
                    session.data.getString("Phase")
                        .endsWith("verified"),
                    "Verify the previous native action first");
                require(
                    session.maid.getActivePotionEffects()
                        .isEmpty(),
                    "Clean post-milk actor required");
                String kind = action.substring(4);
                session.data.setFloat("DenyHealth", session.maid.getHealth());
                session.arm(kind, new ItemStack(Items.golden_apple), null);
                session.data.setTag(
                    "ResultInventory",
                    session.data.getTag("ExpectedInventory")
                        .copy());
                if (kind.equals("distance")) session.move(true);
                else {
                    String owner = UUID.randomUUID()
                        .toString();
                    session.data.setString("ExpectedOwner", owner);
                    session.maid.func_152115_b(owner);
                }
            }
            case "verify-distance", "verify-owner" -> {
                String kind = action.substring(7);
                session.requirePhase(kind + "-armed");
                session.resultInventory();
                require(
                    session.maid.getActivePotionEffects()
                        .isEmpty() && session.maid.getHealth() == session.data.getFloat("DenyHealth"),
                    "Rejected care changed real effects or health");
                if (kind.equals("distance")) session.move(false);
                else {
                    require(
                        session.maid.func_152113_b()
                            .equals(session.data.getString("ExpectedOwner")),
                        "Foreign owner retained");
                    session.maid.func_152115_b(session.data.getString("Owner"));
                    session.data.setString("ExpectedOwner", session.data.getString("Owner"));
                }
                session.finish(kind);
            }
            case "arm-model" -> {
                require(
                    session.data.getString("Phase")
                        .endsWith("verified"),
                    "Verify care first");
                session.hand(null, null);
                session.checkpoint();
                session.data.setString("Phase", "model-armed");
            }
            case "verify-model" -> {
                session.requirePhase("model-armed");
                require(
                    session.maid.getModelId()
                        .equals(HANFU),
                    "Actual native GUI model selection required");
                session.counterPreserved();
                session.data.setString("ExpectedModel", HANFU);
                session.data.setString("Phase", "model-verified");
            }
            case "arm-relogin" -> {
                session.requirePhase("model-verified");
                require(player.openContainer == player.inventoryContainer, "Close native GUI first");
                session.checkpoint();
                session.data.setBoolean("AwaitLogin", true);
                session.data.setBoolean("Rejoined", false);
                session.data.setString("Phase", "relogin-armed");
            }
            case "verify-relogin" -> {
                session.requirePhase("relogin-armed");
                require(session.data.getBoolean("Rejoined"), "A distinct native login required");
                session.counterPreserved();
                session.data.setBoolean("AwaitLogin", false);
                session.data.setString("Phase", "relogin-verified");
            }
            default -> throw new IllegalArgumentException("Unknown care fixture action");
        }
        session.record(action);
    }

    private static NBTTagList inventory(EntityPlayerMP player) {
        return (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
            .copy();
    }

    private static boolean loaded(Entity entity) {
        for (Object actual : entity.worldObj.loadedEntityList) if (actual == entity) return true;
        return false;
    }

    private static boolean online(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && registered(player);
    }

    private static boolean registered(EntityPlayerMP player) {
        if (player == null || MinecraftServer.getServer() == null) return false;
        for (Object actual : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) if (actual == player) return true;
        return false;
    }

    private static void protectObserver(EntityPlayerMP player) {
        if (!registered(player) || !AnimationLab.isLab(player.worldObj)) return;
        if (player.theItemInWorldManager.getGameType()
            .getID() == 0) player.setGameType(GameType.getByID(3));
        if (player.theItemInWorldManager.getGameType()
            .getID() == 3) {
            player.capabilities.disableDamage = true;
            player.capabilities.allowFlying = true;
            player.capabilities.isFlying = true;
            player.noClip = true;
            player.sendPlayerAbilities();
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static final class Session {

        EntityPlayerMP player;
        EntityMaid maid;
        NBTTagCompound data;
        int ticks;
        EntityPlayerMP rejectedPlayer;
        boolean pendingActor;
        World pendingActorWorld;
        long pendingActorDeadline;

        Session(EntityPlayerMP player, EntityMaid maid, NBTTagCompound data) {
            this.player = player;
            this.maid = maid;
            this.data = data;
        }

        int slot() {
            return data.getInteger("Slot");
        }

        boolean validNonce(EntityMaid value) {
            if (value == null || !currentLabWorld(value.worldObj)) return false;
            try {
                for (String key : new String[] { "Owner", "Actor", "Run", "ExpectedOwner" })
                    if (!data.hasKey(key, Constants.NBT.TAG_STRING) || !UUID.fromString(data.getString(key))
                        .toString()
                        .equals(data.getString(key))) return false;
                return player.getUniqueID()
                    .toString()
                    .equals(data.getString("Owner"))
                    && value.getUniqueID()
                        .toString()
                        .equals(data.getString("Actor"))
                    && value.getEntityData()
                        .hasKey(TAG, Constants.NBT.TAG_STRING)
                    && data.getString("Run")
                        .equals(
                            value.getEntityData()
                                .getString(TAG))
                    && value.func_152113_b()
                        .equals(data.getString("ExpectedOwner"));
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }

        void validateSnapshot() {
            for (Object key : data.func_150296_c())
                require(FIELDS.contains(key), "Unknown care snapshot field retained");
            for (String key : new String[] { "Schema", "OriginalMode", "Slot" })
                require(data.hasKey(key, Constants.NBT.TAG_INT), "Invalid care integer");
            for (String key : new String[] { "Run", "Owner", "Actor", "ExpectedOwner", "ExpectedModel", "Phase" })
                require(data.hasKey(key, Constants.NBT.TAG_STRING), "Invalid care identity");
            for (String key : new String[] { "Run", "Owner", "Actor", "ExpectedOwner" }) require(
                UUID.fromString(data.getString(key))
                    .toString()
                    .equals(data.getString(key)),
                "Invalid care UUID");
            for (String key : new String[] { "OriginalX", "OriginalY", "OriginalZ", "ExpectedX", "ExpectedY",
                "ExpectedZ" })
                require(
                    data.hasKey(key, Constants.NBT.TAG_DOUBLE) && Double.isFinite(data.getDouble(key)),
                    "Invalid care position");
            require(
                data.hasKey("OriginalHealth", Constants.NBT.TAG_FLOAT)
                    && data.hasKey("OriginalYaw", Constants.NBT.TAG_FLOAT)
                    && data.hasKey("OriginalPitch", Constants.NBT.TAG_FLOAT)
                    && data.hasKey("HandPresent", Constants.NBT.TAG_BYTE)
                    && data.getByte("HandPresent") >= 0
                    && data.getByte("HandPresent") <= 1
                    && (!data.getBoolean("HandPresent") || data.hasKey("OriginalHand", Constants.NBT.TAG_COMPOUND)),
                "Invalid original player snapshot");
            for (String key : new String[] { "OriginalInventory", "ExpectedInventory" }) require(
                data.hasKey(key, Constants.NBT.TAG_LIST)
                    && ((NBTTagList) data.getTag(key)).tagCount() == data.getTagList(key, Constants.NBT.TAG_COMPOUND)
                        .tagCount(),
                "Invalid care inventory snapshot");
            for (String key : new String[] { "AwaitLogin", "Rejoined" }) require(
                !data.hasKey(key)
                    || data.hasKey(key, Constants.NBT.TAG_BYTE) && data.getByte(key) >= 0 && data.getByte(key) <= 1,
                "Invalid care flag retained");
            for (String key : new String[] { "ResultInventory", "PotionEffects", "MilkRemaining" }) require(
                !data.hasKey(key) || data.hasKey(key, Constants.NBT.TAG_LIST)
                    && ((NBTTagList) data.getTag(key)).tagCount() == data.getTagList(key, Constants.NBT.TAG_COMPOUND)
                        .tagCount(),
                "Invalid care result snapshot");
            MaidNaturalWorldActions.validateSnapshot(data);
            MaidModelLifecycleActions.validateSnapshot(data);
            MaidNaturalShieldActions.validateSnapshot(data);
            MaidPlayerInputActions.validateSnapshot(data);
            MaidWorldPresentationActions.validateSnapshot(data);
        }

        void validatePlayer() {
            require(
                active == this && player.getEntityData()
                    .getTag(TAG) == data
                    && data.getInteger("Schema") == 1
                    && data.getString("Owner")
                        .equals(
                            player.getUniqueID()
                                .toString())
                    && data.getInteger("OriginalMode") == 3
                    && currentLabWorld(player.worldObj)
                    && slot() >= 0
                    && slot() < 9
                    && MaidPlayerInputActions.playerShape(this)
                    && (player.getHealth() == data.getFloat("OriginalHealth")
                        || MaidModelLifecycleActions.allowedHealth(this)),
                "Care fixture identity changed; retained");
        }

        void validate() {
            validateSnapshot();
            validatePlayer();
            if (MaidPlayerInputActions.validateActor(this)) return;
            if (MaidModelLifecycleActions.validateActor(this)) return;
            require(
                player.worldObj == maid.worldObj && validNonce(maid)
                    && player.worldObj.getEntityByID(maid.getEntityId()) == maid
                    && maid.isEntityAlive(),
                "Original current-world care actor required; retained");
            require(
                maid.func_152113_b()
                    .equals(data.getString("ExpectedOwner"))
                    && (maid.getModelId()
                        .equals(data.getString("ExpectedModel"))
                        || data.getString("Phase")
                            .equals("model-armed")
                            && maid.getModelId()
                                .equals(HANFU)),
                "Foreign actor owner or model retained");
            require(
                maid.getCustomNameTag()
                    .isEmpty(),
                "Foreign actor name retained");
            emptyActor();
        }

        void emptyActor() {
            if (MaidModelLifecycleActions
                .verifyLongInventory(this)) { /* finite current-session materials verified */ } else
                if (data.hasKey("WorldPresentation")) MaidWorldPresentationActions.verifyInventory(this);
                else if (data.hasKey("PlayerInputs")) MaidPlayerInputActions.verifyInventory(this);
                else if (data.hasKey("NaturalShield")) MaidNaturalShieldActions.verifyInventory(this);
                else if (data.hasKey("WorldActions")) MaidNaturalWorldActions.verifyInventory(this);
                else for (int i = 0; i < maid.getMaidInventory()
                    .getSizeInventory(); i++)
                    require(
                        maid.getMaidInventory()
                            .getStackInSlot(i) == null,
                        "Actor acquired foreign equipment; retained");
            require(
                maid.getMaidInventory()
                    .recovery()
                    .write()
                    .tagCount() == 0,
                "Actor recovery contents retained");
        }

        void inventoryOwned() {
            if (MaidPlayerInputActions.inventoryOwned(this)) return;
            var actual = inventory(player);
            require(
                actual.equals(data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND))
                    || data.hasKey("ResultInventory", Constants.NBT.TAG_LIST)
                        && actual.equals(data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)),
                "Unknown player inventory successor retained");
        }

        void hand(ItemStack input, ItemStack result) {
            if (data.hasKey("ExpectedInventory", Constants.NBT.TAG_LIST)) inventoryOwned();
            if (input != null) {
                if (!input.hasTagCompound()) input.setTagCompound(new NBTTagCompound());
                input.getTagCompound()
                    .setString(TAG, data.getString("Run"));
            }
            player.inventory.setInventorySlotContents(slot(), input);
            data.setTag("ExpectedInventory", inventory(player));
            var after = new NBTTagList();
            var before = data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND);
            boolean found = false;
            for (int i = 0; i < before.tagCount(); i++) {
                var entry = before.getCompoundTagAt(i);
                if (entry.getByte("Slot") == slot()) {
                    found = true;
                    if (result != null) {
                        var tag = result.copy()
                            .writeToNBT(new NBTTagCompound());
                        tag.setByte("Slot", (byte) slot());
                        after.appendTag(tag);
                    }
                } else after.appendTag(entry.copy());
            }
            if (!found && result != null) {
                var tag = result.copy()
                    .writeToNBT(new NBTTagCompound());
                tag.setByte("Slot", (byte) slot());
                after.appendTag(tag);
            }
            data.setTag("ResultInventory", after);
            sync();
        }

        void arm(String kind, ItemStack input, ItemStack result) {
            hand(input, result);
            data.setString("Phase", kind + "-armed");
        }

        void resultInventory() {
            require(
                inventory(player).equals(data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)),
                "Actual native material/container transaction differs");
        }

        void finish(String kind) {
            require(maid.getFavorability() == 74, "Care unexpectedly changed seeded favorability");
            data.setTag("ExpectedInventory", inventory(player));
            data.removeTag("ResultInventory");
            data.setString("Phase", kind + "-verified");
        }

        void requirePhase(String expected) {
            require(
                data.getString("Phase")
                    .equals(expected),
                "Unexpected care phase");
        }

        void effect(int id, int amplifier, int maximum) {
            var actual = maid.getActivePotionEffect(Potion.potionTypes[id]);
            require(
                actual != null && actual.getAmplifier() == amplifier
                    && actual.getDuration() > 0
                    && actual.getDuration() <= maximum,
                "Native care effect differs: " + id);
        }

        int cooldown() {
            var tag = new NBTTagCompound();
            maid.getFavorabilityManager()
                .addAdditionalSaveData(tag);
            return tag.getCompoundTag("FavorabilityManagerCounter")
                .getInteger(Type.WORK_MEAL.getTypeName());
        }

        void checkpoint() {
            require(maid.getFavorability() == 74 && cooldown() > 0, "Nonzero live favorability cooldown required");
            data.setInteger("CheckpointCounter", cooldown());
            data.setLong("CheckpointTime", maid.worldObj.getTotalWorldTime());
        }

        void counterPreserved() {
            long elapsed = maid.worldObj.getTotalWorldTime() - data.getLong("CheckpointTime");
            int before = data.getInteger("CheckpointCounter"), now = cooldown();
            require(
                maid.getFavorability() == 74 && elapsed >= 0 && now > 0 && now <= before && before - now <= elapsed + 2,
                "Native model/relogin favorability or elapsed cooldown differs");
        }

        void move(boolean far) {
            double x = far ? 30.5 : 4.5;
            player.playerNetServerHandler.setPlayerLocation(x, 65, -3.5, 0, 10);
            data.setDouble("ExpectedX", x);
            data.setDouble("ExpectedY", 65);
            data.setDouble("ExpectedZ", -3.5);
        }

        void protect() {
            require(
                player.theItemInWorldManager.getGameType()
                    .getID() == 0,
                "Care survival mode changed externally");
            boolean changed = !player.capabilities.disableDamage || !player.capabilities.allowFlying
                || !player.capabilities.isFlying;
            player.capabilities.disableDamage = true;
            player.capabilities.allowFlying = true;
            player.capabilities.isFlying = true;
            player.noClip = true;
            player.fallDistance = 0;
            if (changed) player.sendPlayerAbilities();
        }

        void sync() {
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            player.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(slot()));
            player.sendPlayerAbilities();
        }

        void attach(EntityPlayerMP next) {
            validateSnapshot();
            require(
                data.getBoolean("AwaitLogin") && !data.getBoolean("Rejoined")
                    && (data.getString("Phase")
                        .equals("relogin-armed") || MaidModelLifecycleActions.allowsRelogin(this))
                    && next != player
                    && online(next)
                    && next.getUniqueID()
                        .toString()
                        .equals(data.getString("Owner"))
                    && currentLabWorld(next.worldObj)
                    && next.dimension == AnimationLab.DIMENSION
                    && next.getEntityData()
                        .hasKey(TAG, Constants.NBT.TAG_COMPOUND)
                    && next.getEntityData()
                        .getCompoundTag(TAG)
                        .equals(data)
                    && data.getInteger("Schema") == 1
                    && data.getInteger("OriginalMode") == 3
                    && slot() >= 0
                    && slot() < 9
                    && next.inventory.currentItem == slot()
                    && next.inventory.getItemStack() == null
                    && next.isEntityAlive()
                    && next.getHealth() == data.getFloat("OriginalHealth")
                    && next.theItemInWorldManager.getGameType()
                        .getID() == 0
                    && next.getDistanceSq(
                        data.getDouble("ExpectedX"),
                        data.getDouble("ExpectedY"),
                        data.getDouble("ExpectedZ")) < .01
                    && inventory(next).equals(data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND)),
                "Unknown relogin snapshot retained phase=" + data.getString("Phase")
                    + " mode="
                    + next.theItemInWorldManager.getGameType()
                        .getID()
                    + " actualPosition="
                    + next.posX
                    + ","
                    + next.posY
                    + ","
                    + next.posZ
                    + " expectedPosition="
                    + data.getDouble("ExpectedX")
                    + ","
                    + data.getDouble("ExpectedY")
                    + ","
                    + data.getDouble("ExpectedZ")
                    + " snapshotMatches="
                    + next.getEntityData()
                        .getCompoundTag(TAG)
                        .equals(data));
            var loaded = loadedActor(next.worldObj);
            player = next;
            data = next.getEntityData()
                .getCompoundTag(TAG);
            pendingActor = true;
            pendingActorWorld = next.worldObj;
            pendingActorDeadline = next.worldObj.getTotalWorldTime() + 200;
            rejectedPlayer = null;
            // Native LoggedIn precedes asynchronous chunk entities; protect this exact player before PlayerTick.
            protect();
            if (loaded != null) finishAttach(loaded);
            else record("relogin-waiting");
        }

        EntityMaid loadedActor(World world) {
            EntityMaid loaded = null;
            for (Object entity : world.loadedEntityList)
                if (entity instanceof EntityMaid candidate && (candidate.getUniqueID()
                    .toString()
                    .equals(data.getString("Actor"))
                    || candidate.getEntityData()
                        .hasKey(TAG, Constants.NBT.TAG_STRING)
                        && candidate.getEntityData()
                            .getString(TAG)
                            .equals(data.getString("Run")))) {
                                require(validNonce(candidate), "Unknown original care actor successor retained");
                                require(loaded == null, "Ambiguous care actor");
                                loaded = candidate;
                            }
            return loaded;
        }

        void finishAttach(EntityMaid loaded) {
            maid = loaded;
            validate();
            inventoryOwned();
            protect();
            if (MaidModelLifecycleActions.allowsRelogin(this)) data.setBoolean("AwaitLogin", false);
            data.setBoolean("Rejoined", true);
            pendingActor = false;
            pendingActorWorld = null;
            pendingActorDeadline = 0;
            rejectedPlayer = null;
            record("rejoined");
        }

        void waitForActor() {
            validateSnapshot();
            validatePlayer();
            require(
                data.getBoolean("AwaitLogin") && !data.getBoolean("Rejoined")
                    && (data.getString("Phase")
                        .equals("relogin-armed") || MaidModelLifecycleActions.allowsRelogin(this))
                    && player.worldObj == pendingActorWorld
                    && player.theItemInWorldManager.getGameType()
                        .getID() == 0
                    && inventory(player).equals(data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND)),
                "Unknown waiting player successor retained");
            require(
                player.worldObj.getTotalWorldTime() <= pendingActorDeadline,
                "Original native care actor did not load before the bounded login deadline; retained");
            var loaded = loadedActor(player.worldObj);
            if (loaded != null) finishAttach(loaded);
            else protect();
        }

        ItemStack originalHand() {
            var original = data.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND);
            NBTTagCompound entry = null;
            var withoutHand = new NBTTagList();
            for (int i = 0; i < original.tagCount(); i++) {
                var value = original.getCompoundTagAt(i);
                require(value.hasKey("Slot", Constants.NBT.TAG_BYTE), "Unknown original inventory slot retained");
                if ((value.getByte("Slot") & 255) == slot()) {
                    require(entry == null, "Ambiguous original hand retained");
                    entry = (NBTTagCompound) value.copy();
                    entry.removeTag("Slot");
                } else withoutHand.appendTag(value.copy());
            }
            ItemStack hand;
            if (data.getBoolean("HandPresent")) {
                hand = ProtectedItemCodec.decodeField(data, "OriginalHand");
            } else if (entry == null) {
                require(!data.hasKey("OriginalHand"), "Unknown original empty hand retained");
                return null;
            } else {
                // Early schema-1 spectators reported a null equipped item even when this native slot was occupied.
                // Only the untouched prepared-state signature may recover that exact saved slot.
                require(
                    !data.hasKey("OriginalHand") && data.getString("Phase")
                        .equals("prepared")
                        && withoutHand.equals(data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND))
                        && data.hasKey("ResultInventory", Constants.NBT.TAG_LIST)
                        && withoutHand.equals(data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)),
                    "Inconsistent original hand snapshot retained");
                hand = ItemStack.loadItemStackFromNBT((NBTTagCompound) entry.copy());
            }
            require(
                hand != null && hand.stackSize > 0
                    && hand.stackSize <= Math.min(64, hand.getMaxStackSize())
                    && entry != null
                    && hand.copy()
                        .writeToNBT(new NBTTagCompound())
                        .equals(entry),
                "Original native hand cannot be restored exactly; backup retained");
            return hand;
        }

        boolean authorizedRecoveryCamera() {
            return recoveryIdentity(data.getString("Owner"), data.getString("Actor"), data.getString("Run"))
                && player.theItemInWorldManager.getGameType()
                    .getID() == 3
                && data.getDouble("OriginalX") == .5
                && data.getDouble("OriginalY") == 65
                && data.getDouble("OriginalZ") == -4.5
                && player.getDistanceSq(
                    data.getDouble("OriginalX"),
                    data.getDouble("OriginalY"),
                    data.getDouble("OriginalZ")) < .01;
        }

        void close() {
            // PlayerLoggedOut fires before native removal/save, so that same registered object is still safe.
            // A later offline object must never clear the actor or the real next player's retained snapshot.
            require(registered(player), "Offline care session retained for its actual connected player");
            require(!pendingActor, "Original care actor is still loading; session and snapshot retained");
            try {
                validate();
                inventoryOwned();
                require(
                    player.getDistanceSq(
                        data.getDouble("ExpectedX"),
                        data.getDouble("ExpectedY"),
                        data.getDouble("ExpectedZ")) < .01 || authorizedRecoveryCamera(),
                    "Foreign camera move retained with original snapshot");
                MaidPlayerInputActions.beforeClose(this);
                if (player.openContainer != player.inventoryContainer) {
                    boolean own = player.openContainer instanceof ContainerMaidModels models && models.getMaid() == maid
                        || player.openContainer instanceof AbstractMaidContainer main && main.getMaid() == maid;
                    require(own, "Foreign container retained");
                    player.closeScreen();
                    validate();
                    inventoryOwned();
                }
                MaidWorldPresentationActions.restore(this);
                MaidNaturalShieldActions.restore(this);
                MaidModelLifecycleActions.restore(this);
                MaidNaturalWorldActions.restore(this);
                var hand = originalHand();
                var original = data.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND);
                var beforeHand = player.inventory.getStackInSlot(slot());
                var handSnapshot = hand == null ? new NBTTagCompound()
                    : hand.copy()
                        .writeToNBT(new NBTTagCompound());
                player.inventory.setInventorySlotContents(slot(), hand);
                if (!inventory(player).equals(original)) {
                    // Never restore a whole inventory over a callback successor.
                    var current = player.inventory.getStackInSlot(slot());
                    require(
                        current == hand && (current == null ? handSnapshot.hasNoTags()
                            : current.copy()
                                .writeToNBT(new NBTTagCompound())
                                .equals(handSnapshot)),
                        "Restore callback changed the hand; retained");
                    player.inventory.setInventorySlotContents(slot(), beforeHand);
                    throw new IllegalStateException(
                        "Original complete inventory could not be restored; backup retained");
                }
                emptyActor();
                require(
                    validNonce(maid) && player.worldObj.getEntityByID(maid.getEntityId()) == maid,
                    "Actor successor retained");
                maid.worldObj.removePlayerEntityDangerously(maid);
                require(!loaded(maid), "Owned actor removal did not complete");
                player.playerNetServerHandler.setPlayerLocation(
                    data.getDouble("OriginalX"),
                    data.getDouble("OriginalY"),
                    data.getDouble("OriginalZ"),
                    data.getFloat("OriginalYaw"),
                    data.getFloat("OriginalPitch"));
                player.setGameType(GameType.getByID(data.getInteger("OriginalMode")));
                sync();
                player.getEntityData()
                    .removeTag(TAG);
                record("restored");
            } finally {
                protectObserver(player);
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(this);
                if (active == this) active = null;
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || active != this) return;
            // Keep offline/rejected sessions and their exact actor recoverable; never time out the old MP.
            if (!online(player) || rejectedPlayer != null) return;
            if (pendingActor) {
                try {
                    waitForActor();
                } catch (RuntimeException | LinkageError failure) {
                    pendingActor = false;
                    rejectedPlayer = player;
                    protectObserver(player);
                    TLMNotHard.LOG
                        .error("TLMNH_CARE_FIXTURE_RELOGIN_REJECTED session and original snapshot retained", failure);
                }
                return;
            }
            try {
                require(++ticks < MaidModelLifecycleActions.sessionTickLimit(this), "Care fixture timed out");
                validate();
                inventoryOwned();
                MaidModelLifecycleActions.tickLong(this);
                if (MaidModelLifecycleActions.restart(this)) protectObserver(player);
                else if (!MaidPlayerInputActions.protect(this)) protect();
            } catch (RuntimeException | LinkageError failure) {
                abort(failure);
            }
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player == player && active == this && !data.getBoolean("AwaitLogin")) abort(null);
        }

        @SubscribeEvent
        public void login(PlayerEvent.PlayerLoggedInEvent event) {
            if (active == this && event.player instanceof EntityPlayerMP next
                && next.getUniqueID()
                    .toString()
                    .equals(data.getString("Owner"))) {
                try {
                    attach(next);
                } catch (RuntimeException | LinkageError failure) {
                    pendingActor = false;
                    rejectedPlayer = next;
                    protectObserver(next);
                    TLMNotHard.LOG
                        .error("TLMNH_CARE_FIXTURE_RELOGIN_REJECTED session and original snapshot retained", failure);
                }
            }
        }

        void abort(Throwable cause) {
            try {
                close();
            } catch (RuntimeException | LinkageError failure) {
                if (cause != null) failure.addSuppressed(cause);
                TLMNotHard.LOG.error("TLMNH_CARE_FIXTURE_ABORTED original snapshot retained", failure);
            }
        }

        void record(String action) {
            var result = new JsonObject();
            result.addProperty("action", action);
            result.addProperty("run", data.getString("Run"));
            result.addProperty("pendingActor", pendingActor);
            result.addProperty("rejoined", data.getBoolean("Rejoined"));
            if (pendingActor) {
                result.addProperty("maid", data.getString("Actor"));
                result.addProperty("phase", data.getString("Phase"));
                result.addProperty(
                    "mode",
                    player.theItemInWorldManager.getGameType()
                        .getID());
                result.addProperty("worldTime", player.worldObj.getTotalWorldTime());
                TLMNotHard.LOG.info("TLMNH_CARE_FIXTURE {}", result);
                return;
            }
            result.addProperty(
                "maid",
                maid.getUniqueID()
                    .toString());
            result.addProperty("entityId", maid.getEntityId());
            result.addProperty("phase", data.getString("Phase"));
            result.addProperty("model", maid.getModelId());
            result.addProperty("owner", maid.func_152113_b());
            result.addProperty("health", maid.getHealth());
            result.addProperty("favorability", maid.getFavorability());
            result.addProperty("workMealCooldown", cooldown());
            result.addProperty("worldTime", maid.worldObj.getTotalWorldTime());
            result.addProperty(
                "expectedInventoryMatches",
                inventory(player).equals(data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND)));
            result.addProperty(
                "resultInventoryMatches",
                data.hasKey("ResultInventory", Constants.NBT.TAG_LIST)
                    && inventory(player).equals(data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)));
            if (data.hasKey("WorldActions")) result.add("worldActions", MaidNaturalWorldActions.observation(this));
            if (data.hasKey("ModelLifecycle"))
                result.add("modelLifecycle", MaidModelLifecycleActions.observation(this));
            if (data.hasKey("NaturalShield")) result.add("naturalShield", MaidNaturalShieldActions.observation(this));
            if (data.hasKey("PlayerInputs")) result.add("playerInputs", MaidPlayerInputActions.observation(this));
            if (data.hasKey("WorldPresentation"))
                result.add("worldPresentation", MaidWorldPresentationActions.observation(this));
            result.addProperty(
                "mode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            result.addProperty(
                "originalInventoryRestored",
                inventory(player).equals(data.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND)));
            var held = player.inventory.getStackInSlot(slot());
            result.addProperty(
                "hand",
                held == null ? ""
                    : held.copy()
                        .writeToNBT(new NBTTagCompound())
                        .toString());
            var effects = new JsonArray();
            for (Object value : maid.getActivePotionEffects()) effects.add(
                new JsonPrimitive(
                    ((PotionEffect) value).writeCustomPotionEffectToNBT(new NBTTagCompound())
                        .toString()));
            result.add("effects", effects);
            TLMNotHard.LOG.info("TLMNH_CARE_FIXTURE {}", result);
        }
    }
}
