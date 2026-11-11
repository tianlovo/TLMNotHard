/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/EntityMaid.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Additional original: event/maid/{DismountMaidEvent,SwitchSittingEvent}.java
 * Adaptation: Forge 1.7.10 entity, six-slot inventory, equipment, persistence and empty-hand dismount priority
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.block.BlockLadder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.control.MaidMoveControl;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.RandomEmoji;
import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.FavorabilityManager;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidAquaticController;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.MaidMeleeAttack;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatMemory;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulFlightProfile;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDamageHistory;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.CombatCadence;
import com.tianluoqaq.tlmnh.common.combat.CombatHitReceipt;
import com.tianluoqaq.tlmnh.common.combat.EquipmentEpoch;
import com.tianluoqaq.tlmnh.common.combat.MaidAttackSettings;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidCombatWeaponView;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.entity.MaidCombatTaskState;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPoseDimensions;
import com.tianluoqaq.tlmnh.common.entity.MaidSnowballAnimation;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.entity.MaidTeleportParticles;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.interaction.MaidSelfCare;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.inventory.MaidPickupService;
import com.tianluoqaq.tlmnh.common.life.MaidHomeArea;
import com.tianluoqaq.tlmnh.common.life.meal.MaidMealController;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidCaptureService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDimensionTravel;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.network.MaidSoundNetwork;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.MaidBehaviorProtection;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryStore;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;
import com.tianluoqaq.tlmnh.common.sound.MaidWorldSoundState;
import com.tianluoqaq.tlmnh.mixin.MixinEntityNavigator;

import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原实体的核心状态移植，渲染、交互和行为由独立适配模块接入 */
public class EntityMaid extends EntityTameable implements IEntityAdditionalSpawnData {

    public static final String DEFAULT_MODEL_ID = "touhou_little_maid:hakurei_reimu";
    public static final String MODEL_ID_TAG = "ModelId";
    public static final String MAID_INVENTORY_TAG = "MaidInventory";
    public static final String EXPERIENCE_TAG = "MaidExperience";
    public static final int SAVE_VERSION = 1;
    private static final int DATA_BACK_ITEM = 18;
    private static final int DATA_MODEL = 20;
    private static final int DATA_TASK = 21;
    private static final int DATA_SCHEDULE = 22;
    private static final int DATA_EXPERIENCE = 23;
    private static final int DATA_HUNGER = 24;
    private static final int DATA_FAVORABILITY = 25;
    private static final int DATA_FLAGS = 26;
    private static final int DATA_OFFHAND = 27;
    private static final int DATA_POSE = 28;
    private static final int ENTITY_POSE_SHIFT = 8;
    private static final int ENTITY_POSE_MASK = 3 << ENTITY_POSE_SHIFT;
    private static final int SLEEP_DIRECTION_SHIFT = 10;
    private static final int SLEEP_DIRECTION_MASK = 7 << SLEEP_DIRECTION_SHIFT;
    private MaidEntityPose appliedEntityPose = MaidEntityPose.STANDING;
    private float poseEyeHeight = 1.5F * .85F;
    // 29 由 MaidHomeArea 使用，声音占用 30
    private static final int DATA_SOUND_PACK = 30;
    public static final String SOUND_PACK_ID_TAG = "SoundPackId";
    private static final int FLAG_PICKUP_ITEMS = 1;
    private static final int FLAG_PICKUP_EXPERIENCE = 2;
    private static final int FLAG_SILENT = 4;
    private static final int FLAG_STRUCK_LIGHTNING = 8;
    private static final int FLAG_UNIFIED_COMBAT = 16;
    private static final String OFFHAND_TAG = "TLMNHOffhand";

    private final MaidConfigManager configManager = new MaidConfigManager(dataWatcher);
    private final MaidWorldSoundState worldSounds = new MaidWorldSoundState(this);
    private final FavorabilityManager favorabilityManager = new FavorabilityManager(this);
    private final MaidInventory inventory = new MaidInventory(this);
    private final MaidPickupService pickup = new MaidPickupService(this);
    private final MaidHomeArea home = new MaidHomeArea(this);
    private final MaidItemUseState itemUses = new MaidItemUseState(this::syncItemUsePose);
    private final MaidMealController meals = new MaidMealController(this);
    private final MaidShieldController shield = new MaidShieldController(this, itemUses);
    private final MaidSnowballAnimation snowballAnimation = new MaidSnowballAnimation();
    private final MaidCombatMemory modernCombatMemory = new MaidCombatMemory();
    private final MaidAttackSettings attackSettings = new MaidAttackSettings();
    private final MaidCombatTaskState combatTaskState = new MaidCombatTaskState();
    private final MaidCombatWeaponView combatWeaponView = new MaidCombatWeaponView(this);
    private final CombatCadence combatCadence = new CombatCadence(this);
    private final CombatHitReceipt combatHitReceipt = new CombatHitReceipt(this);
    private final EquipmentEpoch equipmentEpoch = new EquipmentEpoch(this);
    private final MaidBrainCoordinator brain = new MaidBrainCoordinator(this);
    private final MaidBehaviorProtection behaviorProtection = new MaidBehaviorProtection(this);
    private final MaidDimensionTravel dimensionTravel = new MaidDimensionTravel(this);
    private final Runnable nativeAiTick = () -> super.updateAITasks();
    private final Runnable brainAfterLiving = () -> brain.afterLivingUpdate();
    private final MaidAquaticController aquatic = new MaidAquaticController(this);
    private final Runnable aquaticBeforeLiving = () -> aquatic.beforeLivingUpdate();
    private final Runnable aquaticAfterLiving = () -> aquatic.afterLivingUpdate();
    private final ChatBubbleManager chatBubbleManager = new ChatBubbleManager(this);
    private ItemStack offhand;
    private final MaidDamageHistory damageHistory = new MaidDamageHistory();
    public boolean guiOpening;
    private Object maidContainer;
    private boolean canClimb;
    private int climbFallDelayTicks;
    private NBTTagCompound extraSaveData = new NBTTagCompound();
    private boolean hadCombatCadenceData, hadCombatReceiptData;

