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
