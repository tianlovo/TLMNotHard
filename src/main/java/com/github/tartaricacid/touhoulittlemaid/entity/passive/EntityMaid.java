/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/EntityMaid.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 entity, six-slot inventory, equipment and versioned persistence
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.MaidMeleeAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidHomeArea;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

/** 原实体的核心状态移植，渲染、交互和行为由独立适配模块接入 */
public class EntityMaid extends EntityTameable implements cpw.mods.fml.common.registry.IEntityAdditionalSpawnData {

    public static final String DEFAULT_MODEL_ID = "touhou_little_maid:hakurei_reimu";
    public static final String MODEL_ID_TAG = "ModelId";
    public static final String MAID_INVENTORY_TAG = "MaidInventory";
    public static final String EXPERIENCE_TAG = "MaidExperience";
    public static final int SAVE_VERSION = 1;
    private static final int DATA_MODEL = 20;
    private static final int DATA_TASK = 21;
    private static final int DATA_SCHEDULE = 22;
    private static final int DATA_EXPERIENCE = 23;
    private static final int DATA_HUNGER = 24;
    private static final int DATA_FAVORABILITY = 25;
    private static final int DATA_FLAGS = 26;
    private static final int DATA_OFFHAND = 27;
    private static final int DATA_POSE = 28;
    // 29 由 MaidHomeArea 使用，声音占用 30
    private static final int DATA_SOUND_PACK = 30;
    public static final String SOUND_PACK_ID_TAG = "SoundPackId";
    private static final int FLAG_PICKUP_ITEMS = 1;
    private static final int FLAG_PICKUP_EXPERIENCE = 2;
    private static final int FLAG_SILENT = 4;
    private static final String OFFHAND_TAG = "TLMNHOffhand";

    private final MaidConfigManager configManager = new MaidConfigManager(dataWatcher);
    private final com.tianluoqaq.tlmnh.common.sound.MaidWorldSoundState worldSounds = new com.tianluoqaq.tlmnh.common.sound.MaidWorldSoundState(
        this);
    private final MaidInventory inventory = new MaidInventory(this);
    private final com.tianluoqaq.tlmnh.common.inventory.MaidPickupService pickup = new com.tianluoqaq.tlmnh.common.inventory.MaidPickupService(
        this);
    private final MaidHomeArea home = new MaidHomeArea(this);
    private final MaidItemUseState itemUses = new MaidItemUseState(this::syncItemUsePose);
    private final MaidShieldController shield = new MaidShieldController(this, itemUses);
    private final MaidBrainCoordinator brain = new MaidBrainCoordinator(this);
    private ItemStack offhand;
    private NBTTagCompound extraSaveData = new NBTTagCompound();

