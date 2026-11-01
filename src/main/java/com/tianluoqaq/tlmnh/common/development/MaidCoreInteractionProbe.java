package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 实际RC2服务端夹具，使用隔离玩家和临时实体；不声称验证客户端点击或网络包顺序 */
public final class MaidCoreInteractionProbe {

    private MaidCoreInteractionProbe() {}

    public static void run(WorldServer world) {
        try (var fixture = new Fixture(world)) {
            carry(fixture);
            foodAndDrinks(fixture);
            experience(fixture);
            permissionsAndHands(fixture);
            TLMNotHard.LOG.info(
                "TLMNH_CORE_INTERACTIONS_TEST_PASS carry=relations consumables=conserved xp=12 creative=checked"
                    + " spectator=denied backhand=current-slot-only network=unverified");
        }
    }

    public static void runConfig(WorldServer world) {
        try (var f = new Fixture(world)) {
            world.getChunkFromBlockCoords((int) Math.floor(f.maid.posX), (int) Math.floor(f.maid.posZ));
            require(world.spawnEntityInWorld(f.maid), "registered config maid");
            var menu = new com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer(
                f.owner.inventory,
                f.maid);
            f.owner.openContainer = menu;
            require(menu.inventorySlots.size() == 36, "config contains only 36 player slots");
            require(
                menu.enchantItem(f.owner, 24) && Math.abs(
                    f.maid.getConfigManager()
                        .getSoundFreq() - 0.9F)
                    < 0.00001F,
                "left arrow reduces by ten percent");
            for (int i = 0; i < 12; i++) require(menu.enchantItem(f.owner, 24), "lower bound action");
            require(
                f.maid.getConfigManager()
                    .getSoundFreq() == 0,
                "lower clamp");
            for (int i = 0; i < 12; i++) require(menu.enchantItem(f.owner, 25), "upper bound action");
            require(
                f.maid.getConfigManager()
                    .getSoundFreq() == 1,
                "upper clamp");
            require(!menu.enchantItem(f.stranger, 24), "foreign viewer denied");
            var config = f.maid.getConfigManager();
            require(
                menu.enchantItem(f.owner, 27) && config.getPickupType()
                    == com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ONLY_ITEM,
                "pickup type next wraps");
            f.maid.setPickup(false);
            require(
                !f.maid.isPickup() && config.getPickupType()
                    == com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ONLY_ITEM,
                "master switch preserves preferred type");
            f.maid.setPickup(true);
            require(f.maid.canPickupItems() && !f.maid.canPickupExperience(), "type effective after reenabling");
            require(
                menu.enchantItem(f.owner, 26)
                    && config.getPickupType() == com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ALL,
                "pickup type previous wraps");
            for (int action = 28; action <= 30; action++)
                require(menu.enchantItem(f.owner, action), "navigation setting toggle");
            require(
                !config.isOpenDoor() && !config.isOpenFenceGate() && !config.isActiveClimbing(),
                "navigation flags independent");
            var saved = new NBTTagCompound();
            f.maid.writeEntityToNBT(saved);
            saved.getCompoundTag("MaidSubConfig")
                .setString("FutureSetting", "preserve");
            var restored = new EntityMaid(world);
            restored.readEntityFromNBT(saved);
            require(
                !restored.getConfigManager()
                    .isOpenDoor()
                    && !restored.getConfigManager()
                        .isOpenFenceGate()
                    && !restored.getConfigManager()
                        .isActiveClimbing(),
                "navigation NBT roundtrip");
            var roundtrip = new NBTTagCompound();
            restored.writeEntityToNBT(roundtrip);
            require(
                "preserve".equals(
                    roundtrip.getCompoundTag("MaidSubConfig")
                        .getString("FutureSetting")),
                "unknown subconfig retained");
            restored.getDataWatcher()
                .updateObject(31, "1;0.4");
            require(
                Math.abs(
                    restored.getConfigManager()
                        .getSoundFreq() - 0.4F)
                    < 0.00001
                    && restored.getConfigManager()
                        .isOpenDoor(),
                "old metadata readable");
            for (int action = 28; action <= 30; action++)
                require(!menu.enchantItem(f.stranger, action), "foreign navigation toggle denied");
            f.owner.inventory.setItemStack(new ItemStack(Items.stick));
            require(!menu.enchantItem(f.owner, 24), "held cursor denied");
            f.owner.inventory.setItemStack(null);
            double x = f.owner.posX;
            f.owner.setPosition(x + 32, f.owner.posY, f.owner.posZ);
            require(!menu.enchantItem(f.owner, 24), "distant viewer denied");
            f.owner.setPosition(x, f.owner.posY, f.owner.posZ);
            require(
                !menu.enchantItem(f.owner, 127) && f.maid.getConfigManager()
                    .getSoundFreq() == 1,
                "unknown action preserves frequency");
            f.clearInventory();
            f.owner.inventory.setInventorySlotContents(9, new ItemStack(Items.arrow, 5));
            require(
                menu.transferStackInSlot(f.owner, 0) != null && f.owner.inventory.getStackInSlot(9) == null
                    && f.owner.inventory.getStackInSlot(0).stackSize == 5,
                "player inventory to hotbar");
            require(
                menu.transferStackInSlot(f.owner, 27) != null && f.owner.inventory.getStackInSlot(0) == null
                    && f.owner.inventory.getStackInSlot(9).stackSize == 5,
                "hotbar back to inventory conserved");
            require(
                menu.transferStackInSlot(f.stranger, 0) == null && f.owner.inventory.getStackInSlot(9).stackSize == 5,
                "foreign move denied");
            TLMNotHard.LOG.info(
                "TLMNH_CONFIG_TEST_PASS frequency=step-and-clamps pickup-type=wrap-and-master navigation=independent nbt=roundtrip-and-unknown owner=checked cursor=denied slots=36 player-transfer=conserved client=unverified");
        }
    }