    public EntityMaid(World world) {
        super(world);
        setSize(0.6F, 1.5F);
        // 原版真实实体沿用0.6的迈步高度，1.7.10 LivingEntity的默认值仅为0.5
        stepHeight = 0.6F;
        func_110163_bv();
        ((MixinEntityNavigator) this).tlmnh$setNavigator(new MaidPathNavigator(this, world));
        getNavigator().setCanSwim(true);
        ((MixinEntityNavigator) this).tlmnh$setMoveHelper(new MaidMoveControl(this));
        tasks.addTask(0, brain);
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        dataWatcher.addObjectByDataType(DATA_BACK_ITEM, 5);
        dataWatcher.addObject(DATA_MODEL, DEFAULT_MODEL_ID);
        dataWatcher.addObject(DATA_TASK, MaidTask.IDLE.getId());
        dataWatcher.addObject(DATA_SCHEDULE, (byte) MaidSchedule.DAY.ordinal());
        dataWatcher.addObject(DATA_EXPERIENCE, 0);
        dataWatcher.addObject(DATA_HUNGER, 0);
        dataWatcher.addObject(DATA_FAVORABILITY, 0);
        dataWatcher.addObject(DATA_FLAGS, (byte) (FLAG_PICKUP_ITEMS | FLAG_PICKUP_EXPERIENCE | FLAG_UNIFIED_COMBAT));
        dataWatcher.addObjectByDataType(DATA_OFFHAND, 5);
        // 原有动作位与独立实体姿态共享整数，不再占用额外元数据槽
        dataWatcher.addObject(DATA_POSE, 0);
        dataWatcher.addObject(DATA_SOUND_PACK, DefaultMaidSoundPack.getInitSoundPackId());
        MaidHomeArea.register(dataWatcher);
        MaidConfigManager.defineSynchedData(dataWatcher);
        ChatBubbleManager.defineSynchedData(dataWatcher);
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        // 原LivingEntity属性构建器沿用MOVEMENT_SPEED的0.7F默认值，不能保留早期原型的0.3
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue((double) .7F);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(64.0D);
        getAttributeMap().registerAttribute(SharedMonsterAttributes.attackDamage)
            .setBaseValue(2.0D);
        MaidCombatAttributes.register(getAttributeMap());
    }

    @Override
    protected boolean isAIEnabled() {
        return true;
    }

    @Override
    protected void updateAITasks() {
        if (behaviorProtection != null) behaviorProtection.run(nativeAiTick);
    }

    /** 开发选择也进入实体保护边界，工厂或旧清理失败时不重试并保留物品 */
    public MaidPolicyRuntime.SelectionResult selectBrainProfileForDevelopment(MaidBrainProfile profile) {
        return selectBrainProfileForDevelopment(profile, MaidFaithfulFlightProfile.SOURCE_ORIGINAL);
    }

    public MaidPolicyRuntime.SelectionResult selectBrainProfileForDevelopment(MaidBrainProfile profile,
        MaidFaithfulFlightProfile flightProfile) {
        var result = new MaidPolicyRuntime.SelectionResult[] { MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE };
        behaviorProtection.run(() -> result[0] = brain.selectProfileForDevelopment(profile, flightProfile));
        syncCombatProfileFlag();
        return result[0];
    }

    public MaidPolicyRuntime.SelectionResult selectBrainProfileForDevelopment(MaidBrainProfile profile,
        MaidBowAttack.ModernFlightProfile flightProfile) {
        var result = new MaidPolicyRuntime.SelectionResult[] { MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE };
        behaviorProtection.run(() -> result[0] = brain.selectProfileForDevelopment(profile, flightProfile));
        syncCombatProfileFlag();
        return result[0];
    }

    public void clearBehaviorInputs() {
        moveForward = moveStrafing = 0;
        setAIMoveSpeed(0);
        setJumping(false);
        if (aquatic != null) aquatic.clearPendingJump();
    }

    @Override
    public void onUpdate() {
        if (!worldObj.isRemote && equipmentEpoch != null) equipmentEpoch.beforeNativeUpdate();
        super.onUpdate();
    }

    @Override
    public void onLivingUpdate() {
        updateArmSwingProgress();
        if (!worldObj.isRemote && equipmentEpoch != null) equipmentEpoch.afterNativeEquipmentUpdate();
        syncCombatProfileFlag();
        if (!worldObj.isRemote && itemUses != null)
            itemUses.advanceUse((int) getEntityAttribute(MaidCombatAttributes.USE_ITEM_SPEED).getAttributeValue());
        if (aquatic != null) behaviorProtection.run(aquaticBeforeLiving);
        super.onLivingUpdate();
        if (aquatic != null) behaviorProtection.run(aquaticAfterLiving);
        if (!worldObj.isRemote) {
            favorabilityManager.tick();
            behaviorProtection.run(brainAfterLiving);
            shield.tickAfterLivingUpdate();
            meals.tick();
            pickup.tick();
            syncOffhand();
            syncBackpackShowItem();
            chatBubbleManager.tick();
            MaidSelfCare.tick(this);
        }
    }