    public EntityMaid(World world) {
        super(world);
        setSize(0.6F, 1.5F);
        func_110163_bv();
        getNavigator().setCanSwim(true);
        tasks.addTask(0, brain);
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        dataWatcher.addObject(DATA_MODEL, DEFAULT_MODEL_ID);
        dataWatcher.addObject(DATA_TASK, MaidTask.IDLE.getId());
        dataWatcher.addObject(DATA_SCHEDULE, (byte) MaidSchedule.DAY.ordinal());
        dataWatcher.addObject(DATA_EXPERIENCE, 0);
        dataWatcher.addObject(DATA_HUNGER, 0);
        dataWatcher.addObject(DATA_FAVORABILITY, 0);
        dataWatcher.addObject(DATA_FLAGS, (byte) (FLAG_PICKUP_ITEMS | FLAG_PICKUP_EXPERIENCE));
        dataWatcher.addObjectByDataType(DATA_OFFHAND, 5);
        dataWatcher.addObject(DATA_POSE, (byte) 0);
        dataWatcher.addObject(DATA_SOUND_PACK, DefaultMaidSoundPack.getInitSoundPackId());
        MaidHomeArea.register(dataWatcher);
        MaidConfigManager.defineSynchedData(dataWatcher);
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.3D);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(64.0D);
        getAttributeMap().registerAttribute(SharedMonsterAttributes.attackDamage)
            .setBaseValue(2.0D);
    }

    @Override
    protected boolean isAIEnabled() {
        return true;
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (!worldObj.isRemote) {
            shield.tickAfterLivingUpdate();
            pickup.tick();
            syncOffhand();
        }
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // Forge构造事件发生在本类字段初始化之前，仍让原生伤害入口正常处理
        var previous = worldSounds == null ? null : worldSounds.beginDamage(source);
        try {
            if (shield != null && worldObj != null && !worldObj.isRemote) shield.beforeIncoming(source, amount);
            return super.attackEntityFrom(source, amount);
        } finally {
            if (worldSounds != null) worldSounds.endDamage(previous);
        }
    }

    @Override
    public void writeSpawnData(io.netty.buffer.ByteBuf buffer) {
        buffer.writeLong(getUniqueID().getMostSignificantBits())
            .writeLong(getUniqueID().getLeastSignificantBits());
    }

    @Override
    public void readSpawnData(io.netty.buffer.ByteBuf buffer) {
        entityUniqueID = new UUID(buffer.readLong(), buffer.readLong());
    }

    public MaidConfigManager getConfigManager() {
        return configManager;
    }

    public com.tianluoqaq.tlmnh.common.sound.MaidWorldSoundState getWorldSoundState() {
        return worldSounds;
    }

    public boolean isSilent() {
        return (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_SILENT) != 0;
    }

    public void setSilent(boolean silent) {
        setMaidFlag(FLAG_SILENT, silent);
    }

    @Override
    public void onEntityUpdate() {
        super.onEntityUpdate();
        if (worldSounds != null) worldSounds.tick();
    }

    @Override
    protected String getLivingSound() {
        return worldObj.isRemote || worldSounds == null ? null : worldSounds.ambient();
    }

    @Override
    protected String getHurtSound() {
        return worldObj.isRemote ? com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.hurt")
            : worldSounds == null ? null : worldSounds.hurt();
    }

    @Override
    protected String getDeathSound() {
        return worldObj.isRemote ? com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.death")
            : worldSounds == null ? null : worldSounds.death();
    }

    @Override
    public float getSoundPitch() {
        return 1 + rand.nextFloat() * 0.1F;
    }

    @Override
    public void playSound(String name, float volume, float pitch) {
        if (name == null) return;
        String prefix = "touhou_little_maid:";
        if (name.startsWith(prefix + "maid.")) {
            // 旧版受伤状态包也会在客户端调用，女仆语音只由服务端消息负责，避免重复
            String event = name.substring(prefix.length());
            if (!worldObj.isRemote && com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog.events()
                .contains(event))
                com.tianluoqaq.tlmnh.common.network.MaidSoundNetwork.send(
                    this,
                    new com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage(this, event));
            return;
        }
        if (!isSilent()) super.playSound(name, volume, pitch);
    }

    public void tryPlayMaidPickupSound() {
        if (!worldObj.isRemote) worldSounds.tryPickup();
    }

    public MaidItemUseState getItemUseState() {
        return itemUses;
    }

    public MaidShieldController getShieldController() {
        return shield;
    }

    public boolean canUseShield() {
        return shield != null && shield.canUseShield();
    }

    public boolean isBlockingWithShield() {
        return shield != null && shield.isBlocking();
    }

    private void syncItemUsePose() {
        if (worldObj == null || worldObj.isRemote) return;
        var use = itemUses.peek();
        setPose(MaidPose.USING_ITEM, use != null);
        setPose(MaidPose.USING_OFFHAND, use != null && use.getHand() == MaidItemUseState.Hand.OFF);
        setPose(MaidPose.AIMING_BOW, use != null && use.getKind() == MaidItemUseState.Kind.BOW);
    }

    @Override
    protected void updateAITick() {
        super.updateAITick();
        if (hasServerBrain()) brain.afterNativeNavigationTick();
    }

    public MaidInventory getMaidInventory() {
        return inventory;
    }

    public MaidHomeArea getHomeArea() {
        return home;
    }

    public MaidBrainCoordinator getBrainCoordinator() {
        return brain;
    }

    @Override
    public boolean attackEntityAsMob(Entity target) {
        return MaidMeleeAttack.attack(this, target);
    }

    public boolean hasPose(MaidPose pose) {
        return (dataWatcher.getWatchableObjectByte(DATA_POSE) & pose.getMask()) != 0;
    }

    public void setPose(MaidPose pose, boolean active) {
        if (worldObj.isRemote) return;
        int flags = dataWatcher.getWatchableObjectByte(DATA_POSE);
        dataWatcher.updateObject(DATA_POSE, (byte) (active ? flags | pose.getMask() : flags & ~pose.getMask()));
    }

    @Override
    public boolean interact(EntityPlayer player) {
        var held = player == null ? null : player.getCurrentEquippedItem();
        boolean nameTag = held != null && held.getItem() == Items.name_tag;
        // 返回未处理会进入ItemNameTag原生分支，拒绝时也要阻止它绕过女仆权限
        if (!MaidInteractions.canInteract(this, player)) return nameTag;
        if (!isTamed() && held != null && held.stackSize > 0 && held.getItem() == Items.cake) {
            if (!worldObj.isRemote && bindTo(player.getUniqueID())) {
                if (!player.capabilities.isCreativeMode && --held.stackSize == 0)
                    player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                getNavigator().clearPathEntity();
                setAttackTarget(null);
                worldObj.setEntityState(this, (byte) 7);
                playSound(com.github.tartaricacid.touhoulittlemaid.util.SoundUtil.name("ai.tamed"), 1, 1);
            }
            return true;
        }
        if (!isOwner(player)) return nameTag;
        // 上游主人交互仅使用主手；Backhand 仍可从实际副手槽进行野生驯服
        if (!MaidInteractions.isMainHand(player)) return nameTag;
        if (MaidInteractions.interactOwned(this, player, held)) return true;
        if (!worldObj.isRemote) {
            if (player.isSneaking() && held == null) {
                if (ridingEntity == null) {
                    setSitting(!isSitting());
                    getNavigator().clearPathEntity();
                    setAttackTarget(null);
                }
            } else {
                player.openGui(TLMNotHard.INSTANCE, CommonProxy.GUI_MAID, worldObj, getEntityId(), 0, 0);
            }
        }
        return true;
    }

    public boolean isOwner(EntityPlayer player) {
        return player != null && isTamed()
            && player.getUniqueID()
                .toString()
                .equals(func_152113_b());
    }

    /** 只允许首次绑定，交互层没有换主人入口 */
    public boolean bindTo(UUID owner) {
        if (worldObj.isRemote || owner == null || isTamed() || !func_152113_b().isEmpty()) return false;
        func_152115_b(owner.toString());
        setTamed(true);
        return true;
    }

    public String getModelId() {
        return dataWatcher.getWatchableObjectString(DATA_MODEL);
    }

    public void setModelId(String modelId) {
        if (!BuiltinModelCatalog.contains(modelId)) {
            modelId = DEFAULT_MODEL_ID;
        }
        dataWatcher.updateObject(DATA_MODEL, modelId);
    }

    public MaidTask getTask() {
        return MaidTask.fromId(dataWatcher.getWatchableObjectString(DATA_TASK));
    }

    public void setTask(MaidTask task) {
        dataWatcher.updateObject(DATA_TASK, task.getId());
        setAttackTarget(null);
        getNavigator().clearPathEntity();
        if (shield != null) shield.cancelUse("task_changed");
        if (itemUses != null) itemUses.reconcile();
    }

    public MaidSchedule getSchedule() {
        int index = dataWatcher.getWatchableObjectByte(DATA_SCHEDULE);
        return index >= 0 && index < MaidSchedule.values().length ? MaidSchedule.values()[index] : MaidSchedule.DAY;
    }

    public void setSchedule(MaidSchedule schedule) {
        dataWatcher.updateObject(DATA_SCHEDULE, (byte) schedule.ordinal());
    }

    public int getExperience() {
        return dataWatcher.getWatchableObjectInt(DATA_EXPERIENCE);
    }

    public void setExperience(int value) {
        dataWatcher.updateObject(DATA_EXPERIENCE, Math.max(0, value));
    }

    public int getHunger() {
        return dataWatcher.getWatchableObjectInt(DATA_HUNGER);
    }

    public void setHunger(int value) {
        dataWatcher.updateObject(DATA_HUNGER, Math.max(0, value));
    }

    public int getFavorability() {
        return dataWatcher.getWatchableObjectInt(DATA_FAVORABILITY);
    }

    public void setFavorability(int value) {
        dataWatcher.updateObject(DATA_FAVORABILITY, Math.max(0, value));
    }

    public boolean canPickupItems() {
        return (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_ITEMS) != 0
            && configManager.getPickupType()
                .canPickItem();
    }

    public boolean canPickupExperience() {
        return (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_EXPERIENCE) != 0
            && configManager.getPickupType()
                .canPickXp();
    }

    public boolean isPickup() {
        return (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & (FLAG_PICKUP_ITEMS | FLAG_PICKUP_EXPERIENCE)) != 0;
    }

    public void setPickup(boolean enabled) {
        setPickupItems(enabled);
        setPickupExperience(enabled);
    }

    public boolean pickupItem(net.minecraft.entity.item.EntityItem item, boolean simulate) {
        return pickup.pickupItem(item, simulate);
    }

    public void pickupXPOrb(net.minecraft.entity.item.EntityXPOrb orb) {
        pickup.pickupExperience(orb);
    }

    public boolean pickupArrow(net.minecraft.entity.projectile.EntityArrow arrow, boolean simulate) {
        return pickup.pickupArrow(arrow, simulate);
    }

    public void setPickupItems(boolean enabled) {
        setMaidFlag(FLAG_PICKUP_ITEMS, enabled);
    }

    public void setPickupExperience(boolean enabled) {
        setMaidFlag(FLAG_PICKUP_EXPERIENCE, enabled);
    }

    private void setMaidFlag(int flag, boolean enabled) {
        int flags = dataWatcher.getWatchableObjectByte(DATA_FLAGS);
        dataWatcher.updateObject(DATA_FLAGS, (byte) (enabled ? flags | flag : flags & ~flag));
    }

    public String getSoundPackId() {
        return dataWatcher.getWatchableObjectString(DATA_SOUND_PACK);
    }

    public void setSoundPackId(String id) {
        if (com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog.get(id) != null)
            dataWatcher.updateObject(DATA_SOUND_PACK, id);
    }

    public ItemStack getOffhandItem() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectItemStack(DATA_OFFHAND) : offhand;
    }

    public void setOffhandItem(ItemStack stack) {
        offhand = stack;
        dataWatcher.updateObject(DATA_OFFHAND, stack == null ? null : stack.copy());
        if (!worldObj.isRemote && shield != null) shield.equipmentChanged();
    }

    /** 使用副本比较，原地修改耐久、数量或 NBT 后仍能广播给观察者 */
    public void syncOffhand() {
        if (!worldObj.isRemote
            && !ItemStack.areItemStacksEqual(offhand, dataWatcher.getWatchableObjectItemStack(DATA_OFFHAND))) {
            dataWatcher.updateObject(DATA_OFFHAND, offhand == null ? null : offhand.copy());
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        var extension = (NBTTagCompound) extraSaveData.copy();
        extension.setInteger("Schema", Math.max(SAVE_VERSION, extension.getInteger("Schema")));
        tag.setTag("TLMNH", extension);
        tag.setString(MODEL_ID_TAG, getModelId());
        tag.setString(SOUND_PACK_ID_TAG, getSoundPackId());
        tag.setBoolean("Silent", isSilent());
        configManager.addAdditionalSaveData(tag);
        tag.setString("MaidTask", getTask().getId());
        tag.setString("MaidScheduleMode", getSchedule().name());
        tag.setInteger(EXPERIENCE_TAG, getExperience());
        tag.setInteger("MaidHunger", getHunger());
        tag.setInteger("MaidFavorability", getFavorability());
        tag.setBoolean("MaidIsPickup", isPickup());
        tag.setBoolean("PickupItems", (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_ITEMS) != 0);
        tag.setBoolean(
            "PickupExperience",
            (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_EXPERIENCE) != 0);
        home.save(tag);
        tag.setTag(MAID_INVENTORY_TAG, inventory.writeBackpack());
        if (offhand != null) tag.setTag(OFFHAND_TAG, offhand.writeToNBT(new NBTTagCompound()));
        else tag.removeTag(OFFHAND_TAG);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        boolean managed = hasServerBrain(), entered = false, successful = false;
        try {
            if (managed) {
                entered = true;
                brain.beforePersistentRead();
            }
            super.readFromNBT(tag);
            successful = true;
        } finally {
            if (entered) brain.afterPersistentRead(successful);
        }
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound tag) {
        boolean managed = hasServerBrain(), entered = false, successful = false;
        try {
            if (managed) {
                entered = true;
                brain.beforePersistentRead();
            }
            super.readEntityFromNBT(tag);
            extraSaveData = (NBTTagCompound) tag.getCompoundTag("TLMNH")
                .copy();
            if (extraSaveData.getInteger("Schema") > SAVE_VERSION) {
                TLMNotHard.LOG.warn(
                    "Maid {} has newer save schema {}; preserving extension data",
                    getUniqueID(),
                    extraSaveData.getInteger("Schema"));
            }
            setModelId(tag.hasKey(MODEL_ID_TAG, 8) ? tag.getString(MODEL_ID_TAG) : DEFAULT_MODEL_ID);
            if (tag.hasKey(SOUND_PACK_ID_TAG, 8)) setSoundPackId(tag.getString(SOUND_PACK_ID_TAG));
            setSilent(tag.getBoolean("Silent"));
            configManager.readAdditionalSaveData(tag);
            setTask(MaidTask.fromId(tag.getString("MaidTask")));
            try {
                setSchedule(MaidSchedule.valueOf(tag.getString("MaidScheduleMode")));
            } catch (IllegalArgumentException exception) {
                setSchedule(MaidSchedule.DAY);
            }
            setExperience(tag.getInteger(EXPERIENCE_TAG));
            setHunger(tag.getInteger("MaidHunger"));
            setFavorability(tag.getInteger("MaidFavorability"));
            if (tag.hasKey("PickupItems")) setPickupItems(tag.getBoolean("PickupItems"));
            if (tag.hasKey("PickupExperience")) setPickupExperience(tag.getBoolean("PickupExperience"));
            if (!tag.hasKey("PickupItems") && !tag.hasKey("PickupExperience") && tag.hasKey("MaidIsPickup", 1))
                setPickup(tag.getBoolean("MaidIsPickup"));
            inventory.readBackpack(tag.getTagList(MAID_INVENTORY_TAG, 10));
            setOffhandItem(ItemStack.loadItemStackFromNBT(tag.getCompoundTag(OFFHAND_TAG)));
            home.load(tag);
            func_110163_bv();
            successful = true;
        } finally {
            if (entered) brain.afterPersistentRead(successful);
        }
    }

    private boolean hasServerBrain() {
        return brain != null && worldObj != null && !worldObj.isRemote;
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (!worldObj.isRemote && dead) {
            MaidDeathService.preserve(this);
            brain.resetTask();
        }
    }

    @Override
    public void setDead() {
        try {
            if (hasServerBrain()) brain.onUnload();
        } finally {
            super.setDead();
        }
    }

    /** 驯服女仆交给死亡保管；野生女仆完整掉落，禁止原版随机毁损装备 */
    @Override
    protected void dropEquipment(boolean recentlyHit, int looting) {
        if (MaidDeathService.preserve(this)) return;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null) {
                entityDropItem(stack, 0.0F);
                inventory.setInventorySlotContents(slot, null);
            }
        }
    }

    @Override
    public EntityAgeable createChild(EntityAgeable mate) {
        return null;
    }

    @Override
    public boolean canMateWith(EntityAnimal other) {
        return false;
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {
        return false;
    }
}