    public static void runPickupAndTraversal(WorldServer world) {
        try (var f = new Fixture(world)) {
            pickupBatch(f);
            traversalBatch(f);
            TLMNotHard.LOG.info(
                "TLMNH_PICKUP_TRAVERSAL_TEST_PASS inventory=partial-full-simulated events=cancelled nbt=preserved xp=type-and-mending contact=actual-tick gates=path-open-close iron=denied ladder=up-down-toggle world=restored benchmark=unverified");
        }
    }

    private static void pickupBatch(Fixture f) {
        var maid = f.maid;
        var inv = maid.getMaidInventory();
        for (int i = 0; i < inv.getSizeInventory(); i++) inv.setInventorySlotContents(i, null);
        for (int i = 0; i <= com.tianluoqaq.tlmnh.common.inventory.MaidInventory.OFF_HAND; i++)
            inv.setInventorySlotContents(i, new ItemStack(Items.stick, 64));
        var tagged = new ItemStack(Items.arrow, 62);
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound()
            .setString("Probe", "kept");
        inv.setInventorySlotContents(0, tagged);
        var input = tagged.copy();
        input.stackSize = 5;
        var item = new EntityItem(f.world, maid.posX, maid.posY, maid.posZ, input);
        item.delayBeforeCanPickup = 0;
        require(
            maid.pickupItem(item, true) && inv.getStackInSlot(0).stackSize == 62 && item.getEntityItem().stackSize == 5,
            "simulation leaves both sides unchanged");
        require(
            maid.pickupItem(item, false) && inv.getStackInSlot(0).stackSize == 64
                && item.getEntityItem().stackSize == 3
                && !item.isDead,
            "partial pickup conserves remainder");
        require(
            "kept".equals(
                item.getEntityItem()
                    .getTagCompound()
                    .getString("Probe")),
            "remainder NBT preserved");
        require(
            !maid.pickupItem(item, false) && item.getEntityItem().stackSize == 3,
            "full inventory leaves ground stack");
        inv.setInventorySlotContents(1, null);
        var cancel = new CancelPickup(maid);
        MinecraftForge.EVENT_BUS.register(cancel);
        try {
            require(
                !maid.pickupItem(item, false) && item.getEntityItem().stackSize == 3 && inv.getStackInSlot(1) == null,
                "cancelled pickup has no mutation");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(cancel);
        }
        require(
            maid.pickupItem(item, false) && item.isDead && inv.getStackInSlot(1).stackSize == 3,
            "remaining stack inserted once");
        item.setDead();
        var orb = new net.minecraft.entity.item.EntityXPOrb(f.world, maid.posX, maid.posY, maid.posZ, 7);
        orb.ticksExisted = 3;
        maid.setExperience(0);
        maid.getConfigManager()
            .setPickupType(com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ONLY_ITEM);
        maid.pickupXPOrb(orb);
        require(!orb.isDead && maid.getExperience() == 0, "item-only rejects XP");
        maid.getConfigManager()
            .setPickupType(com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ONLY_XP);
        maid.pickupXPOrb(orb);
        require(orb.isDead && maid.getExperience() == 7, "XP-only collects orb");
        net.minecraft.enchantment.Enchantment mending = null;
        for (var enchantment : net.minecraft.enchantment.Enchantment.enchantmentsList)
            if (enchantment != null && "enchantment.mending".equals(enchantment.getName())) {
                mending = enchantment;
                break;
            }
        require(mending != null, "RC2 mending enchantment present");
        var tool = new ItemStack(Items.iron_pickaxe);
        tool.setItemDamage(10);
        tool.addEnchantment(mending, 1);
        maid.setOffhandItem(tool);
        var repairOrb = new net.minecraft.entity.item.EntityXPOrb(f.world, maid.posX, maid.posY, maid.posZ, 8);
        repairOrb.ticksExisted = 3;
        maid.pickupXPOrb(repairOrb);
        require(
            repairOrb.isDead && tool.getItemDamage() == 0 && maid.getExperience() == 10,
            "offhand mending consumes five XP and stores three");
        maid.getConfigManager()
            .setPickupType(com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ALL);
        inv.setInventorySlotContents(3, null);
        var arrow = new net.minecraft.entity.projectile.EntityArrow(f.world);
        var arrowState = new NBTTagCompound();
        arrow.writeToNBT(arrowState);
        arrowState.setBoolean("inGround", true);
        arrowState.setByte("pickup", (byte) 1);
        arrowState.setByte("shake", (byte) 0);
        arrow.readFromNBT(arrowState);
        require(
            maid.pickupArrow(arrow, true) && !arrow.isDead && inv.getStackInSlot(3) == null,
            "ground arrow simulation");
        arrow.canBePickedUp = 2;
        require(!maid.pickupArrow(arrow, false), "creative-only arrow cannot create ammunition");
        arrow.canBePickedUp = 1;
        require(
            maid.pickupArrow(arrow, false) && arrow.isDead
                && inv.getStackInSlot(3)
                    .getItem() == Items.arrow,
            "ground arrow recovered once");
        maid.getConfigManager()
            .setPickupType(com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType.ALL);
        inv.setInventorySlotContents(2, null);
        var contact = new EntityItem(f.world, maid.posX, maid.posY, maid.posZ, new ItemStack(Items.apple));
        contact.delayBeforeCanPickup = 0;
        require(f.world.spawnEntityInWorld(contact), "contact item registered");
        maid.onLivingUpdate();
        require(
            contact.isDead && inv.getStackInSlot(2)
                .getItem() == Items.apple,
            "normal living tick collects contact item");
        f.world.removeEntity(contact);
    }

