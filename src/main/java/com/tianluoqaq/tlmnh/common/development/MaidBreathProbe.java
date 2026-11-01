package com.tianluoqaq.tlmnh.common.development;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 复用原生世界 tick 的四段低氧场景，只有场景准备设置初始空气和位置 */
public final class MaidBreathProbe {

    private static MaidBreathProbe pending;
    private final WorldServer world;
    private final Map<ChunkCoordinates, Block> blocks = new LinkedHashMap<>();
    private final Map<ChunkCoordinates, Integer> metadata = new LinkedHashMap<>();
    private EntityMaid maid;
    private ItemStack main, off;
    private int x, z, ticks, phase, phaseStart;
    private boolean finished;

    private MaidBreathProbe(WorldServer world) {
        this.world = world;
    }

    public static void start(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && world.provider.dimensionId == 0 && pending == null,
            "Dedicated test overworld and one episode required");
        var probe = new MaidBreathProbe(world);
        pending = probe;
        try {
            probe.begin();
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
        } catch (RuntimeException | Error failure) {
            probe.finish(false, failure.toString());
            throw failure;
        }
    }

    private void begin() {
        var spawn = world.getSpawnPoint();
        x = spawn.posX + 12;
        z = spawn.posZ + 32;
        for (int px = x - 6; px <= x + 6; px++) for (int pz = z - 4; pz <= z + 4; pz++) {
            world.getChunkFromBlockCoords(px, pz);
            for (int py = 99; py <= 105; py++) {
                var point = new ChunkCoordinates(px, py, pz);
                require(world.isAirBlock(px, py, pz), "Empty bounded test volume required");
                blocks.put(point, world.getBlock(px, py, pz));
                metadata.put(point, world.getBlockMetadata(px, py, pz));
            }
        }
        maid = new EntityMaid(world);
        maid.getBrainCoordinator()
            .setSuspended(true);
        main = new ItemStack(Items.iron_sword);
        off = new ItemStack(Items.stick);
        maid.getMaidInventory()
            .setInventorySlotContents(MaidInventory.MAIN_HAND, main);
        maid.getMaidInventory()
            .setInventorySlotContents(MaidInventory.OFF_HAND, off);
        var potion = new ItemStack(Items.potionitem);
        var tag = new NBTTagCompound();
        var effects = new NBTTagList();
        effects.appendTag(
            new PotionEffect(Potion.waterBreathing.id, 600, 0).writeCustomPotionEffectToNBT(new NBTTagCompound()));
        tag.setTag("CustomPotionEffects", effects);
        potion.setTagCompound(tag);
        maid.getMaidInventory()
            .setInventorySlotContents(0, potion);
        prepare(true, false);
        require(world.spawnEntityInWorld(maid), "Registered breath actor required");
        verifyThresholdScope();
        verifyFluidBounds();
        TLMNotHard.LOG
            .info("TLMNH_BREATH_RUNTIME_STARTED natural_world_ticks=true initial_air=90 aquatic_motion=production");
    }

    private void verifyThresholdScope() {
        var cow = new net.minecraft.entity.passive.EntityCow(world);
        cow.tasks.taskEntries.clear();
        cow.targetTasks.taskEntries.clear();
        cow.setPosition(x + .5, 108, z + .5);
        cow.motionX = .004;
        cow.onLivingUpdate();
        require(cow.motionX == 0, "Ordinary mob retains native motion threshold");
        cow.setDead();
        var idle = new EntityMaid(world);
        idle.getBrainCoordinator()
            .setSuspended(true);
        idle.setPosition(x + .5, 108, z + .5);
        idle.motionX = .004;
        idle.onLivingUpdate();
        require(idle.motionX == 0, "Non-swimming maid retains native motion threshold");
        idle.setDead();
        TLMNotHard.LOG.info("TLMNH_BREATH_THRESHOLD_SCOPE_PASS ordinary_mob=native idle_maid=native");
    }

    private void verifyFluidBounds() {
        var probe = new EntityMaid(world);
        try {
            for (var pose : new com.tianluoqaq.tlmnh.common.entity.MaidEntityPose[] {
                com.tianluoqaq.tlmnh.common.entity.MaidEntityPose.SWIMMING,
                com.tianluoqaq.tlmnh.common.entity.MaidEntityPose.SLEEPING }) {
                probe.setEntityPose(pose);
                for (int fraction = 0; fraction < 10; fraction++) {
                    probe.setPosition(x + .5, 100 + fraction / 10.0, z + .5);
                    require(probe.handleWaterMovement(), "Small body remains wet across fractional block heights");
                }
                probe.setPosition(x + .5, 108, z + .5);
                require(!probe.handleWaterMovement(), "Small dry body has no fabricated water");
            }
            TLMNotHard.LOG.info("TLMNH_AQUATIC_FLUID_BOUNDS_PASS poses=2 fractions=10 dry=2 native_water_method=true");
        } finally {
            probe.setDead();
        }
    }

    private void prepare(boolean roof, boolean opening) {
        maid.getBrainCoordinator()
            .setSuspended(true);
        for (int dx = -5; dx <= 5; dx++) for (int dz = -3; dz <= 3; dz++) for (int y = 99; y <= 105; y++) {
            boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 3;
            Block block = y == 99 || wall && y <= 104 ? Blocks.glass
                : y <= 103 ? Blocks.water
                    : y == 104 && roof && !(opening && dx >= 3 && Math.abs(dz) <= 1) ? Blocks.glass : Blocks.air;
            world.setBlock(x + dx, y, z + dz, block, 0, 2);
        }
        maid.setPosition(x + .5, 100, z + .5);
        maid.motionX = maid.motionY = maid.motionZ = 0;
        maid.setAir(90);
        maid.removePotionEffect(Potion.waterBreathing.id);
        maid.getBrainCoordinator()
            .setSuspended(false);
        phaseStart = ticks;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        try {
            ticks++;
            require(ticks <= 600 && maid.isEntityAlive(), "Breath episode failed or timed out");
            var meals = maid.getMealController();
            var breath = maid.getBrainCoordinator()
                .getBreathController();
            int elapsed = ticks - phaseStart;
            if (phase == 3 && elapsed % 20 == 0) TLMNotHard.LOG.info(
                "TLMNH_BREATH_TRACE elapsed={} x={} y={} z={} route={}",
                elapsed,
                maid.posX,
                maid.posY,
                maid.posZ,
                breath.routeDiagnostic());
            if (phase == 0 && meals.isBreathing()) {
                maid.writeToNBT(new NBTTagCompound());
                require(
                    !meals.isEating() && count(Items.potionitem) == 1 && count(Items.glass_bottle) == 0,
                    "Save cancels drink without consumption or bottle");
                hands();
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.setAir(90);
                maid.getBrainCoordinator()
                    .setSuspended(false);
                phase = 1;
                phaseStart = ticks;
            } else if (phase == 1 && maid.isPotionActive(Potion.waterBreathing)) {
                require(
                    count(Items.potionitem) == 0 && count(Items.glass_bottle) == 1 && maid.getFavorability() == 0,
                    "Single breathing potion/bottle, no work meal reward");
                hands();
                TLMNotHard.LOG.info(
                    "TLMNH_BREATH_PHASE_PASS phase=potion elapsed={} save_cancel=conserved hands=restored",
                    elapsed);
                prepare(false, false);
                phase = 2;
            } else if (phase == 2 && maid.getAir() >= 290 && !breath.isActive()) {
                require(maid.posY > 102, "Native movement surfaced from water");
                TLMNotHard.LOG.info(
                    "TLMNH_BREATH_PHASE_PASS phase=open_surface elapsed={} y={} health={}",
                    elapsed,
                    maid.posY,
                    maid.getHealth());
                prepare(true, true);
                phase = 3;
            } else if (phase == 3 && maid.getAir() >= 290 && !breath.isActive()) {
                require(maid.posX >= x + 2.8 && maid.posY > 102, "Connected route found the side air opening");
                TLMNotHard.LOG.info(
                    "TLMNH_BREATH_PHASE_PASS phase=overhang elapsed={} x={} y={} health={}",
                    elapsed,
                    maid.posX,
                    maid.posY,
                    maid.getHealth());
                prepare(true, false);
                phase = 4;
            } else if (phase == 4 && elapsed >= 20) {
                require(
                    breath.isActive() && "no_air_route".equals(breath.getStatus()) && maid.getAir() < 90,
                    "Sealed water has no fabricated air or escape route");
                hands();
                maid.getBrainCoordinator()
                    .setSuspended(true);
                require(!breath.isActive() && !meals.isEating(), "Lifecycle cleanup releases survival and item use");
                finish(true, "save-cancel;potion;open-surface;overhang;sealed;cleanup");
            }
            if (elapsed > 180) throw new IllegalStateException(
                "Phase timeout: " + phase
                    + " status="
                    + breath.getStatus()
                    + " air="
                    + maid.getAir()
                    + " pos="
                    + maid.posX
                    + ","
                    + maid.posY
                    + ","
                    + maid.posZ);
        } catch (RuntimeException | Error failure) {
            finish(false, failure.toString());
        }
    }

    private int count(Item item) {
        int result = 0;
        var inventory = maid.getMaidInventory();
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) result += stack.stackSize;
        }
        return result;
    }

    private void hands() {
        require(
            maid.getMaidInventory()
                .getStackInSlot(MaidInventory.MAIN_HAND) == main
                && maid.getMaidInventory()
                    .getStackInSlot(MaidInventory.OFF_HAND) == off,
            "Original hands conserved");
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        if (maid != null) {
            maid.getBrainCoordinator()
                .setSuspended(true);
            world.removeEntity(maid);
            maid.setDead();
        }
        for (var entry : blocks.entrySet()) {
            var p = entry.getKey();
            world.setBlock(p.posX, p.posY, p.posZ, entry.getValue(), metadata.get(p), 2);
        }
        pending = null;
        TLMNotHard.LOG.info(
            "TLMNH_BREATH_RUNTIME_TEST_{} ticks={} detail={} benchmark=unverified",
            passed ? "PASS" : "FAIL",
            ticks,
            detail);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
