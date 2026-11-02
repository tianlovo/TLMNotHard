package com.tianluoqaq.tlmnh.common.development.interaction;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 真实联网主人用原生命名牌开窗；快照持久保管，未知后继物品或名称不覆盖 */
public final class MaidNamingInteractionFixture {

    private static final String TAG = "TLMNHNamingInteractionFixture";
    public static final String VISIBLE_NAME = "命名试验 女仆", HIDDEN_NAME = "命名试验 隐藏";
    public static final String EASTER_EGG_NAME = "moesumika";
    private static Session active;

    private MaidNamingInteractionFixture() {}

    public static boolean isActivePlayer(EntityPlayerMP player) {
        return active != null && active.player == player
            && player.worldObj == active.maid.worldObj
            && player.getEntityData()
                .getTag(TAG) == active.data;
    }

    public static void run(EntityPlayerMP player, String action) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && online(player) && AnimationLab.isLab(player.worldObj),
            "Real connected development lab player required");
        require(
            java.util.Arrays
                .asList(
                    "prepare",
                    "open",
                    "verify-cancel",
                    "verify-save",
                    "verify-hidden",
                    "verify-easter-egg",
                    "revoke",
                    "verify-revoked",
                    "status",
                    "restore")
                .contains(action),
            "Unknown naming fixture action");
        if (action.equals("prepare")) {
            require(
                active == null && !player.getEntityData()
                    .hasKey(TAG),
                "Restore previous naming fixture first");
            var maid = AnimationLab.ownedSceneMaid(player);
            require(
                player.openContainer == player.inventoryContainer && maid.isNoDespawnRequired()
                    && maid.getBrainCoordinator()
                        .isSuspended()
                    && maid.getNavigator()
                        .noPath(),
                "Closed menu and persistent suspended lab maid required");
            int slot = player.inventory.currentItem;
            require(slot >= 0 && slot < 9, "Select a main-hand hotbar slot first");
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
            data.setFloat("OriginalHealth", player.getHealth());
            data.setInteger("OriginalSlot", slot);
            data.setInteger("ExpectedSlot", slot);
            data.setTag("OriginalInventory", inventory(player, -1).copy());
            var hand = player.inventory.getStackInSlot(slot);
            data.setBoolean("OriginalHandPresent", hand != null);
            if (hand != null) data.setTag(
                "OriginalHand",
                ProtectedItemCodec.encode(hand)
                    .copy());
            data.setString("OriginalName", maid.getCustomNameTag());
            data.setString("OriginalModel", maid.getModelId());
            data.setBoolean("OriginalAlways", maid.getAlwaysRenderNameTag());
            data.setString("ExpectedName", maid.getCustomNameTag());
            data.setBoolean("ExpectedAlways", maid.getAlwaysRenderNameTag());
            data.setInteger("ExpectedCount", 4);
            player.getEntityData()
                .setTag(TAG, data);
            var session = new Session(player, maid, data);
            active = session;
            FMLCommonHandler.instance()
                .bus()
                .register(session);
            try {
                player.setGameType(GameType.SURVIVAL);
                session.protect();
                player.inventory.setInventorySlotContents(slot, fixtureTag(data, 4));
                sync(player);
                session.record(action);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    session.close();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            return;
        }
        var session = active;
        if (session == null) session = recovered(player);
        require(session.player == player, "Another player owns the naming fixture");
        if (action.equals("restore")) {
            session.close();
            return;
        }
        session.validate();
        if (action.equals("status")) {
            session.record(action);
            return;
        }
        require(active == session, "Restarted fixture must be restored before reuse");
        require(
            player.inventory.currentItem == session.data.getInteger("ExpectedSlot"),
            "Selected slot changed externally");
        if (action.equals("verify-save") || action.equals("verify-hidden") || action.equals("verify-easter-egg")) {
            boolean visible = action.equals("verify-save");
            String expected = action.equals("verify-easter-egg") ? EASTER_EGG_NAME
                : visible ? VISIBLE_NAME : HIDDEN_NAME;
            require(
                player.openContainer == player.inventoryContainer && session.maid.getCustomNameTag()
                    .equals(expected)
                    && session.maid.getAlwaysRenderNameTag() == visible
                    && session.count() == session.data.getInteger("ExpectedCount") - 1,
                "Native naming acknowledgement or exactly-one-tag consumption differs");
            session.data.setString("ExpectedName", expected);
            session.data.setBoolean("ExpectedAlways", visible);
            session.data.setInteger("ExpectedCount", session.count());
            session.record(action);
            return;
        }
        session.requireUnchangedName();
        if (action.equals("open")) {
            require(player.openContainer == player.inventoryContainer, "Close the current window first");
            session.select(session.slot());
            require(
                session.count() == session.data.getInteger("ExpectedCount") && session.count() > 0,
                "Owned unconsumed fixture tag required");
            require(
                session.maid.interact(player) && player.openContainer instanceof ContainerMaidNaming naming
                    && naming.getMaid() == session.maid
                    && naming.canInteractWith(player),
                "Native entity interaction did not open the actual authorized naming container");
        } else if (action.equals("revoke")) {
            require(
                player.openContainer instanceof ContainerMaidNaming naming && naming.getMaid() == session.maid,
                "Open the actual naming window before revoking the selected hand");
            var naming = (ContainerMaidNaming) player.openContainer;
            session.select((session.slot() + 1) % 9);
            require(!naming.canInteractWith(player), "Changed selected hand must revoke the naming session");
        } else {
            require(player.openContainer == player.inventoryContainer, "Wait for the native naming window to close");
            require(
                session.count() == session.data.getInteger("ExpectedCount"),
                "Cancelled or revoked naming consumed a tag");
            if (action.equals("verify-revoked")) session.select(session.slot());
        }
        session.record(action);
    }

    private static Session recovered(EntityPlayerMP player) {
        require(
            player.getEntityData()
                .hasKey(TAG, 10),
            "Naming fixture snapshot required");
        var data = player.getEntityData()
            .getCompoundTag(TAG);
        for (Object entity : player.worldObj.loadedEntityList)
            if (entity instanceof EntityMaid maid && maid.getUniqueID()
                .toString()
                .equals(data.getString("Actor"))) return new Session(player, maid, data);
        throw new IllegalStateException("Original naming actor is not loaded; snapshot retained");
    }

    private static NBTTagList inventory(EntityPlayerMP player, int except) {
        var all = new NBTTagList();
        player.inventory.writeToNBT(all);
        if (except < 0) return all;
        return withoutSlot(all, except);
    }

    private static NBTTagList withoutSlot(NBTTagList all, int except) {
        var result = new NBTTagList();
        for (int i = 0; i < all.tagCount(); i++) {
            var item = all.getCompoundTagAt(i);
            if ((item.getByte("Slot") & 255) != except) result.appendTag(item.copy());
        }
        return result;
    }

    private static ItemStack fixtureTag(NBTTagCompound data, int count) {
        var stack = new ItemStack(Items.name_tag, count);
        var tag = new NBTTagCompound();
        tag.setString(TAG, data.getString("Run"));
        stack.setTagCompound(tag);
        return stack;
    }

    private static boolean online(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(player);
    }

    private static void sync(EntityPlayerMP player) {
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        player.sendPlayerAbilities();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static final class Session {

        final EntityPlayerMP player;
        final EntityMaid maid;
        final NBTTagCompound data;
        int ticks;

        Session(EntityPlayerMP player, EntityMaid maid, NBTTagCompound data) {
            this.player = player;
            this.maid = maid;
            this.data = data;
        }

        int slot() {
            return data.getInteger("OriginalSlot");
        }

        void validate() {
            require(
                !data.hasKey("OriginalModel") || data.hasKey("OriginalModel", 8) && data.getString("OriginalModel")
                    .equals(maid.getModelId()),
                "Naming changed the stored model; original fixture retained");
            require(
                player.getEntityData()
                    .getTag(TAG) == data && data.hasKey("Schema", 3)
                    && data.getInteger("Schema") == 1
                    && data.getInteger("OriginalMode") == 3
                    && data.hasKey("OriginalInventory", 9)
                    && data.hasKey("OriginalHandPresent", 1)
                    && data.hasKey("OriginalName", 8)
                    && data.hasKey("OriginalAlways", 1)
                    && data.hasKey("OriginalSlot", 3)
                    && data.hasKey("ExpectedSlot", 3)
                    && data.hasKey("OriginalHealth", 5)
                    && data.hasKey("Run", 8)
                    && UUID.fromString(data.getString("Run"))
                        .toString()
                        .equals(data.getString("Run"))
                    && data.getString("Owner")
                        .equals(
                            player.getUniqueID()
                                .toString())
                    && data.getString("Actor")
                        .equals(
                            maid.getUniqueID()
                                .toString())
                    && maid.isOwner(player)
                    && player.worldObj == maid.worldObj
                    && AnimationLab.isLab(player.worldObj)
                    && player.worldObj.getEntityByID(maid.getEntityId()) == maid
                    && slot() >= 0
                    && slot() < 9,
                "Unknown or foreign naming fixture snapshot retained");
        }

        void protect() {
            require(
                player.theItemInWorldManager.getGameType()
                    .getID() == 0,
                "Fixture survival mode changed externally");
            boolean changed = !player.capabilities.disableDamage || !player.capabilities.allowFlying
                || !player.capabilities.isFlying;
            player.capabilities.disableDamage = true;
            player.capabilities.allowFlying = true;
            player.capabilities.isFlying = true;
            player.noClip = true;
            player.fallDistance = 0;
            if (changed) player.sendPlayerAbilities();
        }

        void select(int selected) {
            player.inventory.currentItem = selected;
            data.setInteger("ExpectedSlot", selected);
            player.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(selected));
        }

        int count() {
            var stack = player.inventory.getStackInSlot(slot());
            if (stack == null) return 0;
            int count = stack.stackSize;
            require(
                count > 0 && count <= 4 && ItemStack.areItemStacksEqual(stack, fixtureTag(data, count)),
                "Foreign hand item retained with the original backup");
            return count;
        }

        void requireUnchangedName() {
            require(
                maid.getCustomNameTag()
                    .equals(data.getString("ExpectedName"))
                    && maid.getAlwaysRenderNameTag() == data.getBoolean("ExpectedAlways"),
                "Naming actor changed outside the verified fixture sequence");
        }

        void close() {
            try {
                validate();
                require(
                    player.theItemInWorldManager.getGameType()
                        .getID() == 0
                        || player.theItemInWorldManager.getGameType()
                            .getID() == 3,
                    "Foreign game mode retained");
                require(
                    player.getHealth() == data.getFloat("OriginalHealth"),
                    "Observer health changed; snapshot retained");
                require(
                    player.inventory.getItemStack() == null,
                    "Put down the cursor item; original snapshot retained");
                require(
                    player.inventory.currentItem == data.getInteger("ExpectedSlot"),
                    "Selected slot changed externally");
                require(
                    inventory(player, slot()).equals(withoutSlot(data.getTagList("OriginalInventory", 10), slot())),
                    "Other inventory data changed; existing inventory and original snapshot retained");
                String name = maid.getCustomNameTag();
                require(
                    name.equals(data.getString("OriginalName"))
                        && maid.getAlwaysRenderNameTag() == data.getBoolean("OriginalAlways")
                        || name.equals(VISIBLE_NAME) && maid.getAlwaysRenderNameTag()
                        || (name.equals(HIDDEN_NAME) || name.equals(EASTER_EGG_NAME)) && !maid.getAlwaysRenderNameTag(),
                    "Foreign maid name retained");
                var original = data.getBoolean("OriginalHandPresent")
                    ? ProtectedItemCodec.decodeField(data, "OriginalHand")
                    : null;
                require(!data.getBoolean("OriginalHandPresent") || original != null, "Original hand cannot be decoded");
                var current = player.inventory.getStackInSlot(slot());
                if (!ItemStack.areItemStacksEqual(current, original)) count();
                if (player.openContainer instanceof ContainerMaidNaming naming && naming.getMaid() == maid)
                    player.closeScreen();
                player.inventory.setInventorySlotContents(slot(), original);
                select(slot());
                maid.setCustomNameTag(data.getString("OriginalName"));
                maid.setAlwaysRenderNameTag(data.getBoolean("OriginalAlways"));
                require(
                    inventory(player, -1).equals(data.getTagList("OriginalInventory", 10)),
                    "Complete original inventory restore differs");
                player.setGameType(GameType.getByID(data.getInteger("OriginalMode")));
                sync(player);
                player.getEntityData()
                    .removeTag(TAG);
                record("restored");
            } finally {
                try {
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
                } finally {
                    FMLCommonHandler.instance()
                        .bus()
                        .unregister(this);
                    if (active == this) active = null;
                }
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.PlayerTickEvent event) {
            if (event.player != player || event.phase != TickEvent.Phase.END || active != this) return;
            try {
                validate();
                require(
                    ++ticks <= 12000 && online(player) && player.isEntityAlive(),
                    "Naming fixture timeout or disconnect");
                protect();
            } catch (RuntimeException | LinkageError failure) {
                abort(failure);
            }
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player == player && active == this) abort(null);
        }

        void abort(Throwable cause) {
            try {
                close();
            } catch (RuntimeException | LinkageError failure) {
                if (cause != null) failure.addSuppressed(cause);
                TLMNotHard.LOG.error("TLMNH_NAMING_FIXTURE_ABORTED original snapshot retained", failure);
            }
        }

        void record(String action) {
            var result = new JsonObject();
            result.addProperty("action", action);
            result.addProperty("run", data.getString("Run"));
            result.addProperty(
                "maid",
                maid.getUniqueID()
                    .toString());
            result.addProperty("name", maid.getCustomNameTag());
            result.addProperty("storedModel", maid.getModelId());
            result.addProperty("alwaysShow", maid.getAlwaysRenderNameTag());
            result.addProperty("selected", player.inventory.currentItem);
            result.addProperty(
                "mode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            result.addProperty("health", player.getHealth());
            result.addProperty(
                "originalInventoryRestored",
                inventory(player, -1).equals(data.getTagList("OriginalInventory", 10)));
            result.addProperty(
                "originalNameRestored",
                maid.getCustomNameTag()
                    .equals(data.getString("OriginalName"))
                    && maid.getAlwaysRenderNameTag() == data.getBoolean("OriginalAlways"));
            result.addProperty(
                "window",
                player.openContainer.getClass()
                    .getSimpleName());
            result.addProperty(
                "fixtureSnapshot",
                player.getEntityData()
                    .hasKey(TAG));
            result.addProperty("expectedCount", data.getInteger("ExpectedCount"));
            result.addProperty(
                "handCount",
                player.inventory.getStackInSlot(slot()) == null ? 0
                    : player.inventory.getStackInSlot(slot()).stackSize);
            TLMNotHard.LOG.info("TLMNH_NAMING_FIXTURE {}", result);
        }
    }
}