    /** 1.7本地玩家posY含yOffset，原生乘客挂载点统一以玩家脚底为原点 */
    @Override
    public double getYOffset() {
        double offset = super.getYOffset();
        return ridingEntity instanceof EntityPlayer player ? offset - player.yOffset : offset;
    }

    /** 固定上游EntityMixin.positionRider公式；1.7本地玩家眼高原点先转为脚底 */
    public static Vec3 playerCarryPosition(double x, double y, double z, float playerYOffset, float bodyYaw,
        boolean descending) {
        float radians = (float) -Math.toRadians(bodyYaw);
        double offsetX = .75 * MathHelper.sin(radians);
        double offsetZ = .75 * MathHelper.cos(radians);
        return Vec3.createVectorHelper(x + offsetX, y - playerYOffset + .15 - (descending ? .3 : 0), z + offsetZ);
    }

    /** 原rideTick在原生骑乘更新之后对齐头部/身体，保留上游两个可选Create类型排除 */
    @Override
    public void updateRidden() {
        super.updateRidden();
        var vehicle = ridingEntity;
        if (vehicle == null) return;
        if (vehicle instanceof EntityPlayer player) {
            // 固定上游EntityMixin.positionRider替代原生1.35高的座位，原点为玩家脚底
            var position = playerCarryPosition(
                player.posX,
                player.posY,
                player.posZ,
                player.yOffset,
                player.renderYawOffset,
                player.isSneaking());
            setPosition(position.xCoord, position.yCoord, position.zCoord);
        }
        var id = MaidCombatEntities.id(vehicle);
        String type = id == null ? null : id.toString();
        if (!"create:carriage_contraption".equals(type) && !"create:seat".equals(type)) {
            rotationYawHead = vehicle.rotationYaw;
            renderYawOffset = vehicle.rotationYaw;
        }
    }

    @Override
    public void moveEntityWithHeading(float strafe, float forward) {
        if (hasPose(MaidPose.SLEEPING) && usesModernCoreNavigation()) {
            // 原睡眠逐tick清运动；旧move/jump helper在现代Brain之后仍可能重新投递移动
            clearBehaviorInputs();
            motionX = motionY = motionZ = 0;
            super.moveEntityWithHeading(0, 0);
            return;
        }
        if (brain != null && brain.travelLegacyBreathWater()) return;
        if (aquatic != null && aquatic.travelWater(strafe, forward)) return;
        if (brain != null && brain.applyCombatMovement()) {
            strafe = moveStrafing * .98F;
            forward = moveForward * .98F;
            // 本次投递不能在任务切换后残留于旧引擎的横移字段
            moveStrafing = moveForward = 0;
        }
        super.moveEntityWithHeading(strafe, forward);
    }

    @Override
    public boolean isPushedByWater() {
        return (aquatic == null || !aquatic.getManager()
            .wantToSwim()) && super.isPushedByWater();
    }

    public MaidAquaticController getAquaticController() {
        return aquatic;
    }

    public boolean isRideable() {
        return configManager.isRideable();
    }

    public void setRideable(boolean value) {
        if (!worldObj.isRemote) configManager.setRideable(value);
    }

    public MaidEntityPose getEntityPose() {
        return MaidEntityPose
            .decode((byte) ((dataWatcher.getWatchableObjectInt(DATA_POSE) & ENTITY_POSE_MASK) >>> ENTITY_POSE_SHIFT));
    }

    public void setEntityPose(MaidEntityPose pose) {
        if (!worldObj.isRemote) dataWatcher.updateObject(
            DATA_POSE,
            (dataWatcher.getWatchableObjectInt(DATA_POSE) & ~ENTITY_POSE_MASK) | pose.ordinal() << ENTITY_POSE_SHIFT);
    }

    @Override
    public void func_145781_i(int index) {
        super.func_145781_i(index);
        if (index == DATA_POSE && appliedEntityPose != getEntityPose()) {
            appliedEntityPose = getEntityPose();
            MaidPoseDimensions.refresh(this, appliedEntityPose);
        }
    }

    @Override
    public float getEyeHeight() {
        return poseEyeHeight > 0 ? poseEyeHeight : height * .85F;
    }

    /** 原版女仆没有幼体；旧 EntityAgeable 每个客户端 tick 的缩放不能覆盖物理 Pose */
    @Override
    public void setScaleForAge(boolean child) {
        var pose = getEntityPose();
        if (width != pose.width || height != pose.height) MaidPoseDimensions.refresh(this, pose);
    }

