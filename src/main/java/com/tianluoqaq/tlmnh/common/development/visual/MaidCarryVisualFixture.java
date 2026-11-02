package com.tianluoqaq.tlmnh.common.development.visual;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 旁观者不改模式；独立注册玩家提供真实挂载类型，原展台女仆的改动单独保存和恢复 */
public final class MaidCarryVisualFixture {

    private static final String TAG = "TLMNHCarryVisualFixture";
    private static final String BEDROCK = "touhou_little_maid:hakurei_reimu", GECKO = "geckolib:winefox_hanfu";
    private static Session active;

    private MaidCarryVisualFixture() {}

    public static void set(EntityPlayerMP observer, String action) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands required");
        require(
            action.equals("bedrock") || action.equals("gecko") || action.equals("clear") || action.equals("status"),
            "Unknown carry visual action");
        var maid = AnimationLab.ownedSceneMaid(observer);
        require(realPlayer(observer), "A real protected observer is required");
        if (action.equals("status")) {
            record("STATUS", maid, active == null ? null : active.carrier);
            return;
        }
        if (action.equals("clear")) {
            if (active != null) {
                require(active.maid == maid && active.observer == observer, "Another observer owns the carry fixture");
                active.close();
            } else if (maid.getEntityData()
                .hasKey(TAG)) restore(maid, null);
            record("CLEARED", maid, null);
            return;
        }
        if (active == null) {
            capture(maid);
            var session = new Session(observer, maid);
            active = session;
            try {
                session.begin();
            } catch (RuntimeException | LinkageError failure) {
                try {
                    session.close();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        require(active.maid == maid && active.observer == observer, "Another observer owns the carry fixture");
        active.stage(action.equals("bedrock") ? BEDROCK : GECKO);
        record("STAGED", maid, active.carrier);
    }

    private static void capture(EntityMaid maid) {
        require(
            !maid.getEntityData()
                .hasKey(TAG) && !MaidVisualFixtureState.exists(maid),
            "Restore previous visual fixture first");
        require(
            maid.getBrainCoordinator()
                .isSuspended()
                && maid.getNavigator()
                    .noPath()
                && !maid.hasPose(MaidPose.SLEEPING)
                && maid.ridingEntity == null
                && maid.riddenByEntity == null,
            "An idle suspended, unmounted lab maid is required");
        var data = new NBTTagCompound();
        data.setInteger("Schema", 1);
        data.setString(
            "Run",
            UUID.randomUUID()
                .toString());
        data.setString(
            "Actor",
            maid.getUniqueID()
                .toString());
        data.setString("Owner", maid.func_152113_b());
        data.setString("Model", maid.getModelId());
        data.setString("ExpectedModel", maid.getModelId());
        data.setBoolean("Sitting", maid.isSitting());
        data.setBoolean(
            "ShowBack",
            maid.getConfigManager()
                .isShowBackItem());
        data.setDouble("X", maid.posX);
        data.setDouble("Y", maid.posY);
        data.setDouble("Z", maid.posZ);
        data.setDouble("MotionX", maid.motionX);
        data.setDouble("MotionY", maid.motionY);
        data.setDouble("MotionZ", maid.motionZ);
        data.setFloat("Yaw", maid.rotationYaw);
        data.setFloat("Pitch", maid.rotationPitch);
        data.setFloat("BodyYaw", maid.renderYawOffset);
        data.setFloat("HeadYaw", maid.rotationYawHead);
        for (int slot = 0; slot < 3; slot++) {
            writeItem(data, "Original" + slot, item(maid, slot));
            writeItem(data, "Expected" + slot, item(maid, slot));
        }
        maid.getEntityData()
            .setTag(TAG, data);
    }

    private static NBTTagCompound saved(EntityMaid maid) {
        require(
            !maid.worldObj.isRemote && AnimationLab.isLab(maid.worldObj)
                && maid.worldObj.getEntityByID(maid.getEntityId()) == maid,
            "Registered server lab maid required");
        var data = maid.getEntityData()
            .getCompoundTag(TAG);
        require(
            data.getInteger("Schema") == 1 && maid.getUniqueID()
                .toString()
                .equals(data.getString("Actor"))
                && maid.func_152113_b()
                    .equals(data.getString("Owner")),
            "Unknown carry fixture snapshot retained");
        return data;
    }

    private static void verifyItems(EntityMaid maid, NBTTagCompound data) {
        for (int slot = 0; slot < 3; slot++) require(
            matches(data, "Expected" + slot, item(maid, slot)) || matches(data, "Original" + slot, item(maid, slot)),
            "Carry fixture equipment changed externally; current item and original snapshot retained");
    }

    private static void restore(EntityMaid maid, EntityPlayerMP carrier) {
        var data = saved(maid);
        verifyItems(maid, data);
        require(
            (maid.ridingEntity == null || maid.ridingEntity == carrier) && maid.riddenByEntity == null
                && (maid.getModelId()
                    .equals(data.getString("ExpectedModel"))
                    || maid.getModelId()
                        .equals(data.getString("Model")))
                && BuiltinModelCatalog.contains(data.getString("Model")),
            "Carry fixture pose/model changed externally; original snapshot retained");
        var originals = new ItemStack[3];
        for (int slot = 0; slot < 3; slot++) {
            originals[slot] = data.getBoolean("Original" + slot + "Present")
                ? ProtectedItemCodec.decodeField(data, "Original" + slot)
                : null;
            require(
                !data.getBoolean("Original" + slot + "Present") || originals[slot] != null,
                "Original item could not be decoded");
        }
        if (maid.ridingEntity == carrier && carrier != null) maid.mountEntity(null);
        maid.setModelId(data.getString("Model"));
        maid.setSitting(data.getBoolean("Sitting"));
        maid.getConfigManager()
            .setShowBackItem(data.getBoolean("ShowBack"));
        for (int slot = 0; slot < 3; slot++) setItem(maid, slot, originals[slot]);
        maid.setLocationAndAngles(
            data.getDouble("X"),
            data.getDouble("Y"),
            data.getDouble("Z"),
            data.getFloat("Yaw"),
            data.getFloat("Pitch"));
        maid.prevRotationYaw = maid.rotationYaw;
        maid.prevRotationPitch = maid.rotationPitch;
        maid.prevRenderYawOffset = maid.renderYawOffset = data.getFloat("BodyYaw");
        maid.prevRotationYawHead = maid.rotationYawHead = data.getFloat("HeadYaw");
        maid.prevPosX = maid.lastTickPosX = maid.posX;
        maid.prevPosY = maid.lastTickPosY = maid.posY;
        maid.prevPosZ = maid.lastTickPosZ = maid.posZ;
        maid.motionX = data.getDouble("MotionX");
        maid.motionY = data.getDouble("MotionY");
        maid.motionZ = data.getDouble("MotionZ");
        for (int slot = 0; slot < 3; slot++)
            require(matches(data, "Original" + slot, item(maid, slot)), "Original item restore incomplete");
        require(
            maid.ridingEntity == null && maid.getModelId()
                .equals(data.getString("Model"))
                && maid.isSitting() == data.getBoolean("Sitting")
                && maid.getConfigManager()
                    .isShowBackItem() == data.getBoolean("ShowBack")
                && maid.posX == data.getDouble("X")
                && maid.posY == data.getDouble("Y")
                && maid.posZ == data.getDouble("Z")
                && maid.rotationYaw == data.getFloat("Yaw")
                && maid.rotationPitch == data.getFloat("Pitch"),
            "Original pose restore incomplete");
        maid.getEntityData()
            .removeTag(TAG);
        record("RESTORED", maid, carrier);
    }

    private static ItemStack item(EntityMaid maid, int slot) {
        return slot == 0 ? maid.getHeldItem()
            : slot == 1 ? maid.getOffhandItem()
                : maid.getMaidInventory()
                    .getStackInSlot(5);
    }

    private static void setItem(EntityMaid maid, int slot, ItemStack stack) {
        if (slot == 0) maid.setCurrentItemOrArmor(0, stack);
        else if (slot == 1) maid.setOffhandItem(stack);
        else maid.getMaidInventory()
            .setInventorySlotContents(5, stack);
    }

    private static void writeItem(NBTTagCompound data, String key, ItemStack stack) {
        data.setBoolean(key + "Present", stack != null);
        if (stack == null) data.removeTag(key);
        else data.setTag(key, ProtectedItemCodec.encode(stack));
    }

    private static boolean matches(NBTTagCompound data, String key, ItemStack stack) {
        return data.hasKey(key + "Present", 1) && data.getBoolean(key + "Present") == (stack != null)
            && (stack == null || data.getCompoundTag(key)
                .equals(ProtectedItemCodec.encode(stack)));
    }

    private static boolean realPlayer(EntityPlayerMP player) {
        return MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.contains(player);
    }

    private static void record(String phase, EntityMaid maid, EntityPlayerMP carrier) {
        var report = new JsonObject();
        report.addProperty("phase", phase);
        report.addProperty(
            "maid",
            maid.getUniqueID()
                .toString());
        report.addProperty("model", maid.getModelId());
        report.addProperty(
            "snapshotRetained",
            maid.getEntityData()
                .hasKey(TAG));
        report.addProperty("mounted", maid.ridingEntity != null);
        report.addProperty(
            "carrier",
            carrier == null ? ""
                : carrier.getUniqueID()
                    .toString());
        report.addProperty(
            "carrierRegistered",
            carrier != null && carrier.worldObj.getEntityByID(carrier.getEntityId()) == carrier);
        report
            .addProperty("carrierInWorldPlayers", carrier != null && carrier.worldObj.playerEntities.contains(carrier));
        report.addProperty(
            "carrierInLoadedEntities",
            carrier != null && carrier.worldObj.loadedEntityList.contains(carrier));
        report.addProperty("carrierRealServerPlayer", carrier != null && realPlayer(carrier));
        if (phase.equals("RESTORED")) report.addProperty("ownedItemNbtRestored", true);
        report.addProperty(
            "scope",
            "controlled registered-player display fixture; not natural input or multiplayer acceptance");
        TLMNotHard.LOG.info("TLMNH_CARRY_VISUAL {}", report);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static final class Session {

        final EntityPlayerMP observer;
        final EntityMaid maid;
        final WorldServer world;
        EntityPlayerMP carrier;
        EmbeddedChannel channel;
        boolean registered;
        int ticks;

        Session(EntityPlayerMP observer, EntityMaid maid) {
            this.observer = observer;
            this.maid = maid;
            world = (WorldServer) maid.worldObj;
        }

        void begin() {
            carrier = new EntityPlayerMP(
                MinecraftServer.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), "TLMNHCarryView"),
                new ItemInWorldManager(world));
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, channel);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, carrier);
            carrier.dimension = world.provider.dimensionId;
            carrier.setGameType(GameType.CREATIVE);
            carrier.capabilities.isFlying = true;
            carrier.setLocationAndAngles(.5, 65, .5, 180, 0);
            carrier.prevRenderYawOffset = carrier.renderYawOffset = carrier.rotationYawHead = carrier.prevRotationYawHead = 180;
            require(
                world.spawnEntityInWorld(carrier) && world.getEntityByID(carrier.getEntityId()) == carrier
                    && !realPlayer(carrier),
                "Isolated carrier registration failed");
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            registered = true;
        }