    private static void traversalBatch(Fixture f) {
        var world = f.world;
        var maid = f.maid;
        int x = (int) Math.floor(maid.posX), z = (int) Math.floor(maid.posZ), y = 100;
        var backup = new java.util.LinkedHashMap<net.minecraft.util.ChunkCoordinates, net.minecraft.block.Block>();
        var metadata = new java.util.HashMap<net.minecraft.util.ChunkCoordinates, Integer>();
        try {
            for (int bx = x - 1; bx <= x + 5; bx++)
                for (int bz = z - 1; bz <= z + 1; bz++) for (int by = y - 1; by <= y + 4; by++) {
                    var p = new net.minecraft.util.ChunkCoordinates(bx, by, bz);
                    backup.put(p, world.getBlock(bx, by, bz));
                    metadata.put(p, world.getBlockMetadata(bx, by, bz));
                    world.setBlock(
                        bx,
                        by,
                        bz,
                        by == y - 1 || bz != z ? net.minecraft.init.Blocks.stone : net.minecraft.init.Blocks.air,
                        0,
                        2);
                }
            world.setBlock(x + 1, y, z, net.minecraft.init.Blocks.fence_gate, 0, 2);
            maid.setPosition(x + 0.5, y, z + 0.5);
            maid.onGround = true;
            maid.getBrainCoordinator()
                .setSuspended(false);
            var config = maid.getConfigManager();
            config.setOpenDoor(true);
            config.setOpenFenceGate(true);
            config.setActiveClimbing(true);
            var traversal = new com.tianluoqaq.tlmnh.common.ai.MaidTraversalController(maid);
            traversal.configureNavigator();
            var path = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery
                .create(maid, x + 5, y, z + 1, 8, true, true, false, true);
            require(path != null && path.getFinalPathPoint().xCoord >= x + 3, "closed gate can be planned through");
            maid.getNavigator()
                .setPath(path, 1);
            path.setCurrentPathIndex(1);
            traversal.tick();
            require((world.getBlockMetadata(x + 1, y, z) & 4) != 0, "path execution opens gate");
            maid.setPosition(x + 4.5, y, z + 0.5);
            maid.getNavigator()
                .clearPathEntity();
            traversal.tick();
            require((world.getBlockMetadata(x + 1, y, z) & 4) == 0, "passed gate closes");
            config.setOpenFenceGate(false);
            maid.setPosition(x + 0.5, y, z + 0.5);
            var blocked = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery
                .create(maid, x + 5, y, z + 1, 8, true, true, false, true);
            require(blocked == null || blocked.getFinalPathPoint().xCoord <= x, "disabled gate blocks corridor");
            world.setBlock(x + 1, y, z, net.minecraft.init.Blocks.wooden_door, 0, 2);
            world.setBlock(x + 1, y + 1, z, net.minecraft.init.Blocks.wooden_door, 8, 2);
            maid.getNavigator()
                .setPath(
                    new net.minecraft.pathfinding.PathEntity(
                        new net.minecraft.pathfinding.PathPoint[] {
                            new net.minecraft.pathfinding.PathPoint(x + 1, y, z) }),
                    1);
            traversal.tick();
            require(
                ((net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.wooden_door)
                    .func_150015_f(world, x + 1, y, z),
                "wood door opens on current path");
            maid.setPosition(x + 4.5, y, z + 0.5);
            maid.getNavigator()
                .clearPathEntity();
            traversal.tick();
            require(
                !((net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.wooden_door)
                    .func_150015_f(world, x + 1, y, z),
                "passed wood door closes");
            maid.setPosition(x + 0.5, y, z + 0.5);
            world.setBlock(x + 1, y, z, net.minecraft.init.Blocks.iron_door, 0, 2);
            world.setBlock(x + 1, y + 1, z, net.minecraft.init.Blocks.iron_door, 8, 2);
            maid.getNavigator()
                .setPath(
                    new net.minecraft.pathfinding.PathEntity(
                        new net.minecraft.pathfinding.PathPoint[] {
                            new net.minecraft.pathfinding.PathPoint(x + 1, y, z) }),
                    1);
            traversal.tick();
            require(
                !((net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.iron_door)
                    .func_150015_f(world, x + 1, y, z),
                "iron door never forced open");
            for (int by = y; by <= y + 3; by++) {
                world.setBlock(x + 1, by, z, net.minecraft.init.Blocks.stone, 0, 2);
                world.setBlock(x, by, z, net.minecraft.init.Blocks.ladder, 4, 2);
            }
            maid.setPosition(x + 0.5, y, z + 0.5);
            maid.motionX = maid.motionY = maid.motionZ = 0;
            var up = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery
                .create(maid, x + 1, y + 3, z + 1, 8, true, true, false, true);
            require(up != null && up.getFinalPathPoint().yCoord == y + 3, "existing ladder column has vertical route");
            maid.getNavigator()
                .setPath(up, 1);
            traversal.tick();
            maid.moveEntityWithHeading(0, 0);
            require(maid.posY > y, "ladder ascent reaches native movement");
            maid.setPosition(x + 0.5, y + 3, z + 0.5);
            maid.motionX = maid.motionY = maid.motionZ = 0;
            var down = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery
                .create(maid, x + 1, y, z + 1, 8, true, true, false, true);
            require(down != null && down.getFinalPathPoint().yCoord == y, "ladder descent route");
            maid.getNavigator()
                .setPath(down, 1);
            traversal.tick();
            maid.moveEntityWithHeading(0, 0);
            require(maid.posY < y + 3 && maid.isSneaking(), "controlled descent reaches native movement");
            config.setActiveClimbing(false);
            traversal.tick();
            require(!maid.isSneaking(), "disabled climbing clears owned sneak");
            maid.getNavigator()
                .clearPathEntity();
            traversal.clear();
        } finally {
            maid.getNavigator()
                .clearPathEntity();
            maid.getBrainCoordinator()
                .setSuspended(true);
            var positions = new ArrayList<>(backup.keySet());
            Collections.reverse(positions);
            for (var p : positions) world.setBlock(p.posX, p.posY, p.posZ, backup.get(p), metadata.get(p), 2);
        }
    }

    /** 旧 Forge 的 ASM 分发器从独立类加载器访问监听器，监听类须为 public */
    public static final class CancelPickup {

        private final EntityMaid maid;

        public CancelPickup(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void before(com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent.ItemResultPre event) {
            if (event.getMaid() == maid) event.setCanceled(true);
        }
    }

    public static void runSound(WorldServer world) {
        try (var f = new Fixture(world)) {
            var packs = com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog.packs();
            require(packs.size() == 2, "two builtin sound packs");
            world.getChunkFromBlockCoords((int) Math.floor(f.maid.posX), (int) Math.floor(f.maid.posZ));
            require(world.spawnEntityInWorld(f.maid), "registered sound fixture");
            var container = new com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds(f.owner.inventory, f.maid);
            f.owner.openContainer = container;
            for (int i = 0; i < packs.size(); i++) {
                f.maid.ticksExisted++;
                require(container.enchantItem(f.owner, i), "owned apply accepted");
                require(
                    f.maid.getSoundPackId()
                        .equals(packs.get(i).id),
                    "selected sound applied");
                require(!container.enchantItem(f.owner, 1 - i), "same tick duplicate rejected");
                var tag = new NBTTagCompound();
                f.maid.writeEntityToNBT(tag);
                var restored = new EntityMaid(world);
                restored.readEntityFromNBT(tag);
                require(
                    restored.getSoundPackId()
                        .equals(packs.get(i).id),
                    "sound NBT roundtrip");
                tag.removeTag(EntityMaid.SOUND_PACK_ID_TAG);
                restored.setSoundPackId(packs.get(i).id);
                restored.readEntityFromNBT(tag);
                require(
                    restored.getSoundPackId()
                        .equals(packs.get(i).id),
                    "legacy missing tag retains initialized sound");
            }
            f.maid.ticksExisted++;
            require(
                !container.enchantItem(f.owner, -1) && !container.enchantItem(f.owner, 2),
                "out of catalog rejected");
            require(!container.enchantItem(f.stranger, 0), "other viewer rejected");
            var stranger = new com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds(f.stranger.inventory, f.maid);
            require(!stranger.enchantItem(f.stranger, 0), "nonowner rejected");
            f.owner.inventory.setItemStack(new ItemStack(Items.apple));
            require(!container.enchantItem(f.owner, 0), "cursor item rejected");
            f.owner.inventory.setItemStack(null);
            f.owner.setPosition(f.owner.posX + 100, f.owner.posY, f.owner.posZ);
            require(!container.enchantItem(f.owner, 0), "distance rechecked");
            f.maid.setSoundPackId("untrusted:pack");
            require(
                f.maid.getSoundPackId()
                    .equals(packs.get(1).id),
                "unknown sound ignored");
            TLMNotHard.LOG.info(
                "TLMNH_SOUND_TEST_PASS apply=both-builtin nbt=roundtrip legacy=retained permissions=checked preview=unverified");
        }
    }

    public static void runWorldSound(WorldServer world) {
        try (var f = new Fixture(world)) {
            var config = f.maid.getConfigManager();
            config.setSoundFreq(0.3F);
            var tag = new NBTTagCompound();
            f.maid.writeEntityToNBT(tag);
            tag.getCompoundTag("MaidSubConfig")
                .setString("FutureSetting", "retained");
            var restored = new EntityMaid(world);
            restored.readEntityFromNBT(tag);
            require(
                restored.getConfigManager()
                    .getSoundFreq() == 0.3F,
                "frequency NBT roundtrip");
            var saved = new NBTTagCompound();
            restored.writeEntityToNBT(saved);
            require(
                saved.getCompoundTag("MaidSubConfig")
                    .getString("FutureSetting")
                    .equals("retained"),
                "unknown subconfig retained");
            saved.removeTag("MaidSubConfig");
            var legacy = new EntityMaid(world);
            legacy.readEntityFromNBT(saved);
            require(
                legacy.getConfigManager()
                    .getSoundFreq() == 1,
                "legacy frequency defaults to one");
            try {
                config.setSoundFreq(Float.NaN);
                throw new IllegalStateException("NaN accepted");
            } catch (IllegalArgumentException expected) {}
            config.setSoundFreq(-1);
            require(config.getSoundFreq() == 0, "frequency lower clamp");
            config.setSoundFreq(2);
            require(config.getSoundFreq() == 1, "frequency upper clamp");
            config.setSoundFreq(0.3F);
            var bytes = io.netty.buffer.Unpooled.buffer();
            try {
                var packet = new net.minecraft.network.PacketBuffer(bytes);
                new net.minecraft.network.play.server.S1CPacketEntityMetadata(
                    f.maid.getEntityId(),
                    f.maid.getDataWatcher(),
                    true).writePacketData(packet);
                require(packet.readInt() == f.maid.getEntityId(), "metadata entity ID");
                var values = net.minecraft.entity.DataWatcher.readWatchedListFromPacketBuffer(packet);
                boolean found = false;
                for (var value : values) if (value.getDataValueId() == 31) found = "1;0.3".equals(value.getObject());
                require(found && packet.readableBytes() == 0, "string slot31 survives actual metadata end marker");
                // 专用服务端没有客户端更新方法；真实客户端另验31号字段应用
                packet.clear();
                f.maid.writeSpawnData(packet);
                require(packet.readableBytes() == 16, "additional UUID byte length");
                restored.readSpawnData(packet);
                require(
                    restored.getUniqueID()
                        .equals(f.maid.getUniqueID()),
                    "additional spawn UUID roundtrip");
            } catch (java.io.IOException error) {
                throw new IllegalStateException("world sound metadata codec failed", error);
            } finally {
                bytes.release();
            }
            var state = f.maid.getWorldSoundState();
            var outer = state.beginDamage(DamageSource.causePlayerDamage(f.owner));
            try {
                require(state.hurt() == null, "initial player hurt cooldown");
                for (int i = 0; i < 120; i++) state.tick();
                require(
                    com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.hurt_player")
                        .equals(state.hurt()),
                    "player voice after120 ticks");
                require(state.hurt() == null, "player voice resets120 cooldown");
                var inner = state.beginDamage(DamageSource.onFire);
                require(
                    com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.hurt_fire")
                        .equals(state.hurt()),
                    "fire overrides player cooldown");
                state.endDamage(inner);
                require(state.hurt() == null, "nested damage context restores player");
            } finally {
                state.endDamage(outer);
            }
            require(
                com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.hurt")
                    .equals(state.hurt()),
                "damage context restored");
            long previousTime = world.getWorldTime();
            try {
                world.setWorldTime(1000);
                require(
                    com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("environment.morning")
                        .equals(
                            com.github.tartaricacid.touhoulittlemaid.util.SoundUtil
                                .environmentSound(f.maid, "fallback", 1)),
                    "original morning branch and target biome adapter");
                world.setWorldTime(27000);
                require(
                    !com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("environment.morning")
                        .equals(
                            com.github.tartaricacid.touhoulittlemaid.util.SoundUtil
                                .environmentSound(f.maid, "fallback", 1)),
                    "raw daytime remains unmodded");
            } finally {
                world.setWorldTime(previousTime);
            }
            var canceller = new SoundCanceller(f.maid);
            MinecraftForge.EVENT_BUS.register(canceller);
            try {
                require(
                    state.ambient() == null && state.hurt() == null && state.death() == null,
                    "actual cancellable voice event");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(canceller);
            }
            TLMNotHard.LOG.info(
                "TLMNH_WORLD_SOUND_TEST_PASS config=nbt-and-metadata31 spawn=uuid16 cooldown=120 damage=nested event=cancelled environment=original-priority client=unverified");
        }
    }

    public static final class SoundCanceller {

        private final EntityMaid maid;

        private SoundCanceller(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void onSound(com.github.tartaricacid.touhoulittlemaid.api.event.MaidPlaySoundEvent event) {
            if (event.getMaid() == maid) event.setCanceled(true);
        }
    }

    private static void carry(Fixture f) {
        f.hand(new ItemStack(Items.saddle));
        f.maid.setSitting(true);
        f.maid.setPose(MaidPose.SLEEPING, true);
        require(f.click(), "saddle accepted");
        require(f.maid.ridingEntity == f.owner && f.owner.riddenByEntity == f.maid, "both riding relations");
        require(!f.maid.isSitting() && !f.maid.hasPose(MaidPose.SLEEPING), "carry interrupts resting");
        require(f.held().stackSize == 1, "saddle conserved");
        require(f.click() && f.maid.ridingEntity == null && f.owner.riddenByEntity == null, "saddle dismount");
        var other = f.newMaid();
        other.mountEntity(f.owner);
        require(f.click() && f.maid.ridingEntity == null && f.owner.riddenByEntity == other, "existing passenger kept");
        other.mountEntity(null);
        f.owner.mountEntity(f.maid);
        require(f.click() && f.maid.ridingEntity == null && f.owner.ridingEntity == f.maid, "riding cycle refused");
        f.owner.mountEntity(null);
    }

    private static void foodAndDrinks(Fixture f) {
        f.owner.setSneaking(true);
        f.hand(new ItemStack(Items.golden_apple, 2));
        require(f.click() && f.held().stackSize == 1, "golden apple consumes one");
        effect(f.maid, Potion.regeneration, 100, 1);
        effect(f.maid, Potion.field_76444_x, 2400, 0);
        f.maid.setHealth(10.0F);
        f.maid.getActivePotionEffect(Potion.regeneration)
            .performEffect(f.maid);
        require(f.maid.getHealth() > 10.0F, "golden apple regeneration heals");
        f.maid.clearActivePotions();
        f.hand(new ItemStack(Items.golden_apple, 1, 1));
        require(f.click() && f.held() == null, "enchanted apple consumes last");
        effect(f.maid, Potion.regeneration, 400, 1);
        effect(f.maid, Potion.field_76444_x, 2400, 3);
        effect(f.maid, Potion.resistance, 6000, 0);
        effect(f.maid, Potion.fireResistance, 6000, 0);
        f.maid.clearActivePotions();
        var potion = potion(new PotionEffect(Potion.moveSpeed.id, 333, 1), new PotionEffect(Potion.heal.id, 1, 0));
        var original = potion.copy();
        f.maid.setHealth(10.0F);
        f.hand(potion);
        require(
            f.click() && f.held()
                .getItem() == Items.glass_bottle,
            "potion returns bottle in hand");
        effect(f.maid, Potion.moveSpeed, 333, 1);
        require(f.maid.getHealth() == 14.0F, "instant potion applies immediately");
        require(ItemStack.areItemStacksEqual(potion, original), "potion source and cached effects unmodified");
        var resistant = new PotionEffect(Potion.fireResistance.id, 500, 0);
        resistant.setCurativeItems(Collections.emptyList());
        f.maid.addPotionEffect(resistant);
        f.hand(new ItemStack(Items.milk_bucket));
        require(
            f.click() && f.held()
                .getItem() == Items.bucket,
            "milk returns bucket");
        require(
            !f.maid.isPotionActive(Potion.moveSpeed) && f.maid.isPotionActive(Potion.fireResistance),
            "milk respects Forge curative rules");
        f.maid.clearActivePotions();
        for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++) {
            f.owner.inventory.setInventorySlotContents(slot, new ItemStack(Items.stick, 64));
        }
        f.hand(potion(new PotionEffect(Potion.moveSpeed.id, 222, 0)));
        require(
            f.click() && f.held()
                .getItem() == Items.glass_bottle,
            "full inventory still returns container");
        f.maid.clearActivePotions();
        var stackedPotion = potion(new PotionEffect(Potion.moveSpeed.id, 222, 0));
        stackedPotion.stackSize = 2;
        f.hand(stackedPotion);
        int bottles = f.drops(Items.glass_bottle);
        require(
            f.click() && f.held().stackSize == 1 && f.drops(Items.glass_bottle) == bottles + 1,
            "stacked potion with full inventory drops exactly one container");
        f.maid.clearActivePotions();
        stackedPotion = potion(new PotionEffect(Potion.moveSpeed.id, 222, 0));
        stackedPotion.stackSize = 2;
        f.hand(stackedPotion);
        var reject = new RejectDrop(f.world, f.owner, Items.glass_bottle);
        MinecraftForge.EVENT_BUS.register(reject);
        try {
            require(
                f.click() && f.held().stackSize == 2 && !f.maid.isPotionActive(Potion.moveSpeed),
                "canceled empty-container drop preserves drink and effects");
            require(f.drops(Items.glass_bottle) == bottles + 1, "canceled container has no entity");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
        }
        f.clearInventory();
        f.owner.capabilities.isCreativeMode = true;
        f.hand(new ItemStack(Items.golden_apple));
        require(f.click() && f.held().stackSize == 1, "creative apple conserved");
        f.hand(potion(new PotionEffect(Potion.moveSpeed.id, 111, 0)));
        require(
            f.click() && f.held()
                .getItem() == Items.potionitem && f.held().stackSize == 1,
            "creative potion conserved without free container");
        f.hand(new ItemStack(Items.milk_bucket));
        require(
            f.click() && f.held()
                .getItem() == Items.milk_bucket,
            "creative milk conserved");
        require(
            count(f.owner, Items.glass_bottle) == 0 && count(f.owner, Items.bucket) == 0,
            "creative drinking creates no containers");
        f.owner.capabilities.isCreativeMode = false;
        f.owner.setSneaking(false);
        f.maid.clearActivePotions();
    }

    private static void experience(Fixture f) {
        f.hand(new ItemStack(Items.glass_bottle, 4));
        f.maid.setExperience(35);
        int rewards = f.experienceDrops();
        require(f.click() && f.maid.getExperience() == 23 && f.held().stackSize == 3, "single 12XP exchange");
        require(f.experienceDrops() == rewards + 1, "single reward exists");
        f.owner.setSneaking(true);
        require(f.click() && f.maid.getExperience() == 11 && f.held().stackSize == 2, "bulk floors XP");
        require(f.experienceDrops() == rewards + 2, "bulk reward conserved");
        // 不足经验会回退到正常主界面；直接业务分支可证明不扣瓶，也不触发离线玩家GUI网络
        require(
            !com.tianluoqaq.tlmnh.common.interaction.MaidInteractions.interactOwned(f.maid, f.owner, f.held()),
            "insufficient XP falls through");
        require(f.maid.getExperience() == 11 && f.held().stackSize == 2, "insufficient XP conserved");
        f.maid.setExperience(Integer.MAX_VALUE);
        require(
            f.click() && f.maid.getExperience() == Integer.MAX_VALUE - 24 && f.held() == null,
            "large XP bounded by held bottles without overflow");
        f.owner.capabilities.isCreativeMode = true;
        f.hand(new ItemStack(Items.glass_bottle, 3));
        f.maid.setExperience(48);
        require(
            f.click() && f.maid.getExperience() == 12 && f.held().stackSize == 3,
            "creative exchange keeps glass but consumes maid XP through EntityPlayer.interactWith");
        f.owner.capabilities.isCreativeMode = false;
        f.hand(new ItemStack(Items.glass_bottle, 2));
        f.maid.setExperience(24);
        var reject = new RejectDrop(f.world, f.owner, Items.experience_bottle);
        MinecraftForge.EVENT_BUS.register(reject);
        try {
            rewards = f.experienceDrops();
            require(
                f.click() && f.maid.getExperience() == 24 && f.held().stackSize == 2,
                "canceled reward preserves XP and glass");
            require(f.experienceDrops() == rewards, "canceled reward has no entity");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
        }
        f.owner.setSneaking(false);
    }

    private static void permissionsAndHands(Fixture f) {
        f.hand(new ItemStack(Items.saddle));
        f.stranger.inventory.setInventorySlotContents(0, new ItemStack(Items.saddle));
        require(!f.maid.interact(f.stranger) && f.maid.ridingEntity == null, "non-owner carry denied");
        f.owner.setPosition(f.maid.posX + 20, f.maid.posY, f.maid.posZ);
        require(!f.maid.interact(f.owner), "distance denied");
        f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ);
        var spectator = GameType.getByID(3);
        require(spectator.getID() == 3, "RC2 spectator registered");
        f.owner.theItemInWorldManager.setGameType(spectator);
        f.owner.setSneaking(true);
        f.hand(new ItemStack(Items.golden_apple));
        require(f.owner.inventory.currentItem == 0 && f.held() == null, "EFR spectator hides the selected hand");
        require(!f.maid.interact(f.owner), "spectator feeding denied");
        var apple = f.owner.inventory.getStackInSlot(0);
        require(
            apple != null && apple.getItem() == Items.golden_apple && apple.stackSize == 1,
            "spectator apple conserved");
        f.hand(new ItemStack(Items.saddle));
        require(!f.maid.interact(f.owner) && f.maid.ridingEntity == null, "spectator carry denied");
        var wild = f.newMaid();
        f.hand(new ItemStack(Items.cake));
        require(!wild.interact(f.owner) && !wild.isTamed(), "spectator tame denied");
        var cake = f.owner.inventory.getStackInSlot(0);
        require(cake != null && cake.getItem() == Items.cake && cake.stackSize == 1, "spectator cake conserved");
        f.owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        f.owner.setSneaking(false);
        var fake = new FakePlayer(f.world, new GameProfile(UUID.randomUUID(), "[TLMNH-Fake]"));
        fake.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ);
        require(!f.maid.interact(fake), "fake player denied");
        int offhand = offhandSlot(f.owner);
        require(offhand >= 9 && offhand < f.owner.inventory.mainInventory.length, "actual Backhand extended slot");
        f.owner.inventory.setInventorySlotContents(offhand, new ItemStack(Items.saddle));
        require(
            !usingOffhand(f.owner, () -> f.maid.interact(f.owner)) && f.maid.ridingEntity == null,
            "owner interactions remain main-hand-only");
        f.owner.inventory.setInventorySlotContents(offhand, new ItemStack(Items.cake, 2));
        f.hand(new ItemStack(Items.stick, 5));
        require(usingOffhand(f.owner, () -> f.owner.interactWith(wild)) && wild.isOwner(f.owner), "offhand cake tame");
        require(
            f.owner.inventory.getStackInSlot(offhand).stackSize == 1 && f.held().stackSize == 5,
            "offhand consumption uses real slot and restores main hand");
    }

    private static int offhandSlot(EntityPlayer player) {
        try {
            return (Integer) Class.forName("xonin.backhand.api.core.BackhandUtils")
                .getMethod("getOffhandSlot", EntityPlayer.class)
                .invoke(null, player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("RC2 Backhand API unavailable", exception);
        }
    }

    private static boolean usingOffhand(EntityPlayer player, BooleanSupplier action) {
        try {
            return (Boolean) Class.forName("xonin.backhand.api.core.BackhandUtils")
                .getMethod("useOffhandItem", EntityPlayer.class, boolean.class, BooleanSupplier.class)
                .invoke(null, player, false, action);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("RC2 Backhand hand switch failed", exception);
        }
    }

    private static ItemStack potion(PotionEffect... effects) {
        var stack = new ItemStack(Items.potionitem);
        var list = new NBTTagList();
        for (var effect : effects) list.appendTag(effect.writeCustomPotionEffectToNBT(new NBTTagCompound()));
        var tag = new NBTTagCompound();
        tag.setTag("CustomPotionEffects", list);
        stack.setTagCompound(tag);
        return stack;
    }

    private static int count(EntityPlayer player, Item item) {
        int count = 0;
        for (var stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }

    private static void effect(EntityMaid maid, Potion potion, int duration, int amplifier) {
        var effect = maid.getActivePotionEffect(potion);
        require(
            effect != null && effect.getDuration() == duration && effect.getAmplifier() == amplifier,
            "effect " + potion.getId());
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("TLMNH core interaction probe failed: " + label);
    }

    public static final class RejectDrop {

        private final WorldServer world;
        private final EntityPlayer owner;
        private final Item item;

        private RejectDrop(WorldServer world, EntityPlayer owner, Item item) {
            this.world = world;
            this.owner = owner;
            this.item = item;
        }

        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            if (event.world == world && event.entity instanceof EntityItem drop
                && drop.getEntityItem()
                    .getItem() == item
                && drop.getDistanceSqToEntity(owner) < 1.0D) event.setCanceled(true);
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final WorldServer world;
        private final EntityPlayerMP owner;
        private final EntityPlayerMP stranger;
        private final List<EntityMaid> maids = new ArrayList<>();
        private final Set<Entity> originalEntities = new HashSet<>();
        private final EntityMaid maid;

        private Fixture(WorldServer world) {
            this.world = world;
            originalEntities.addAll(world.loadedEntityList);
            owner = player(world, "[TLMNH-Core]");
            stranger = player(world, "[TLMNH-Other]");
            var spawn = world.getSpawnPoint();
            int x = spawn.posX + 12;
            int z = spawn.posZ + 12;
            world.getChunkFromBlockCoords(x, z);
            owner.setPosition(x + 0.5D, Math.max(64, world.getTopSolidOrLiquidBlock(x, z)), z + 0.5D);
            stranger.setPosition(owner.posX, owner.posY, owner.posZ);
            maid = newMaid();
            require(maid.bindTo(owner.getUniqueID()), "fixture binding");
        }

        private EntityMaid newMaid() {
            var result = new EntityMaid(world);
            result.setPosition(owner.posX, owner.posY, owner.posZ);
            result.getBrainCoordinator()
                .setSuspended(true);
            maids.add(result);
            return result;
        }

        private boolean click() {
            return owner.interactWith(maid);
        }

        private void hand(ItemStack stack) {
            owner.inventory.currentItem = 0;
            owner.inventory.setInventorySlotContents(0, stack);
        }

        private ItemStack held() {
            return owner.getCurrentEquippedItem();
        }

        private void clearInventory() {
            for (int slot = 0; slot < owner.inventory.mainInventory.length; slot++) {
                owner.inventory.setInventorySlotContents(slot, null);
            }
        }

        private int experienceDrops() {
            return drops(Items.experience_bottle);
        }

        private int drops(Item type) {
            int count = 0;
            for (Object entity : world.loadedEntityList) {
                if (entity instanceof EntityItem item && !originalEntities.contains(item)
                    && item.getEntityItem()
                        .getItem() == type)
                    count += item.getEntityItem().stackSize;
            }
            return count;
        }

        @Override
        public void close() {
            if (owner.ridingEntity != null) owner.mountEntity(null);
            for (var entity : maids) {
                if (entity.ridingEntity != null) entity.mountEntity(null);
                world.removeEntity(entity);
            }
            for (Object entity : new ArrayList<>(world.loadedEntityList)) {
                if (entity instanceof EntityItem item && !originalEntities.contains(item)) world.removeEntity(item);
            }
            clearInventory();
        }

        private static EntityPlayerMP player(WorldServer world, String name) {
            var player = new EntityPlayerMP(
                MinecraftServer.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), name),
                new ItemInWorldManager(world));
            // 无channel且不加入在线列表，只让原版乘骑/容器路径有正常的出站队列
            new NetHandlerPlayServer(MinecraftServer.getServer(), new NetworkManager(false), player);
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            return player;
        }
    }
}