    /** 现代引擎在 refreshDimensions 时缓存眼高，单改坐姿标志不刷新 */
    public void refreshPoseEyeHeight(MaidEntityPose pose) {
        poseEyeHeight = pose == MaidEntityPose.SLEEPING ? .2F : pose.height * (isSitting() ? .65F : .85F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handleHealthUpdate(byte state) {
        if (state >= 100 && state <= 102) spawnRestoreHealthParticle(state - 100 + 7);
        else if (state == 46) MaidTeleportParticles.show(this);
        else if (state == MaidSnowballAnimation.STATUS) snowballAnimation.start(this);
        else super.handleHealthUpdate(state);
    }

    /** 上游spawnRestoreHealthParticle：旧版mobSpell对应ENTITY_EFFECT */
    public void spawnRestoreHealthParticle(int count) {
        if (!worldObj.isRemote) return;
        for (int i = 0; i < count; i++) {
            double dx = rand.nextGaussian() * .02, dy = rand.nextGaussian() * .02, dz = rand.nextGaussian() * .02;
            worldObj.spawnParticle(
                "mobSpell",
                posX + rand.nextFloat() * width * 2 - width - dx * 10,
                posY + rand.nextFloat() * height - dy * 10,
                posZ + rand.nextFloat() * width * 2 - width - dz * 10,
                .9,
                .1,
                .1);
        }
    }

    @Override
    protected void damageEntity(DamageSource source, float amount) {
        float before = getHealth();
        super.damageEntity(source, amount);
        // 目标版没有LivingDamageEvent，在原生护甲/吸收处理完成后响应实际扣血
        if (worldObj != null && !worldObj.isRemote && chatBubbleManager != null && getHealth() < before)
            RandomEmoji.addHurtChatText(this);
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // Forge构造事件发生在本类字段初始化之前，仍让原生伤害入口正常处理
        var previous = worldSounds == null ? null : worldSounds.beginDamage(source);
        try {
            if (shield != null && worldObj != null && !worldObj.isRemote) shield.beforeIncoming(source, amount);
            boolean accepted = super.attackEntityFrom(source, amount);
            if (accepted && damageHistory != null && worldObj != null && !worldObj.isRemote)
                damageHistory.record(worldObj, worldObj.getTotalWorldTime(), source);
            if (accepted && brain != null) behaviorProtection.run(brain::recordAcceptedHurt);
            return accepted;
        } finally {
            if (worldSounds != null) worldSounds.endDamage(previous);
        }
    }

    public DamageSource getMaidLastDamageSource() {
        return damageHistory == null || worldObj == null ? null
            : damageHistory.source(worldObj, worldObj.getTotalWorldTime());
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

    public MaidWorldSoundState getWorldSoundState() {
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
        if (climbFallDelayTicks > 0) {
            climbFallDelayTicks--;
            fallDistance = 0;
        }
        if (worldSounds != null) worldSounds.tick();
    }

    public boolean usesModernCoreNavigation() {
        var policy = brain == null ? null : brain.peekPolicyRuntime();
        return policy != null && policy.requiresAfterNativeNavigation() && policy.isReady();
    }

    public boolean isCanClimb() {
        return canClimb;
    }

    public void setCanClimb(boolean value) {
        canClimb = value;
    }

    public int getClimbFallDelayTicks() {
        return climbFallDelayTicks;
    }

    @Override
    public boolean isOnLadder() {
        if (!usesModernCoreNavigation()) return super.isOnLadder();
        var path = getNavigator().getPath();
        boolean vertical = false;
        if (path != null && !path.isFinished()) {
            for (int i = Math.max(0, path.getCurrentPathIndex() - 3); i
                < Math.min(path.getCurrentPathLength(), path.getCurrentPathIndex() + 3) - 1; i++) {
                var first = path.getPathPointFromIndex(i);
                var next = path.getPathPointFromIndex(i + 1);
                if (first.xCoord == next.xCoord && first.zCoord == next.zCoord) {
                    vertical = true;
                    break;
                }
            }
        }
        if (!vertical) return false;
        int x = MathHelper.floor_double(posX);
        int y = MathHelper.floor_double(boundingBox.minY);
        int z = MathHelper.floor_double(posZ);
        // 固定现代Forge默认只判当前格；目标整包运行时可能强制全身体扫描，不能继承其提前触发
        boolean ladder = modernLadderAt(x, y, z);
        int ladderY = y;
        if (!ladder && worldObj.blockExists(x, y - 1, z)) {
            ladder = modernLadderAt(x, y - 1, z);
            ladderY = y - 1;
        }
        if (ladder) {
            climbFallDelayTicks = 30;
            if (worldObj.getBlock(x, ladderY, z) instanceof BlockLadder) {
                int meta = worldObj.getBlockMetadata(x, ladderY, z);
                int yaw = switch (meta) {
                    case 2 -> 0;
                    case 3 -> 180;
                    case 4 -> 270;
                    case 5 -> 90;
                    default -> -1;
                };
                if (yaw >= 0) {
                    rotationYaw = yaw;
                    rotationYawHead = yaw;
                }
            }
        }
        return ladder;
    }

    private boolean modernLadderAt(int x, int y, int z) {
        return worldObj.blockExists(x, y, z) && worldObj.getBlock(x, y, z)
            .isLadder(worldObj, x, y, z, this);
    }

    @Override
    protected String getLivingSound() {
        return worldObj.isRemote || worldSounds == null ? null : worldSounds.ambient();
    }

    @Override
    protected String getHurtSound() {
        return worldObj.isRemote ? SoundUtil.name("ai.hurt") : worldSounds == null ? null : worldSounds.hurt();
    }

    @Override
    protected String getDeathSound() {
        return worldObj.isRemote ? SoundUtil.name("ai.death") : worldSounds == null ? null : worldSounds.death();
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
            if (!worldObj.isRemote && BuiltinSoundCatalog.events()
                .contains(event)) MaidSoundNetwork.send(this, new PlayMaidSoundMessage(this, event));
            return;
        }
        if (!isSilent()) super.playSound(name, volume, pitch);
    }

    public void tryPlayMaidPickupSound() {
        if (!worldObj.isRemote) worldSounds.tryPickup();
    }

    public ChatBubbleManager getChatBubbleManager() {
        return chatBubbleManager;
    }

    public MaidMealController getMealController() {
        return meals;
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
        if (aquatic != null) aquatic.afterNativeAiTick();
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

    public MaidSnowballAnimation getSnowballAnimation() {
        return snowballAnimation;
    }

    public void startPickUpSnowball() {
        snowballAnimation.start(this);
    }

    @Override
    public void swingItem() {
        swingMaidHand(false);
    }

    public void swingMaidHand(boolean offhand) {
        super.swingItem();
        if (!worldObj.isRemote && isSwingInProgress && swingProgressInt == -1) setPose(MaidPose.SWING_OFFHAND, offhand);
    }

    /** 原EntityMaid.getTarget读取Brain ATTACK_TARGET，原型仍使用原生EntityLiving字段 */
    @Override
    public EntityLivingBase getAttackTarget() {
        var policy = brain == null ? null : brain.peekPolicyRuntime();
        if (modernCombatMemory != null && policy != null && policy.requiresAfterNativeNavigation())
            return modernCombatMemory.target();
        return super.getAttackTarget();
    }

    @Override
    public boolean attackEntityAsMob(Entity target) {
        return MaidMeleeAttack.attack(this, target);
    }

    public boolean hasPose(MaidPose pose) {
        return (dataWatcher.getWatchableObjectInt(DATA_POSE) & pose.getMask()) != 0;
    }

    public void setPose(MaidPose pose, boolean active) {
        if (worldObj.isRemote) return;
        int flags = dataWatcher.getWatchableObjectInt(DATA_POSE);
        int updated = active ? flags | pose.getMask() : flags & ~pose.getMask();
        if (pose == MaidPose.SLEEPING && !active) updated &= ~SLEEP_DIRECTION_MASK;
        dataWatcher.updateObject(DATA_POSE, updated);
        if (pose == MaidPose.SLEEPING && ((flags & pose.getMask()) != 0) != active)
            setEntityPose(active ? MaidEntityPose.SLEEPING : MaidEntityPose.STANDING);
    }

    /** 0..3沿旧床metadata方向；-1为无已确认床，不把未知值猜成南向 */
    public int getSleepingBedDirection() {
        int encoded = (dataWatcher.getWatchableObjectInt(DATA_POSE) & SLEEP_DIRECTION_MASK) >>> SLEEP_DIRECTION_SHIFT;
        return encoded >= 1 && encoded <= 4 ? encoded - 1 : -1;
    }

    public void setSleepingBedDirection(int direction) {
        if (worldObj.isRemote) return;
        if (direction < -1 || direction > 3) throw new IllegalArgumentException("Invalid sleeping bed direction");
        int flags = dataWatcher.getWatchableObjectInt(DATA_POSE);
        dataWatcher.updateObject(DATA_POSE, (flags & ~SLEEP_DIRECTION_MASK) | (direction + 1) << SLEEP_DIRECTION_SHIFT);
    }

    @Override
    public boolean interact(EntityPlayer player) {
        var held = player == null ? null
            : IntegrationGuard.call(ExternalFeature.PLAYER_OFFHAND, () -> player.getCurrentEquippedItem(), () -> null);
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
                playSound(SoundUtil.name("ai.tamed"), 1, 1);
            }
            return true;
        }
        if (!isOwner(player)) return nameTag;
        // 上游主人交互仅使用主手；Backhand 仍可从实际副手槽进行野生驯服
        if (!MaidInteractions.isMainHand(player)) return nameTag;
        if (MaidCaptureService.interact(this, player, held)) return true;
        if (MaidInteractions.interactOwned(this, player, held)) return true;
        if (!worldObj.isRemote) {
            if (player.isSneaking() && held == null) {
                // 固定上游DismountMaidEvent先于SwitchSittingEvent，空手放下不能同时切坐姿
                boolean mounted = ridingEntity != null || riddenByEntity != null;
                if (ridingEntity != null) mountEntity(null);
                if (riddenByEntity != null) riddenByEntity.mountEntity(null);
                if (!mounted) setSitting(!isSitting());
                getNavigator().clearPathEntity();
                setAttackTarget(null);
            } else {
                player.openGui(TLMNotHard.INSTANCE, CommonProxy.GUI_MAID_ORIGINAL, worldObj, getEntityId(), 0, 0);
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

    /** Forge先构造新窗口再关闭旧窗口，旧会话只能释放自身 */
    public void openMaidContainer(Object container) {
        maidContainer = Objects.requireNonNull(container, "container");
        guiOpening = true;
    }

    public void closeMaidContainer(Object container) {
        if (maidContainer == container) {
            maidContainer = null;
            guiOpening = false;
        }
    }

    public MaidTask getTask() {
        String id = dataWatcher.getWatchableObjectString(DATA_TASK);
        return combatTaskState == null ? MaidTask.fromId(id) : combatTaskState.task(id, isUnifiedCombat());
    }

    public void setTask(MaidTask task) {
        if (!combatTaskState.canSelect()) return;
        if (dataWatcher.getWatchableObjectString(DATA_TASK)
            .equals(task.getId()) && combatTaskState.available()) return;
        boolean modernRefresh = brain != null && brain.refreshModernBrain();
        combatTaskState.selected(task);
        dataWatcher.updateObject(DATA_TASK, task.getId());
        if (modernRefresh) return;
        setAttackTarget(null);
        getNavigator().clearPathEntity();
        if (shield != null) shield.cancelUse("task_changed");
        if (itemUses != null) itemUses.reconcile();
    }

    public boolean isUnifiedCombat() {
        return worldObj.isRemote ? (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_UNIFIED_COMBAT) != 0
            : brain == null || brain.usesUnifiedCombat();
    }

    private void syncCombatProfileFlag() {
        if (worldObj == null || worldObj.isRemote || brain == null) return;
        byte flags = dataWatcher.getWatchableObjectByte(DATA_FLAGS);
        byte next = (byte) (brain.usesUnifiedCombat() ? flags | FLAG_UNIFIED_COMBAT : flags & ~FLAG_UNIFIED_COMBAT);
        if (next != flags) dataWatcher.updateObject(DATA_FLAGS, next);
        String watched = dataWatcher.getWatchableObjectString(DATA_TASK);
        String projected = combatTaskState.task(watched, brain.usesUnifiedCombat())
            .getId();
        if (!watched.equals(projected)) dataWatcher.updateObject(DATA_TASK, projected);
    }

    /** 行为任务保持统一身份，旧机械入口只取得当前已准入的武器模式 */
    public MaidTask getCombatModeTask() {
        if (!isUnifiedCombat()) return getTask();
        if (getTask() != MaidTask.ATTACK || !combatTaskState.available()) return MaidTask.IDLE;
        var profile = getCombatWeaponProfile();
        return profile.bow() ? MaidTask.RANGED_ATTACK : profile.melee() ? MaidTask.ATTACK : MaidTask.IDLE;
    }

    public MaidCombatTaskState getCombatTaskState() {
        return combatTaskState;
    }

    public MaidWeaponProfile getCombatWeaponProfile() {
        return combatWeaponView.current();
    }

    public CombatCadence getCombatCadence() {
        return combatCadence;
    }

    public CombatHitReceipt getCombatHitReceipt() {
        return combatHitReceipt;
    }

    public EquipmentEpoch getEquipmentEpoch() {
        return equipmentEpoch;
    }

    public MaidSchedule getSchedule() {
        int index = dataWatcher.getWatchableObjectByte(DATA_SCHEDULE);
        return index >= 0 && index < MaidSchedule.values().length ? MaidSchedule.values()[index] : MaidSchedule.DAY;
    }

    public void setSchedule(MaidSchedule schedule) {
        dataWatcher.updateObject(DATA_SCHEDULE, (byte) schedule.ordinal());
        if (brain != null) brain.refreshModernBrain();
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

    public FavorabilityManager getFavorabilityManager() {
        return favorabilityManager;
    }

    public boolean isStruckByLightning() {
        return (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_STRUCK_LIGHTNING) != 0;
    }

    public void setStruckByLightning(boolean struck) {
        setMaidFlag(FLAG_STRUCK_LIGHTNING, struck);
    }

    @Override
    public void onStruckByLightning(EntityLightningBolt lightning) {
        super.onStruckByLightning(lightning);
        if (!worldObj.isRemote && !isStruckByLightning()) {
            var health = getEntityAttribute(SharedMonsterAttributes.maxHealth);
            health.setBaseValue(health.getBaseValue() + 20);
            setStruckByLightning(true);
        }
    }

    public void onMaidStartSleeping() {
        setHealth(getMaxHealth());
        favorabilityManager.apply(Type.SLEEP);
    }

    public MaidCombatMemory getModernCombatMemory() {
        return modernCombatMemory;
    }

    public AttackListData getAttackListData() {
        return attackSettings.data();
    }

    public int getFavorability() {
        return dataWatcher.getWatchableObjectInt(DATA_FAVORABILITY);
    }

    public void setFavorability(int value) {
        dataWatcher.updateObject(DATA_FAVORABILITY, Math.max(0, Math.min(384, value)));
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

    public boolean pickupItem(EntityItem item, boolean simulate) {
        return pickup.pickupItem(item, simulate);
    }

    public void pickupXPOrb(EntityXPOrb orb) {
        pickup.pickupExperience(orb);
    }

    public boolean pickupArrow(EntityArrow arrow, boolean simulate) {
        return pickup.pickupArrow(arrow, simulate);
    }

    public boolean canPickup(Entity entity, boolean rejectWater) {
        return pickup.canPickup(entity, rejectWater);
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
        if (BuiltinSoundCatalog.get(id) != null) dataWatcher.updateObject(DATA_SOUND_PACK, id);
    }

    /** 原版背包第六格的显示副本，随实体元数据同步给全部追踪玩家 */
    public ItemStack getBackpackShowItem() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectItemStack(DATA_BACK_ITEM)
            : inventory.getStackInSlot(MaidInventory.BACKPACK_SHOW_SLOT);
    }

    public void syncBackpackShowItem() {
        if (worldObj == null || worldObj.isRemote || inventory == null) return;
        var actual = inventory.getStackInSlot(MaidInventory.BACKPACK_SHOW_SLOT);
        if (!ItemStack.areItemStacksEqual(actual, dataWatcher.getWatchableObjectItemStack(DATA_BACK_ITEM)))
            dataWatcher.updateObject(DATA_BACK_ITEM, actual == null ? null : actual.copy());
    }

    public ItemStack getOffhandItem() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectItemStack(DATA_OFFHAND) : offhand;
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {
        if (worldObj != null && !worldObj.isRemote) stack = ProtectedItemCodec.admit(stack, 64);
        super.setCurrentItemOrArmor(slot, stack);
        if (combatWeaponView != null) combatWeaponView.invalidate();
        if (equipmentEpoch != null) equipmentEpoch.invalidate();
    }

    public void setOffhandItem(ItemStack stack) {
        if (!worldObj.isRemote) stack = ProtectedItemCodec.admit(stack, 64);
        offhand = stack;
        if (equipmentEpoch != null) equipmentEpoch.invalidate();
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

    /** 导出 TLMNH 扩展标签的独立深拷贝，行为数据由会话层按命名空间分发 */
    public NBTTagCompound copyBehaviorSaveData() {
        return (NBTTagCompound) extraSaveData.copy();
    }

    /** 原子提交 BehaviorExt 子标签，旧快照不匹配或类型异常时保留现场 */
    public boolean commitBehaviorExtensionData(NBTTagCompound before, NBTTagCompound after) {
        if (worldObj == null || worldObj.isRemote || before == null || after == null) return false;
        var previous = before.getTag("BehaviorExt");
        var current = extraSaveData.getTag("BehaviorExt");
        var proposed = after.getTag("BehaviorExt");
        if (!Objects.equals(previous, current)) return false;
        if (Objects.equals(current, proposed)) return true;
        if (current != null && !(current instanceof NBTTagCompound)) return false;
        if (proposed != null && !(proposed instanceof NBTTagCompound)) return false;
        if (proposed == null || ((NBTTagCompound) proposed).hasNoTags()) extraSaveData.removeTag("BehaviorExt");
        else extraSaveData.setTag("BehaviorExt", proposed.copy());
        return true;
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        if (brain != null) brain.flushPersistentBehaviorData();
        var extension = (NBTTagCompound) extraSaveData.copy();
        extension.setInteger("Schema", Math.max(SAVE_VERSION, extension.getInteger("Schema")));
        tag.setTag("TLMNH", extension);
        tag.setString(MODEL_ID_TAG, getModelId());
        tag.setString(SOUND_PACK_ID_TAG, getSoundPackId());
        tag.setBoolean("Silent", isSilent());
        configManager.addAdditionalSaveData(tag);
        combatTaskState.save(tag, dataWatcher.getWatchableObjectString(DATA_TASK), isUnifiedCombat());
        if (isUnifiedCombat() || hadCombatCadenceData || combatCadence.hasPersistentState()) combatCadence.save(tag);
        if (isUnifiedCombat() || hadCombatReceiptData || combatHitReceipt.hasPersistentState())
            combatHitReceipt.save(tag);
        tag.setString("MaidScheduleMode", getSchedule().name());
        tag.setInteger(EXPERIENCE_TAG, getExperience());
        tag.setInteger("MaidHunger", getHunger());
        tag.setInteger("MaidFavorability", getFavorability());
        tag.setBoolean("StruckByLightning", isStruckByLightning());
        favorabilityManager.addAdditionalSaveData(tag);
        tag.setBoolean("MaidIsPickup", isPickup());
        tag.setBoolean("PickupItems", (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_ITEMS) != 0);
        tag.setBoolean(
            "PickupExperience",
            (dataWatcher.getWatchableObjectByte(DATA_FLAGS) & FLAG_PICKUP_EXPERIENCE) != 0);
        home.save(tag);
        attackSettings.save(tag);
        tag.setTag(MAID_INVENTORY_TAG, inventory.writeBackpack());
        tag.setTag(
            RecoveryStore.TAG,
            inventory.recovery()
                .write());
        var equipment = new NBTTagList();
        for (int slot = 0; slot < 5; slot++) {
            var stack = getEquipmentInSlot(slot);
            equipment.appendTag(stack == null ? new NBTTagCompound() : ProtectedItemCodec.encode(stack));
        }
        tag.setTag("Equipment", equipment);
        if (offhand != null) tag.setTag(OFFHAND_TAG, ProtectedItemCodec.encode(offhand));
        else tag.removeTag(OFFHAND_TAG);
        meals.save(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        rejectCombatPersistentRead();
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
        rejectCombatPersistentRead();
        if (damageHistory != null) damageHistory.clear();
        if (modernCombatMemory != null) modernCombatMemory.clear();
        boolean managed = hasServerBrain(), entered = false, successful = false;
        try {
            if (managed) {
                entered = true;
                brain.beforePersistentRead();
            }
            // 原生读取前先解码，避免缺失物品变为空槽；原始输入保持不变
            var sanitized = (NBTTagCompound) tag.copy();
            sanitized.removeTag("Equipment");
            super.readEntityFromNBT(sanitized);
            inventory.recovery()
                .read(tag.getTagList(RecoveryStore.TAG, Constants.NBT.TAG_COMPOUND));
            inventory.recovery()
                .retainInvalidList(tag, "Equipment");
            inventory.recovery()
                .retainInvalidList(tag, MAID_INVENTORY_TAG);
            inventory.recovery()
                .retainInvalidList(tag, RecoveryStore.TAG);
            var equipment = tag.getTagList("Equipment", Constants.NBT.TAG_COMPOUND);
            for (int slot = 0; slot < 5; slot++) setCurrentItemOrArmor(
                slot,
                slot < equipment.tagCount() ? ProtectedItemCodec.decode(equipment.getCompoundTagAt(slot)) : null);
            for (int slot = 5; slot < equipment.tagCount(); slot++) inventory.recovery()
                .retain(equipment.getCompoundTagAt(slot));
            extraSaveData = (NBTTagCompound) tag.getCompoundTag("TLMNH")
                .copy();
            if (extraSaveData.getInteger("Schema") > SAVE_VERSION) {
                TLMNotHard.LOG.warn(
                    "Maid {} has newer save schema {}; preserving extension data",
                    getUniqueID(),
                    extraSaveData.getInteger("Schema"));
            }
            setModelId(
                tag.hasKey(MODEL_ID_TAG, Constants.NBT.TAG_STRING) ? tag.getString(MODEL_ID_TAG) : DEFAULT_MODEL_ID);
            if (tag.hasKey(SOUND_PACK_ID_TAG, Constants.NBT.TAG_STRING))
                setSoundPackId(tag.getString(SOUND_PACK_ID_TAG));
            setSilent(tag.getBoolean("Silent"));
            configManager.readAdditionalSaveData(tag);
            dataWatcher.updateObject(DATA_TASK, combatTaskState.load(tag));
            hadCombatCadenceData = tag.hasKey(CombatCadence.TAG);
            hadCombatReceiptData = tag.hasKey(CombatHitReceipt.TAG);
            combatCadence.load(tag);
            combatHitReceipt.load(tag);
            equipmentEpoch.invalidate();
            combatWeaponView.invalidate();
            try {
                setSchedule(MaidSchedule.valueOf(tag.getString("MaidScheduleMode")));
            } catch (IllegalArgumentException exception) {
                setSchedule(MaidSchedule.DAY);
            }
            setExperience(tag.getInteger(EXPERIENCE_TAG));
            setHunger(tag.getInteger("MaidHunger"));
            setFavorability(tag.getInteger("MaidFavorability"));
            setStruckByLightning(tag.getBoolean("StruckByLightning"));
            favorabilityManager.readAdditionalSaveData(tag);
            favorabilityManager.refreshAttributes();
            if (tag.hasKey("PickupItems")) setPickupItems(tag.getBoolean("PickupItems"));
            if (tag.hasKey("PickupExperience")) setPickupExperience(tag.getBoolean("PickupExperience"));
            if (!tag.hasKey("PickupItems") && !tag.hasKey("PickupExperience")
                && tag.hasKey("MaidIsPickup", Constants.NBT.TAG_BYTE)) setPickup(tag.getBoolean("MaidIsPickup"));
            inventory.readBackpack(tag.getTagList(MAID_INVENTORY_TAG, Constants.NBT.TAG_COMPOUND));
            setOffhandItem(ProtectedItemCodec.decodeField(tag, OFFHAND_TAG));
            home.load(tag);
            attackSettings.load(tag);
            meals.load(tag);
            func_110163_bv();
            successful = true;
        } finally {
            if (entered) brain.afterPersistentRead(successful);
        }
    }

    private boolean hasServerBrain() {
        return brain != null && worldObj != null && !worldObj.isRemote;
    }

    private void rejectCombatPersistentRead() {
        if (combatHitReceipt != null && combatHitReceipt.blocksTransfer())
            throw new IllegalStateException("Cannot reload maid during an unsettled combat submission");
    }

    /** 目标世界确认登记后才移除源女仆；取消或异常不能丢失原实体持有的完整物品 */
    @Override
    public void travelToDimension(int dimension) {
        dimensionTravel.travel(dimension);
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (!worldObj.isRemote && dead) {
            favorabilityManager.apply(Type.DEATH);
            MaidDeathService.preserve(this);
            brain.resetTask();
        }
    }

    @Override
    protected void onDeathUpdate() {
        if (!worldObj.isRemote && !MaidDeathService.canFinishDeath(this, dead)) return;
        super.onDeathUpdate();
    }

    @Override
    public void setDead() {
        try {
            if (hasServerBrain()) brain.onUnload();
        } finally {
            if (modernCombatMemory != null) modernCombatMemory.clear();
            super.setDead();
        }
    }

    /** 驯服女仆交给死亡保管；野生女仆完整掉落，禁止原版随机毁损装备 */
    @Override
    protected void dropEquipment(boolean recentlyHit, int looting) {
        meals.cancel();
        if (!worldObj.isRemote && dead) favorabilityManager.apply(Type.DEATH);
        if (MaidDeathService.preserve(this)) return;
        MaidDeathService.dropUnowned(this);
    }

    /** 输出可兑现的保存经验，死亡账本和交互保留分别持有已移交或锁定的余额 */
    @Override
    protected int getExperiencePoints(EntityPlayer player) {
        return MaidDeathService.nativeDeathExperience(this);
    }

    /** 1.7.10该protected标志只用于经验掉落的最近玩家攻击门槛 */
    @Override
    protected boolean isPlayer() {
        return true;
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