        void stage(String model) {
            require(BuiltinModelCatalog.contains(model), "Unknown fixture model");
            var data = saved(maid);
            verifyItems(maid, data);
            require(maid.ridingEntity == null || maid.ridingEntity == carrier, "Foreign mount retained");
            maid.setModelId(model);
            data.setString("ExpectedModel", model);
            maid.setSitting(false);
            maid.getConfigManager()
                .setShowBackItem(true);
            for (int slot = 0; slot < 3; slot++) {
                var stack = new ItemStack(slot == 1 ? Items.stick : Items.diamond_sword);
                var tag = new NBTTagCompound();
                tag.setString(TAG, data.getString("Run"));
                stack.setTagCompound(tag);
                writeItem(data, "Expected" + slot, stack);
                setItem(maid, slot, stack);
            }
            maid.mountEntity(carrier);
            require(maid.ridingEntity == carrier && carrier.riddenByEntity == maid, "Real player mount failed");
        }

        @SubscribeEvent
        public void tick(TickEvent.WorldTickEvent event) {
            if (event.world != world || event.phase != TickEvent.Phase.END || active != this) return;
            try {
                drain();
                require(
                    ++ticks <= 12000 && realPlayer(observer)
                        && observer.worldObj == world
                        && observer.isEntityAlive()
                        && observer.theItemInWorldManager.getGameType()
                            .getID() == 3
                        && observer.capabilities.disableDamage
                        && observer.capabilities.allowFlying
                        && observer.capabilities.isFlying,
                    "Protected observer left or carry display timed out");
                require(
                    world.getEntityByID(carrier.getEntityId()) == carrier && !realPlayer(carrier)
                        && carrier.ridingEntity == null
                        && carrier.posX == .5
                        && carrier.posY == 65
                        && carrier.posZ == .5
                        && maid.ridingEntity == carrier
                        && carrier.riddenByEntity == maid,
                    "Carry display participants changed");
                var data = saved(maid);
                verifyItems(maid, data);
                require(
                    maid.getModelId()
                        .equals(data.getString("ExpectedModel"))
                        && maid.getBrainCoordinator()
                            .isSuspended(),
                    "Carry display model or pause state changed externally");
            } catch (RuntimeException | LinkageError failure) {
                try {
                    close();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                TLMNotHard.LOG
                    .error("TLMNH_CARRY_VISUAL_ABORTED original snapshot retained on rejected restore", failure);
            }
        }

        void drain() {
            if (channel == null) return;
            IsolatedPlayerChannel.requireUnbound(channel);
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        }

        void close() {
            try {
                restore(maid, carrier);
            } finally {
                try {
                    if (registered) FMLCommonHandler.instance()
                        .bus()
                        .unregister(this);
                    if (carrier != null) {
                        if (maid.ridingEntity == carrier) maid.mountEntity(null);
                        world.removePlayerEntityDangerously(carrier);
                    }
                } finally {
                    try {
                        if (channel != null) {
                            channel.finish();
                            drain();
                        }
                    } finally {
                        if (active == this) active = null;
                    }
                }
                require(
                    carrier == null
                        || world.getEntityByID(carrier.getEntityId()) == null && !world.playerEntities.contains(carrier)
                            && !world.loadedEntityList.contains(carrier),
                    "Carrier tracking cleanup incomplete");
                require(channel == null || !channel.isOpen(), "Isolated carrier channel remains open");
                record("CLEANED", maid, carrier);
            }
        }
    }
}
