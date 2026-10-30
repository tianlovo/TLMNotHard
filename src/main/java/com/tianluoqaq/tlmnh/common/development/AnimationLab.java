package com.tianluoqaq.tlmnh.common.development;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 独立虚空场地与观察者生命周期，不操作普通世界的地形或夹具 */
public final class AnimationLab {

    public static final int DIMENSION = Integer.getInteger("tlmnh.labDimension", 7337);
    private static final String MAID_MARKER = "TLMNHAnimationLabMaid";
    private static final String RETURN = "TLMNHAnimationLabReturn";
    private static final int FLOOR = 64, RADIUS = 10;
    private static final String DEFAULT_MODEL = "geckolib:winefox_hanfu";
    private final Set<UUID> pendingObservers = ConcurrentHashMap.newKeySet();

    private AnimationLab() {}

    public static void register() {
        if (!Boolean.getBoolean("tlmnh.devCommands")) return;
        require(DIMENSION > 1 && DIMENSION <= 32767, "Invalid animation lab dimension ID");
        require(!DimensionManager.isDimensionRegistered(DIMENSION), "Animation lab dimension is already registered");
        require(
            DimensionManager.registerProviderType(DIMENSION, WorldProviderAnimationLab.class, false),
            "Animation lab provider ID is already registered");
        DimensionManager.registerDimension(DIMENSION, DIMENSION);
        var listener = new AnimationLab();
        FMLCommonHandler.instance()
            .bus()
            .register(listener);
        MinecraftForge.EVENT_BUS.register(listener);
        TLMNotHard.LOG.info("TLMNH_ANIMATION_LAB_REGISTERED dimension={} terrain=air", DIMENSION);
    }

    public static void validateAutoEntry() {
        if (Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.animationLabAutoEnter")) {
            spectator();
            TLMNotHard.LOG.info("TLMNH_ANIMATION_LAB_SPECTATOR_READY mode=3");
        }
    }

    private static GameType spectator() {
        var mode = GameType.getByID(3);
        require(
            mode.getID() == 3,
            "RC2 spectator mode is unavailable; enable Et Futurum spectator mode before testing");
        return mode;
    }

    public static boolean isLab(World world) {
        return world != null && world.provider instanceof WorldProviderAnimationLab;
    }

    private static WorldServer world() {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Animation lab disabled");
        if (DimensionManager.getWorld(DIMENSION) == null) DimensionManager.initDimension(DIMENSION);
        var world = DimensionManager.getWorld(DIMENSION);
        require(isLab(world), "Unexpected animation lab provider");
        return world;
    }

