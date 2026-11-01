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

    public static void runBackItem(WorldServer world) {
        try (var f = new Fixture(world)) {
            world.getChunkFromBlockCoords((int) Math.floor(f.maid.posX), (int) Math.floor(f.maid.posZ));
            require(world.spawnEntityInWorld(f.maid), "registered production menu maid");
            var inventory = f.maid.getMaidInventory();
            inventory.setInventorySlotContents(5, new ItemStack(Items.diamond_sword));
            var actual = f.maid.getBackpackShowItem();
            var synced = f.maid.getDataWatcher()
                .getWatchableObjectItemStack(18);
            require(
                actual != synced && ItemStack.areItemStacksEqual(actual, synced),
                "back slot copied to tracker metadata");
            actual.setItemDamage(17);
            f.maid.syncBackpackShowItem();
            require(
                f.maid.getDataWatcher()
                    .getWatchableObjectItemStack(18)
                    .getItemDamage() == 17,
                "in-place durability refresh");
            var config = f.maid.getConfigManager();
            var menu = new com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer(
                f.owner.inventory,
                f.maid);
            f.owner.openContainer = menu;
            require(
                config.isShowBackItem() && menu.enchantItem(f.owner, 31) && !config.isShowBackItem(),
                "owner display toggle");
            require(!menu.enchantItem(f.stranger, 31), "foreign display toggle denied");
            var saved = new NBTTagCompound();
            f.maid.writeEntityToNBT(saved);
            var restored = new EntityMaid(world);
            restored.readEntityFromNBT(saved);
            require(
                !restored.getConfigManager()
                    .isShowBackItem()
                    && restored.getBackpackShowItem()
                        .getItemDamage() == 17,
                "flag and back slot persisted");
            restored.getDataWatcher()
                .updateObject(31, "2;0.4;0;0");
            require(
                restored.getConfigManager()
                    .isShowBackItem()
                    && !restored.getConfigManager()
                        .isOpenDoor(),
                "v2 metadata default visuals and preserved navigation");
            inventory.setInventorySlotContents(5, null);
            require(
                f.maid.getDataWatcher()
                    .getWatchableObjectItemStack(18) == null,
                "cleared tracker item");
            var proxy = new com.tianluoqaq.tlmnh.common.proxy.CommonProxy();
            var previous = System.getProperty("tlmnh.devCommands");
            try {
                System.setProperty("tlmnh.devCommands", "false");
                for (int id : new int[] { 3, 4, 5, 6 }) require(
                    proxy.getServerGuiElement(id, f.owner, world, f.maid.getEntityId(), 0, 0) != null,
                    "original menu available without development mode");
                require(
                    proxy.getServerGuiElement(0, f.owner, world, f.maid.getEntityId(), 0, 0) == null,
                    "prototype remains development-only");
                require(
                    proxy.getServerGuiElement(3, f.stranger, world, f.maid.getEntityId(), 0, 0) == null,
                    "foreign original menu denied");
            } finally {
                if (previous == null) System.clearProperty("tlmnh.devCommands");
                else System.setProperty("tlmnh.devCommands", previous);
            }
            TLMNotHard.LOG.info(
                "TLMNH_BACK_ITEM_TEST_PASS tracker=copy-mutate-clear nbt=roundtrip metadata=v2-compatible menus=production-access-and-owner-guard rendering=unverified");
        }
    }

    public static void runBubbles(WorldServer world) {
        boolean enabled = com.tianluoqaq.tlmnh.common.config.MaidChatConfig.enableEmoji;
        int rate = com.tianluoqaq.tlmnh.common.config.MaidChatConfig.checkRate;
        int image = com.tianluoqaq.tlmnh.common.config.MaidChatConfig.imageWeight;
        int text = com.tianluoqaq.tlmnh.common.config.MaidChatConfig.kaomojiWeight;
        try (var f = new Fixture(world)) {
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.enableEmoji = true;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.checkRate = 20;
            f.maid.ticksExisted = (int) Math.floorMod(
                -(f.maid.getUniqueID()
                    .getLeastSignificantBits() % 20),
                20L);
            var manager = f.maid.getChatBubbleManager();
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.imageWeight = 1;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.kaomojiWeight = 0;
            manager.tick();
            var decoded = com.tianluoqaq.tlmnh.common.network.MaidBubbleCodec.decode(
                f.maid.getDataWatcher()
                    .getWatchableObjectString(19));
            require(
                decoded.size() == 1 && decoded.entries()
                    .firstEntry()
                    .getValue()
                    .isImage(),
                "native routine image metadata");
            manager.clear();
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.imageWeight = 0;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.kaomojiWeight = 1;
            manager.tick();
            require(
                manager.getChatBubbleDataCollection()
                    .size() == 1
                    && !manager.getChatBubbleDataCollection()
                        .entries()
                        .firstEntry()
                        .getValue()
                        .text()
                        .isEmpty(),
                "native routine kaomoji loaded");
            manager.clear();
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.kaomojiWeight = 0;
            manager.tick();
            require(
                manager.getChatBubbleDataCollection()
                    .isEmpty(),
                "zero weights safely disable routine generation");
            f.maid.hurtResistantTime = 0;
            f.maid.attackEntityFrom(DamageSource.generic, 1);
            require(
                !manager.getChatBubbleDataCollection()
                    .isEmpty(),
                "actual native damage creates hurt bubble");
            manager.clear();
            manager.getChatBubbleDataCollection()
                .put(
                    System.currentTimeMillis() - 1,
                    com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData
                        .type2("expired"));
            manager.forceUpdateChatBubble();
            f.maid.ticksExisted = 5;
            manager.tick();
            require(
                manager.getChatBubbleDataCollection()
                    .isEmpty()
                    && f.maid.getDataWatcher()
                        .getWatchableObjectString(19)
                        .equals("1;[]"),
                "server expiry clears tracked metadata");
            TLMNotHard.LOG.info(
                "TLMNH_BUBBLES_TEST_PASS routine=image-and-text zero-weights=disabled native-hurt=observed expired-metadata=cleared client=unverified");
        } finally {
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.enableEmoji = enabled;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.checkRate = rate;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.imageWeight = image;
            com.tianluoqaq.tlmnh.common.config.MaidChatConfig.kaomojiWeight = text;
        }
    }

    public static void setVisualFixture(EntityPlayerMP player, String action) {
        var maid = AnimationLab.ownedSceneMaid(player);
        maid.getChatBubbleManager()
            .clear();
        if (action.equals("clear")) maid.getMaidInventory()
            .setInventorySlotContents(5, null);
        else {
            require(action.equals("reimu") || action.equals("winefox"), "Unknown visual fixture");
            maid.setModelId(action.equals("reimu") ? "touhou_little_maid:hakurei_reimu" : "geckolib:winefox_hanfu");
            maid.setSitting(false);
            maid.setPose(MaidPose.SLEEPING, false);
            maid.getMaidInventory()
                .setInventorySlotContents(5, new ItemStack(Items.diamond_sword));
            maid.getConfigManager()
                .setShowBackItem(true);
            maid.getConfigManager()
                .setShowChatBubble(true);
            long now = System.currentTimeMillis();
            var bubbles = maid.getChatBubbleManager()
                .getChatBubbleDataCollection();
            bubbles.put(
                now + 120000,
                com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData
                    .type2("(๑>◡<๑)"));
            bubbles.put(
                now + 120001,
                com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.EmojiChatBubbleData.create());
            maid.getChatBubbleManager()
                .forceUpdateChatBubble();
        }
        TLMNotHard.LOG.info(
            "TLMNH_VISUAL_FIXTURE action={} bubbles={} back={}",
            action,
            maid.getChatBubbleManager()
                .getChatBubbleDataCollection()
                .size(),
            maid.getBackpackShowItem() != null);
    }

    public static void openDefaultGui(EntityPlayerMP player) {
        var maid = AnimationLab.ownedSceneMaid(player);
        var mode = player.theItemInWorldManager.getGameType();
        int slot = player.inventory.currentItem;
        var held = player.inventory.getStackInSlot(slot);
        boolean sneaking = player.isSneaking();
        try {
            player.theItemInWorldManager.setGameType(GameType.CREATIVE);
            player.inventory.setInventorySlotContents(slot, null);
            player.setSneaking(false);
            require(
                maid.interact(player)
                    && player.openContainer instanceof com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer,
                "actual owner right-click opens original main");
        } finally {
            player.inventory.setInventorySlotContents(slot, held);
            player.setSneaking(sneaking);
            player.theItemInWorldManager.setGameType(mode);
            player.sendPlayerAbilities();
        }
        AnimationLab.verifyObserver(player);
        TLMNotHard.LOG
            .info("TLMNH_DEFAULT_GUI_TEST_PASS route=actual-owned-interact original-main=true observer=restored");
    }

    public static void runEffects(WorldServer world) {
        try (var f = new Fixture(world)) {
            f.maid.addPotionEffect(new PotionEffect(Potion.moveSpeed.id, 72000, 1));
            f.maid.addPotionEffect(new PotionEffect(Potion.moveSlowdown.id, 900, 0));
            var message = new com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage(f.maid, 7);
            require(
                message.effects()
                    .size() == 2,
                "native effects snapshot contains both effects");
            require(
                message.effects()
                    .stream()
                    .anyMatch(
                        e -> e.descriptionId.equals(Potion.moveSpeed.getName()) && e.duration == 72000
                            && e.amplifier == 1),
                "full integer duration retained");
            f.maid.removePotionEffect(Potion.moveSpeed.id);
            f.maid.removePotionEffect(Potion.moveSlowdown.id);
            require(
                new com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage(f.maid, 7).effects()
                    .isEmpty(),
                "empty native snapshot clears display");
            TLMNotHard.LOG.info(
                "TLMNH_EFFECTS_TEST_PASS native-snapshot=2 duration=untruncated clear=empty server-client-symbols=not-loaded");
        }
    }

    public static void setGuiEffectFixture(EntityPlayerMP player, String action) {
        var maid = AnimationLab.ownedSceneMaid(player);
        if (action.equals("add")) {
            maid.addPotionEffect(new PotionEffect(Potion.moveSpeed.id, 72000, 1));
            maid.addPotionEffect(new PotionEffect(Potion.moveSlowdown.id, 900, 0));
        } else if (action.equals("clear")) {
            maid.removePotionEffect(Potion.moveSpeed.id);
            maid.removePotionEffect(Potion.moveSlowdown.id);
        } else throw new IllegalArgumentException("Unknown GUI effect fixture action");
        TLMNotHard.LOG.info(
            "TLMNH_GUI_EFFECTS_FIXTURE action={} actual_effects={}",
            action,
            maid.getActivePotionEffects()
                .size());
    }

    public static void runPickupAndTraversal(WorldServer world) {
        try (var f = new Fixture(world)) {
            pickupBatch(f);
            traversalBatch(f);
            TLMNotHard.LOG.info(
                "TLMNH_PICKUP_TRAVERSAL_TEST_PASS inventory=partial-full-simulated events=cancelled nbt=preserved xp=type-and-mending contact=actual-tick gates=path-open-close iron=denied ladder=up-down-toggle world=restored benchmark=unverified");
        }
    }

    private static PanicRuntime pendingPanic;

    public static void startPanicRuntime(WorldServer world) {
        require(pendingPanic == null, "one panic runtime");
        var probe = new PanicRuntime(world);
        pendingPanic = probe;
        try {
            probe.begin();
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(probe);
        } catch (RuntimeException | Error failure) {
            probe.finish(false, failure.toString());
            throw failure;
        }
    }

    public static void cancelPanicRuntime() {
        if (pendingPanic != null) pendingPanic.finish(false, "server stopping");
    }

    /** 三组库存边界夹具，调用实际RC2物品与女仆控制器，不冒充自然tick/客户端验收 */
    public static void runMeals(WorldServer world, String group) {
        try (var f = new Fixture(world)) {
            require(world.spawnEntityInWorld(f.maid), "meal actor registered");
            f.maid.getBrainCoordinator()
                .setSuspended(false);
            var maid = f.maid;
            var inv = maid.getMaidInventory();
            var meal = maid.getMealController();
            if ("complete".equals(group)) {
                var sword = new ItemStack(Items.iron_sword);
                var spare = new ItemStack(Items.diamond, 3);
                inv.setInventorySlotContents(6, sword);
                inv.setInventorySlotContents(7, spare);
                inv.setInventorySlotContents(0, new ItemStack(Items.mushroom_stew));
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating() && maid.hasPose(MaidPose.USING_OFFHAND), "backpack food in offhand");
                require(inv.getStackInSlot(0) == spare && maid.getHealth() > 2, "swap conserves old hand and heals");
                for (int i = 0; i < 32; i++) meal.tick();
                require(
                    !meal.isEating() && inv.getStackInSlot(6) == sword && inv.getStackInSlot(7) == spare,
                    "hands restored");
                require(count(inv, Items.mushroom_stew) == 0 && count(inv, Items.bowl) == 1, "one soup one bowl");
                require(
                    maid.getItemUseState()
                        .peek() == null && !maid.hasPose(MaidPose.USING_ITEM),
                    "food lease released");
            } else if ("cancel".equals(group)) {
                var sword = new ItemStack(Items.iron_sword);
                var oldOff = new ItemStack(Items.diamond, 2);
                inv.setInventorySlotContents(6, sword);
                inv.setInventorySlotContents(7, oldOff);
                inv.setInventorySlotContents(0, new ItemStack(Items.apple, 4));
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating(), "cancel eating started");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                require(
                    !meal.isEating() && inv.getStackInSlot(7) == oldOff && count(inv, Items.apple) == 4,
                    "pause restores without consuming");
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                meal.load(new NBTTagCompound());
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating(), "save eating started");
                var saved = new NBTTagCompound();
                maid.writeEntityToNBT(saved);
                require(!meal.isEating() && inv.getStackInSlot(7) == oldOff, "save restores before serializing");
                var restored = f.newMaid();
                restored.readEntityFromNBT(saved);
                require(
                    count(restored.getMaidInventory(), Items.apple) == 4
                        && count(restored.getMaidInventory(), Items.diamond) == 2,
                    "save/load conserves");
                meal.load(new NBTTagCompound());
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating(), "mutation eating started");
                var replacement = new ItemStack(Items.emerald);
                f.owner.inventory.setInventorySlotContents(20, inv.getStackInSlot(7));
                inv.setInventorySlotContents(7, replacement);
                meal.tick();
                require(
                    !meal.isEating() && inv.getStackInSlot(7) == replacement && inv.getStackInSlot(0) == oldOff,
                    "menu changes not overwritten");
                require(f.owner.inventory.getStackInSlot(20).stackSize == 4, "removed food retained by viewer");
            } else if ("rc2".equals(group)) {
                Item item = (Item) Item.itemRegistry.getObject("gregtech:gt.metaitem.02");
                require(item != null, "actual RC2 GT item exists");
                var drink = new ItemStack(item, 2, 32000);
                var food = com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile.inspect(drink);
                require(food != null && food.container() != null, "GT coffee nutrition and container");
                var empty = food.container();
                for (int slot = 0; slot < 6; slot++)
                    inv.setInventorySlotContents(slot, new ItemStack(Items.diamond, 64));
                inv.setInventorySlotContents(7, new ItemStack(Items.diamond, 64));
                inv.setInventorySlotContents(6, drink);
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating() && !maid.hasPose(MaidPose.USING_OFFHAND), "existing main hand drink");
                for (int i = 0; i < food.duration; i++) meal.tick();
                require(drink.stackSize == 1 && inv.getStackInSlot(6) == drink, "one GT drink consumed");
                int dropped = 0;
                for (Object entity : world.loadedEntityList)
                    if (entity instanceof EntityItem drop && !f.originalEntities.contains(drop)) {
                        var stack = drop.getEntityItem();
                        if (stack.isItemEqual(empty) && ItemStack.areItemStackTagsEqual(stack, empty))
                            dropped += stack.stackSize;
                    }
                require(
                    count(inv, empty.getItem(), empty.getItemDamage()) == 0 && dropped == empty.stackSize,
                    "full inventory returns exactly one real dropped thermos");
                // 必定生效的原版附魔金苹果，检查NPC药效路径且不借用玩家回调
                meal.load(new NBTTagCompound());
                inv.setInventorySlotContents(6, new ItemStack(Items.golden_apple, 1, 1));
                maid.setHealth(2);
                meal.tick();
                require(meal.isEating(), "golden apple started");
                for (int i = 0; i < 32; i++) meal.tick();
                require(
                    maid.isPotionActive(Potion.field_76444_x) && maid.isPotionActive(Potion.regeneration)
                        && maid.isPotionActive(Potion.resistance)
                        && maid.isPotionActive(Potion.fireResistance),
                    "golden apple effects on NPC");
                require(
                    com.tianluoqaq.tlmnh.common.config.MaidMealConfig.blocked(new ItemStack(Items.fish, 1, 3), true)
                        && !com.tianluoqaq.tlmnh.common.config.MaidMealConfig.blocked(new ItemStack(Items.fish), true),
                    "only pufferfish blocked");
            } else throw new IllegalArgumentException("Unknown meal group: " + group);
            TLMNotHard.LOG.info(
                "TLMNH_MEAL_TEST_PASS group={} controller-calls=actual-RC2 natural-ticks=unverified client=unverified",
                group);
        }
    }

    private static int count(com.tianluoqaq.tlmnh.common.inventory.MaidInventory inv, Item item) {
        return count(inv, item, -1);
    }

    private static int count(com.tianluoqaq.tlmnh.common.inventory.MaidInventory inv, Item item, int metadata) {
        int count = 0;
        for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
            var stack = inv.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item && (metadata < 0 || stack.getItemDamage() == metadata))
                count += stack.stackSize;
        }
        return count;
    }

    public static void runFavorability(WorldServer world, String group) {
        try (var f = new Fixture(world)) {
            require(world.spawnEntityInWorld(f.maid), "favorability actor registered");
            var maid = f.maid;
            var manager = maid.getFavorabilityManager();
            if ("levels".equals(group)) {
                int[] points = { 64, 192, 384 }, health = { 30, 40, 80 }, damage = { 3, 4, 6 };
                for (int i = 0; i < points.length; i++) {
                    maid.setFavorability(points[i] - 1);
                    manager.refreshAttributes();
                    maid.setHealth(2);
                    manager.add(1);
                    require(
                        manager.getLevel() == i + 1 && maid.getMaxHealth() == health[i]
                            && maid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.attackDamage)
                                .getBaseValue() == damage[i]
                            && maid.getHealth() == 2,
                        "level changes base stats without free heal: " + i);
                }
                manager.add(Integer.MAX_VALUE);
                manager.reduce(999);
                require(maid.getFavorability() == 384, "max clamp and normal reduction level floor");
                maid.setHealth(80);
                manager.reduceWithoutLevel(384);
                require(
                    manager.getLevel() == 0 && maid.getMaxHealth() == 20 && maid.getHealth() == 20,
                    "explicit level reduction clamps health");
                maid.setFavorability(65);
                manager.reduce(2);
                require(maid.getFavorability() == 64, "ordinary loss cannot cross earned level");
            } else if ("care".equals(group)) {
                maid.setHealth(2);
                maid.onMaidStartSleeping();
                require(maid.getHealth() == 20 && maid.getFavorability() == 2, "sleep heals and grants two points");
                maid.setHealth(3);
                maid.onMaidStartSleeping();
                require(
                    maid.getHealth() == 20 && maid.getFavorability() == 2,
                    "sleep heal independent from reward cooldown");
                manager.apply(com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type.STEAL_EDIBLE_BLOCK);
                require(maid.getFavorability() == 3, "steal independent cooldown");
                maid.getMaidInventory()
                    .setInventorySlotContents(6, new ItemStack(Items.bread, 2));
                maid.getBrainCoordinator()
                    .setSuspended(false);
                maid.getMealController()
                    .tick();
                require(
                    maid.getMealController()
                        .isEating() && !manager.canAdd("WorkMeal")
                        && maid.getFavorability() >= 3
                        && maid.getFavorability() <= 4,
                    "work meal uses same manager even zero point");
                var saved = new NBTTagCompound();
                maid.writeEntityToNBT(saved);
                require(
                    !maid.getMealController()
                        .isEating()
                        && saved.getCompoundTag("FavorabilityManagerCounter")
                            .getInteger("Sleep") == 24000
                        && saved.getCompoundTag("FavorabilityManagerCounter")
                            .getInteger("StealEdibleBlock") == 3600
                        && saved.getCompoundTag("FavorabilityManagerCounter")
                            .getInteger("WorkMeal") == 3600,
                    "cooldowns persisted independently");
                for (int i = 0; i < 3599; i++) manager.tick();
                require(
                    !manager.canAdd("StealEdibleBlock") && !manager.canAdd("WorkMeal"),
                    "one tick before meal cooldown");
                manager.tick();
                require(
                    manager.canAdd("StealEdibleBlock") && manager.canAdd("WorkMeal") && !manager.canAdd("Sleep"),
                    "cooldown boundary distinct from sleep");
            } else if ("persistence".equals(group)) {
                var legacy = new NBTTagCompound();
                maid.writeToNBT(legacy);
                legacy.setInteger("MaidFavorability", 192);
                legacy.setInteger("TLMNHWorkMealCooldown", 77);
                var counters = new NBTTagCompound();
                counters.setInteger("FutureReward", 1234);
                legacy.setTag("FavorabilityManagerCounter", counters);
                legacy.getCompoundTag("ForgeData")
                    .setLong("TLMNHStealEdibleRewardAt", world.getTotalWorldTime() + 20);
                // ForgeData可能原先不存在，显式挂回真实完整NBT
                var forgeData = legacy.getCompoundTag("ForgeData");
                forgeData.setLong("TLMNHStealEdibleRewardAt", world.getTotalWorldTime() + 20);
                legacy.setTag("ForgeData", forgeData);
                var restored = f.newMaid();
                restored.readFromNBT(legacy);
                require(
                    restored.getMaxHealth() == 40 && restored.getFavorabilityManager()
                        .getLevel() == 2,
                    "legacy point/stat mismatch repaired");
                var migrated = new NBTTagCompound();
                restored.writeToNBT(migrated);
                var after = migrated.getCompoundTag("FavorabilityManagerCounter");
                require(
                    after.getInteger("WorkMeal") == 77 && after.getInteger("StealEdibleBlock") == 20
                        && after.getInteger("FutureReward") == 1234
                        && !migrated.hasKey("TLMNHWorkMealCooldown"),
                    "legacy cooldown migration and future counter preserved");
                boolean fire = world.getGameRules()
                    .getGameRuleBooleanValue("doFireTick");
                try {
                    world.getGameRules()
                        .setOrCreateGameRule("doFireTick", "false");
                    var bolt = new net.minecraft.entity.effect.EntityLightningBolt(
                        world,
                        restored.posX,
                        restored.posY,
                        restored.posZ);
                    restored.setHealth(restored.getMaxHealth());
                    restored.onStruckByLightning(bolt);
                    float maximum = restored.getMaxHealth();
                    restored.onStruckByLightning(bolt);
                    require(
                        restored.isStruckByLightning() && maximum == 60 && restored.getMaxHealth() == maximum,
                        "actual lightning callback grants twenty only once");
                    restored.getFavorabilityManager()
                        .max();
                    require(restored.getMaxHealth() == 100, "level three retains lightning bonus");
                    var save = new NBTTagCompound();
                    restored.writeToNBT(save);
                    var reloaded = f.newMaid();
                    reloaded.readFromNBT(save);
                    require(
                        reloaded.getMaxHealth() == 100 && reloaded.isStruckByLightning(),
                        "lightning and level survive reload");
                } finally {
                    world.getGameRules()
                        .setOrCreateGameRule("doFireTick", Boolean.toString(fire));
                }
            } else throw new IllegalArgumentException("Unknown favorability group: " + group);
            TLMNotHard.LOG.info(
                "TLMNH_FAVORABILITY_TEST_PASS group={} callbacks=actual-runtime natural-ticks=unverified client=unverified",
                group);
        }
    }

    public static void runSelfCare(WorldServer world) {
        try (var f = new Fixture(world)) {
            world.getChunkFromBlockCoords((int) Math.floor(f.maid.posX), (int) Math.floor(f.maid.posZ));
            require(world.spawnEntityInWorld(f.maid), "registered self-care maid");
            var spy = new HealSpy(f.maid);
            MinecraftForge.EVENT_BUS.register(spy);
            try {
                float before = Math.max(1, f.maid.getMaxHealth() - 2);
                f.maid.setHealth(before);
                int draws = 0;
                while (spy.count == 0 && draws < 10000) {
                    com.tianluoqaq.tlmnh.common.interaction.MaidSelfCare.tick(f.maid);
                    draws++;
                }
                require(
                    spy.count == 1 && spy.amount == 1
                        && !spy.cancelled
                        && f.maid.getHealth() == Math.min(f.maid.getMaxHealth(), before + 1),
                    "native passive heal actual=" + f.maid.getHealth()
                        + " before="
                        + before
                        + " events="
                        + spy.count
                        + " amount="
                        + spy.amount
                        + " cancelled="
                        + spy.cancelled
                        + " draws="
                        + draws
                        + " rng="
                        + f.maid.getRNG()
                            .getClass()
                            .getName());
                f.maid.setHealth(f.maid.getMaxHealth());
                com.tianluoqaq.tlmnh.common.interaction.MaidSelfCare.tick(f.maid);
                require(
                    f.maid.getHealth() == f.maid.getMaxHealth() && spy.count == 1,
                    "full health no extra heal event");
                f.maid.setHealth(0);
                com.tianluoqaq.tlmnh.common.interaction.MaidSelfCare.tick(f.maid);
                require(f.maid.getHealth() == 0, "no passive resurrection");
                TLMNotHard.LOG.info(
                    "TLMNH_SELF_CARE_TEST_PASS one-health=restored full-health=no-extra-event dead=no-resurrection draws={} rng={} particles=client-unverified",
                    draws,
                    f.maid.getRNG()
                        .getClass()
                        .getName());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(spy);
            }
        }
    }

    public static final class HealSpy {

        private final EntityMaid maid;
        private int count;
        private float amount;
        private boolean cancelled;

        HealSpy(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST, receiveCanceled = true)
        public void heal(net.minecraftforge.event.entity.living.LivingHealEvent event) {
            if (event.entityLiving == maid) {
                count++;
                amount = event.amount;
                cancelled = event.isCanceled();
            }
        }
    }

    /** 自然实体tick：坐下遇苦力怕逃离、攻击任务不慌乱、取消/恢复与威胁移除 */
    public static final class PanicRuntime {

        private final Fixture f;
        private final java.util.Map<net.minecraft.util.ChunkCoordinates, net.minecraft.block.Block> blocks = new java.util.LinkedHashMap<>();
        private final java.util.Map<net.minecraft.util.ChunkCoordinates, Integer> metadata = new java.util.HashMap<>();
        private net.minecraft.entity.monster.EntityCreeper creeper;
        private final net.minecraft.world.EnumDifficulty difficulty;
        private int ticks, phase, phaseStart;
        private boolean finished;
        private double x, z, initialDistance;

        PanicRuntime(WorldServer world) {
            f = new Fixture(world);
            difficulty = world.difficultySetting;
        }

        private void begin() {
            f.world.difficultySetting = net.minecraft.world.EnumDifficulty.NORMAL;
            int bx = (int) Math.floor(f.maid.posX), bz = (int) Math.floor(f.maid.posZ);
            x = bx + .5;
            z = bz + .5;
            for (int cx = (bx - 24) >> 4; cx <= (bx + 24) >> 4; cx++)
                for (int cz = (bz - 24) >> 4; cz <= (bz + 24) >> 4; cz++) f.world.getChunkFromChunkCoords(cx, cz);
            for (int px = bx - 16; px <= bx + 16; px++)
                for (int pz = bz - 16; pz <= bz + 16; pz++) for (int py = 99; py <= 107; py++) {
                    var point = new net.minecraft.util.ChunkCoordinates(px, py, pz);
                    blocks.put(point, f.world.getBlock(px, py, pz));
                    metadata.put(point, f.world.getBlockMetadata(px, py, pz));
                    f.world.setBlock(
                        px,
                        py,
                        pz,
                        py == 99 ? net.minecraft.init.Blocks.glowstone : net.minecraft.init.Blocks.air,
                        0,
                        2);
                }
            f.maid.setPosition(x, 100, z);
            f.maid.onGround = true;
            f.maid.setSitting(true);
            f.maid.setSchedule(com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.ALL);
            creeper = new net.minecraft.entity.monster.EntityCreeper(f.world);
            creeper.tasks.taskEntries.clear();
            creeper.targetTasks.taskEntries.clear();
            creeper.func_110163_bv();
            creeper.setPosition(x + 4, 100, z);
            creeper.onGround = true;
            require(
                f.world.spawnEntityInWorld(creeper) && f.world.spawnEntityInWorld(f.maid),
                "registered panic actors");
            initialDistance = f.maid.getDistanceSqToEntity(creeper);
            f.maid.getBrainCoordinator()
                .setSuspended(false);
            TLMNotHard.LOG
                .info("TLMNH_PANIC_RUNTIME_STARTED input=natural-world-ticks stationary-registered-creeper=true");
        }

        @SubscribeEvent
        public void tick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (finished || event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
            try {
                ticks++;
                var brain = f.maid.getBrainCoordinator();
                var controller = brain.getPanicController();
                require(
                    ticks <= 300,
                    "panic timeout phase=" + phase
                        + " status="
                        + controller.getStatus()
                        + " distance="
                        + f.maid.getDistanceSqToEntity(creeper));
                if (phase == 0 && f.maid.getDistanceSqToEntity(creeper) > initialDistance + 20) {
                    require(!f.maid.isSitting(), "panic stands from waiting");
                    require(
                        brain.getActionRunner()
                            .getOwner() == com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner.PANIC,
                        "panic owns actual motion");
                    TLMNotHard.LOG.info(
                        "TLMNH_PANIC_PHASE_PASS phase=escape ticks={} distanceSq={}",
                        ticks,
                        f.maid.getDistanceSqToEntity(creeper));
                    f.maid.setTask(com.tianluoqaq.tlmnh.common.entity.MaidTask.ATTACK);
                    f.maid.setPosition(x, 100, z);
                    f.maid.motionX = f.maid.motionZ = 0;
                    f.maid.setSitting(true);
                    phase = 1;
                    phaseStart = ticks;
                } else if (phase == 1 && ticks - phaseStart >= 15) {
                    require(
                        f.maid.isSitting() && controller.getThreat() == null
                            && brain.getActionRunner()
                                .getOwner() != com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner.PANIC,
                        "attack task disables panic");
                    f.maid.setTask(com.tianluoqaq.tlmnh.common.entity.MaidTask.IDLE);
                    phase = 2;
                    phaseStart = ticks;
                } else if (phase == 2 && f.maid.getDistanceSqToEntity(creeper) > initialDistance + 20) {
                    brain.setSuspended(true);
                    require(
                        f.maid.getNavigator()
                            .noPath()
                            && brain.getActionRunner()
                                .getOwner() == null
                            && controller.getThreat() == null,
                        "suspension releases owned navigation");
                    phase = 3;
                    phaseStart = ticks;
                } else if (phase == 3 && ticks - phaseStart >= 5) {
                    f.maid.setPosition(x, 100, z);
                    f.maid.motionX = f.maid.motionZ = 0;
                    f.maid.onGround = true;
                    brain.setSuspended(false);
                    phase = 4;
                    phaseStart = ticks;
                } else if (phase == 4 && f.maid.getDistanceSqToEntity(creeper) > initialDistance + 20) {
                    f.world.removeEntity(creeper);
                    phase = 5;
                    phaseStart = ticks;
                } else if (phase == 5 && ticks - phaseStart >= 3) {
                    require(
                        controller.getThreat() == null && brain.getActionRunner()
                            .getOwner() != com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner.PANIC,
                        "removed threat releases panic");
                    finish(true, "escape;attack-disabled;cancel-resume;threat-removed");
                }
            } catch (RuntimeException | Error error) {
                finish(false, error.toString());
            }
        }

        private void finish(boolean success, String detail) {
            if (finished) return;
            finished = true;
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            try {
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                if (creeper != null) f.world.removeEntity(creeper);
                f.close();
            } finally {
                for (var entry : blocks.entrySet()) {
                    var p = entry.getKey();
                    f.world.setBlock(p.posX, p.posY, p.posZ, entry.getValue(), metadata.get(p), 2);
                }
                f.world.difficultySetting = difficulty;
                if (pendingPanic == this) pendingPanic = null;
            }
            TLMNotHard.LOG.info(
                "TLMNH_PANIC_RUNTIME_TEST_{} ticks={} detail={} benchmark=unverified",
                success ? "PASS" : "FAIL",
                ticks,
                detail);
        }
    }

    private static PickupRuntime pendingPickup;

    public static void startPickupRuntime(WorldServer world) {
        require(pendingPickup == null, "one active pickup runtime fixture");
        var probe = new PickupRuntime(world);
        pendingPickup = probe;
        try {
            probe.begin();
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(probe);
        } catch (RuntimeException | Error failure) {
            probe.finish(false, failure.toString());
            throw failure;
        }
    }

    /** 两个真实世界 tick 场景：接近收取、当前动作被坐下指令取消 */
    public static final class PickupRuntime {

        private final Fixture fixture;
        private final java.util.Map<net.minecraft.util.ChunkCoordinates, net.minecraft.block.Block> blocks = new java.util.LinkedHashMap<>();
        private final java.util.Map<net.minecraft.util.ChunkCoordinates, Integer> metadata = new java.util.HashMap<>();
        private EntityItem item;
        private int ticks, phase;
        private double startX, startZ, stoppedX, stoppedZ;
        private boolean finished;

        PickupRuntime(WorldServer world) {
            fixture = new Fixture(world);
        }

        private void begin() {
            var f = fixture;
            int x = (int) Math.floor(f.maid.posX), z = (int) Math.floor(f.maid.posZ);
            for (int cx = (x - 24) >> 4; cx <= (x + 24) >> 4; cx++)
                for (int cz = (z - 24) >> 4; cz <= (z + 24) >> 4; cz++) f.world.getChunkFromChunkCoords(cx, cz);
            for (int bx = x - 2; bx <= x + 7; bx++)
                for (int bz = z - 2; bz <= z + 2; bz++) for (int by = 99; by <= 102; by++) {
                    var p = new net.minecraft.util.ChunkCoordinates(bx, by, bz);
                    blocks.put(p, f.world.getBlock(bx, by, bz));
                    metadata.put(p, f.world.getBlockMetadata(bx, by, bz));
                    f.world.setBlock(
                        bx,
                        by,
                        bz,
                        by == 99 ? net.minecraft.init.Blocks.glowstone : net.minecraft.init.Blocks.air,
                        0,
                        2);
                }
            f.maid.setPosition(x + 0.5, 100, z + 0.5);
            f.maid.onGround = true;
            f.maid.setSchedule(com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.ALL);
            require(
                f.maid.getHomeArea()
                    .setEnabled(true),
                "pickup home enabled");
            require(f.world.spawnEntityInWorld(f.maid), "pickup actor registered");
            item = new EntityItem(f.world, x + 3.5, 100.25, z + 0.5, new ItemStack(Items.apple, 3));
            item.delayBeforeCanPickup = 0;
            require(f.world.spawnEntityInWorld(item), "pickup target registered");
            startX = f.maid.posX;
            startZ = f.maid.posZ;
            f.maid.getBrainCoordinator()
                .setSuspended(false);
            TLMNotHard.LOG.info("TLMNH_PICKUP_RUNTIME_STARTED owner=offline-home input=natural-world-ticks");
        }

        @SubscribeEvent
        public void onTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (finished || event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
            try {
                ticks++;
                var maid = fixture.maid;
                if (ticks > 220) throw new IllegalStateException(
                    "pickup runtime exceeded 220 ticks; phase=" + phase
                        + ";target="
                        + maid.getBrainCoordinator()
                            .getPickupController()
                            .getTarget());
                if (phase == 0 && item.isDead) {
                    int apples = 0;
                    for (int i = 0; i <= com.tianluoqaq.tlmnh.common.inventory.MaidInventory.OFF_HAND; i++) {
                        var stack = maid.getMaidInventory()
                            .getStackInSlot(i);
                        if (stack != null && stack.getItem() == Items.apple) apples += stack.stackSize;
                    }
                    require(
                        apples == 3 && maid.getDistanceSq(startX, maid.posY, startZ) > 0.25,
                        "natural approach and conserved pickup");
                    item = new EntityItem(fixture.world, startX + 5, 100.25, startZ, new ItemStack(Items.stick));
                    item.delayBeforeCanPickup = 0;
                    require(fixture.world.spawnEntityInWorld(item), "second target registered");
                    phase = 1;
                } else if (phase == 1 && maid.getBrainCoordinator()
                    .getPickupController()
                    .getTarget() == item) {
                        require(!item.isDead, "target not yet collected before cancellation");
                        maid.setSitting(true);
                        stoppedX = maid.posX;
                        stoppedZ = maid.posZ;
                        phase = 2;
                    } else if (phase == 2 && maid.getBrainCoordinator()
                        .getPickupController()
                        .getTarget() == null) {
                            require(
                                maid.getNavigator()
                                    .noPath() && !item.isDead,
                                "sit cancels owned path without collecting distant target");
                            require(
                                maid.getDistanceSq(stoppedX, maid.posY, stoppedZ) < 0.25,
                                "sit stops pickup movement");
                            finish(true, "");
                        }
            } catch (RuntimeException | Error failure) {
                finish(false, failure.toString());
            }
        }

        private void finish(boolean passed, String reason) {
            if (finished) return;
            finished = true;
            try {
                fixture.maid.getBrainCoordinator()
                    .setSuspended(true);
                if (item != null) fixture.world.removeEntity(item);
                fixture.close();
                var points = new ArrayList<>(blocks.keySet());
                Collections.reverse(points);
                for (var p : points) fixture.world.setBlock(p.posX, p.posY, p.posZ, blocks.get(p), metadata.get(p), 2);
            } finally {
                cpw.mods.fml.common.FMLCommonHandler.instance()
                    .bus()
                    .unregister(this);
                pendingPickup = null;
            }
            TLMNotHard.LOG.info(
                "TLMNH_PICKUP_RUNTIME_{} ticks={} phase={} reason={} full_benchmark=false",
                passed ? "PASS" : "FAIL",
                ticks,
                phase,
                reason);
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
            TLMNotHard.LOG.info(
                "TLMNH_DOOR_FIXTURE block={} upper={} meta={}/{} wood={} enabled={} tame={} sitting={} riding={} leash={} sleep={}",
                world.getBlock(x + 1, y, z),
                world.getBlock(x + 1, y + 1, z),
                world.getBlockMetadata(x + 1, y, z),
                world.getBlockMetadata(x + 1, y + 1, z),
                world.getBlock(x + 1, y, z)
                    .getMaterial() == net.minecraft.block.material.Material.wood,
                config.isOpenDoor(),
                maid.isTamed(),
                maid.isSitting(),
                maid.ridingEntity,
                maid.getLeashed(),
                maid.hasPose(MaidPose.SLEEPING));
            maid.getNavigator()
                .setPath(
                    new net.minecraft.pathfinding.PathEntity(
                        new net.minecraft.pathfinding.PathPoint[] {
                            new net.minecraft.pathfinding.PathPoint(x + 1, y, z) }),
                    1);
            traversal.tick();
            require(
                com.tianluoqaq.tlmnh.common.ai.Rc2DoorState.isOpenOrOpening(
                    (net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.wooden_door,
                    world,
                    x + 1,
                    y,
                    z),
                "wood door opens on current path; actual=" + world.getBlock(x + 1, y, z)
                    + ";meta="
                    + world.getBlockMetadata(x + 1, y, z)
                    + ";path="
                    + maid.getNavigator()
                        .getPath());
            maid.setPosition(x + 4.5, y, z + 0.5);
            maid.getNavigator()
                .clearPathEntity();
            var animated = world.getTileEntity(x + 1, y, z);
            if (animated != null && animated.getClass()
                .getName()
                .startsWith("net.malisis.doors.")) {
                traversal.tick();
                require(
                    com.tianluoqaq.tlmnh.common.ai.Rc2DoorState.isOpenOrOpening(
                        (net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.wooden_door,
                        world,
                        x + 1,
                        y,
                        z),
                    "pending open animation retained for later close");
                // 本同步夹具用已读存档接口给定动画完成态，不在服务端命令中阻塞等待动画计时
                var completed = new NBTTagCompound();
                animated.writeToNBT(completed);
                completed.setInteger("state", 2);
                animated.readFromNBT(completed);
            }
            traversal.tick();
            require(
                !com.tianluoqaq.tlmnh.common.ai.Rc2DoorState.isOpenOrOpening(
                    (net.minecraft.block.BlockDoor) net.minecraft.init.Blocks.wooden_door,
                    world,
                    x + 1,
                    y,
                    z),
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
            var up = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery.create(maid, x, y + 3, z, 8, true, true, false, true);
            require(up != null && up.getFinalPathPoint().yCoord == y + 3, "existing ladder column has vertical route");
            maid.getNavigator()
                .setPath(up, 1);
            traversal.tick();
            maid.moveEntityWithHeading(0, 0);
            require(maid.posY > y, "ladder ascent reaches native movement");
            maid.setPosition(x + 0.5, y + 3, z + 0.5);
            maid.motionX = maid.motionY = maid.motionZ = 0;
            var down = com.tianluoqaq.tlmnh.common.ai.MaidPathQuery.create(maid, x, y, z, 8, true, true, false, true);
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