    public static void prepare() {
        var world = world();
        for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) loadChunk(world, x, z);
        for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++) {
            int color = ((x + z) & 1) == 0 ? 7 : 8;
            if (x == 0) color = 13;
            if (z == 0) color = 11;
            if (x == 0 && z == 0) color = 14;
            world.setBlock(x, FLOOR, z, Blocks.wool, color, 2);
        }
        world.setAllowedSpawnTypes(false, false);
    }

    public static void enter(EntityPlayerMP player, String model) {
        require(BuiltinModelCatalog.contains(model), "Unknown builtin lab model");
        require(player.inventory.getItemStack() == null, "Put down the cursor item before entering the lab");
        var spectator = spectator();
        if (!isLab(player.worldObj)) {
            var saved = new NBTTagCompound();
            saved.setInteger("dimension", player.dimension);
            saved.setDouble("x", player.posX);
            saved.setDouble("y", player.posY);
            saved.setDouble("z", player.posZ);
            saved.setFloat("yaw", player.rotationYaw);
            saved.setFloat("pitch", player.rotationPitch);
            saved.setInteger(
                "gameMode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            player.getEntityData()
                .setTag(RETURN, saved);
        }
        player.isDead = false;
        player.deathTime = 0;
        player.setHealth(player.getMaxHealth());
        player.extinguish();
        player.setGameType(spectator);
        prepare();
        var world = world();
        var maid = sceneMaid(world);
        if (maid == null) {
            maid = new EntityMaid(world);
            maid.getEntityData()
                .setBoolean(MAID_MARKER, true);
            maid.bindTo(player.getUniqueID());
            maid.setSitting(true);
            maid.func_110163_bv();
            maid.setLocationAndAngles(0.5, FLOOR + 1, 0.5, 180, 0);
            maid.prevRotationYaw = maid.renderYawOffset = maid.prevRenderYawOffset = 180;
            maid.rotationYawHead = maid.prevRotationYawHead = 180;
            maid.setModelId(model);
            require(world.spawnEntityInWorld(maid), "Animation lab maid spawn rejected");
        } else maid.setModelId(model);
        transfer(player, world, 0.5, FLOOR + 1, -4.5, 0, 10);
        player.sendPlayerAbilities();
        TLMNotHard.LOG.info(
            "TLMNH_ANIMATION_LAB_ENTER player={} dimension={} mode={} model={} maid={}",
            player.getCommandSenderName(),
            player.dimension,
            player.theItemInWorldManager.getGameType()
                .getID(),
            maid.getModelId(),
            maid.getUniqueID());
    }

    public static void leave(EntityPlayerMP player) {
        require(
            isLab(player.worldObj) && player.getEntityData()
                .hasKey(RETURN, 10),
            "No lab return position saved");
        var saved = player.getEntityData()
            .getCompoundTag(RETURN);
        int dimension = saved.getInteger("dimension");
        require(
            dimension != DIMENSION && DimensionManager.isDimensionRegistered(dimension),
            "Invalid return dimension");
        var destination = MinecraftServer.getServer()
            .worldServerForDimension(dimension);
        transfer(
            player,
            destination,
            saved.getDouble("x"),
            saved.getDouble("y"),
            saved.getDouble("z"),
            saved.getFloat("yaw"),
            saved.getFloat("pitch"));
        player.setGameType(GameType.getByID(saved.getInteger("gameMode")));
        player.getEntityData()
            .removeTag(RETURN);
    }

    private static void transfer(EntityPlayerMP player, WorldServer destination, double x, double y, double z,
        float yaw, float pitch) {
        if (player.dimension != destination.provider.dimensionId) MinecraftServer.getServer()
            .getConfigurationManager()
            .transferPlayerToDimension(player, destination.provider.dimensionId, new Teleporter(destination) {

                @Override
                public void placeInPortal(Entity entity, double previousX, double previousY, double previousZ,
                    float previousYaw) {
                    entity.setLocationAndAngles(x, y, z, yaw, pitch);
                }
            });
        player.motionX = player.motionY = player.motionZ = 0;
        player.fallDistance = 0;
        player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
    }

    private static EntityMaid sceneMaid(WorldServer world) {
        EntityMaid found = null;
        for (var entity : world.loadedEntityList) if (entity instanceof EntityMaid maid && maid.getEntityData()
            .getBoolean(MAID_MARKER) && !maid.isDead) {
                require(found == null, "Multiple animation lab maids found");
                found = maid;
            }
        return found;
    }

    public static void verifyWorld() {
        prepare();
        var world = world();
        for (int x : new int[] { -32, 32 }) for (int z : new int[] { -32, 32 }) {
            loadChunk(world, x >> 4, z >> 4);
            for (int y = 0; y < 256; y++) require(world.isAirBlock(x, y, z), "Lab must be void outside the platform");
        }
        for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++)
            require(world.getBlock(x, FLOOR, z) == Blocks.wool, "Missing lab platform block");
        var slime = new EntitySlime(world);
        slime.setPosition(4, FLOOR + 1, 4);
        try {
            require(!world.spawnEntityInWorld(slime), "Non-lab entities must be rejected");
        } finally {
            slime.setDead();
        }
        require(world.getWorldTime() == 6000, "Lab daylight is not fixed");
        require(!world.isRaining() && !world.isThundering(), "Lab weather must remain clear");
        TLMNotHard.LOG
            .info("TLMNH_ANIMATION_LAB_WORLD_PASS dimension={} airSamples=1024 platform=441 slime=rejected", DIMENSION);
    }

    public static void verifyObserver(EntityPlayerMP player) {
        require(isLab(player.worldObj), "Observer is outside the animation lab");
        require(
            player.theItemInWorldManager.getGameType()
                .getID() == 3,
            "Observer is not in spectator mode");
        require(
            player.capabilities.disableDamage && player.capabilities.allowFlying && player.capabilities.isFlying,
            "Observer abilities are unsafe");
        require(player.getHealth() > 0 && !player.isDead, "Observer is not alive");
        var maid = sceneMaid((WorldServer) player.worldObj);
        require(maid != null && BuiltinModelCatalog.contains(maid.getModelId()), "Missing lab maid");
        TLMNotHard.LOG.info(
            "TLMNH_ANIMATION_LAB_OBSERVER_PASS player={} mode=3 invulnerable=true flight=true model={}",
            player.getCommandSenderName(),
            maid.getModelId());
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (Boolean.getBoolean("tlmnh.animationLabAutoEnter") && event.player instanceof EntityPlayerMP player)
            pendingObservers.add(player.getUniqueID());
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        pendingObservers.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.player.worldObj.isRemote
            && event.player instanceof EntityPlayerMP player
            && pendingObservers.remove(player.getUniqueID())) {
            // 所有登录监听器先完成，再在正常服务端实体 tick 中切换维度
            enter(player, System.getProperty("tlmnh.labModel", DEFAULT_MODEL));
        }
        if (event.phase == TickEvent.Phase.START && !event.player.worldObj.isRemote
            && isLab(event.player.worldObj)
            && event.player instanceof EntityPlayerMP player
            && player.theItemInWorldManager.getGameType()
                .getID() != 3)
            player.setGameType(spectator());
    }

    @SubscribeEvent
    public void rejectEntities(EntityJoinWorldEvent event) {
        if (!isLab(event.world) || event.world.isRemote) return;
        if (event.entity instanceof EntityPlayer) return;
        if (event.entity instanceof EntityMaid maid && maid.getEntityData()
            .getBoolean(MAID_MARKER)) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void protectObserver(LivingAttackEvent event) {
        if (isLab(event.entityLiving.worldObj) && event.entityLiving instanceof EntityPlayer) event.setCanceled(true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void loadChunk(WorldServer world, int x, int z) {
        var chunk = world.getChunkProvider()
            .loadChunk(x, z);
        require(chunk != null && !(chunk instanceof EmptyChunk), "Animation lab chunk was not loaded");
    }
}
