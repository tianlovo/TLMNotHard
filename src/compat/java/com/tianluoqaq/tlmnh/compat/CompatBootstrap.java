package com.tianluoqaq.tlmnh.compat;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.spi.DoorCompatProvider;
import com.tianluoqaq.tlmnh.common.ai.spi.WorldExtraContentProvider;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.ShieldCompatProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.WeaponCompatProvider;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponStrike;
import com.tianluoqaq.tlmnh.common.compat.CompatRegistrar;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider;
import com.tianluoqaq.tlmnh.common.lifecycle.spi.LifecycleStorageCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.applecore.AppleCoreCompat;
import com.tianluoqaq.tlmnh.compat.applecore.common.food.AppleCoreNutrition;
import com.tianluoqaq.tlmnh.compat.battlegear2.Battlegear2Compat;
import com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess;
import com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldIds;
import com.tianluoqaq.tlmnh.compat.cropsnh.common.breeding.CropsNhBreedingCompat;
import com.tianluoqaq.tlmnh.compat.endlessids.common.storage.EndlessIdsSectionData;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.block.EtFuturumBlocks;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.entity.EtFuturumEntities;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.entity.MaidVehiclePlatform;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.fall.EtFuturumBoatRescueCompat;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.item.EtFuturumItems;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.physics.NativeLiquidPhysics;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.sound.EtFuturumSounds;
import com.tianluoqaq.tlmnh.compat.extrautilities.common.item.HealingAxeCompat;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics.NativeArrowGravity;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.space.GalacticraftSpaceSurvivalCompat;
import com.tianluoqaq.tlmnh.compat.gregtech.GregTechCompat;
import com.tianluoqaq.tlmnh.compat.gregtech.common.combat.GregTechWeaponAdapter;
import com.tianluoqaq.tlmnh.compat.gregtech.common.food.GregTechFoodAccess;
import com.tianluoqaq.tlmnh.compat.gregtech.common.hazard.GregTechHotItemHazardCompat;
import com.tianluoqaq.tlmnh.compat.gregtech.common.maintenance.GregTechMaintenanceHatchCompat;
import com.tianluoqaq.tlmnh.compat.hodgepodge.common.storage.HodgepodgeWorldDataBarrier;
import com.tianluoqaq.tlmnh.compat.hungeroverhaul.common.hunger.HungerOverhaulPolicyCompat;
import com.tianluoqaq.tlmnh.compat.malisisdoors.common.block.MaidDoorAccess;
import com.tianluoqaq.tlmnh.compat.malisisdoors.common.block.NativeDoorState;
import com.tianluoqaq.tlmnh.compat.malisisdoors.common.combat.MalisisCombatGeometry;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.common.shield.TinkersDefenseShields;
import com.tianluoqaq.tlmnh.compat.witchery.common.entity.WitcheryInitialSize;

/** 双端共享适配实现装配入口；仅向 main 暴露窄接口，不在初始化阶段提前加载可选模组类型 */
public final class CompatBootstrap implements CompatRegistrar {

    @Override
    public void register(CompatServices.Sink sink) {
        sink.shields(new ShieldProviderImpl())
            .weapons(new WeaponProviderImpl())
            .projectilePhysics(new ProjectilePhysicsImpl())
            .doors(new DoorProviderImpl())
            .worldContent(new WorldContentImpl())
            .nutrition(new NutritionProviderImpl())
            .lifecycleStorage(new LifecycleStorageImpl())
            .backpacks(new BackpackCompatDispatcher())
            .maintenanceHatch(GregTechMaintenanceHatchCompat.INSTANCE)
            .cropsNhBreeding(CropsNhBreedingCompat.INSTANCE)
            .hungerPolicy(HungerOverhaulPolicyCompat.INSTANCE)
            .healingAxe(HealingAxeCompat.INSTANCE)
            .hotItemHazard(GregTechHotItemHazardCompat.INSTANCE)
            .specialArmor(SpecialArmorCompatDispatcher.INSTANCE)
            .flightEquipment(FlightCompatDispatcher.INSTANCE)
            .spaceSurvival(GalacticraftSpaceSurvivalCompat.INSTANCE)
            .fallRescueBoat(EtFuturumBoatRescueCompat.INSTANCE);
    }

    private static final class ShieldProviderImpl implements ShieldCompatProvider {

        @Override
        public String defaultBlockSound() {
            return BattlegearShieldIds.BLOCK_SOUND;
        }

        @Override
        public Item previewShieldItem() {
            return OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID) ? BattlegearShieldAccess.woodenShieldItem() : null;
        }

        @Override
        public boolean isShield(ItemStack stack) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID)) return false;
            return BattlegearShieldAccess.isShield(stack);
        }

        @Override
        public boolean supportsBaseShield(String itemId) {
            return BattlegearShieldAccess.supports(itemId);
        }

        @Override
        public boolean supportsModularShield(String itemId) {
            return TinkersDefenseShields.supports(itemId);
        }

        @Override
        public MaidShieldItems.Status modularShieldStatus(ItemStack stack, String itemId) {
            return TinkersDefenseShields.status(stack, itemId);
        }

        @Override
        public NBTTagCompound readToolData(ItemStack stack) {
            return TConstructToolData.read(stack);
        }

        @Override
        public boolean isToolBroken(ItemStack stack) {
            return TConstructToolData.broken(stack);
        }

        @Override
        public boolean isModularTool(String itemId) {
            return TConstructToolData.isTool(itemId);
        }

        @Override
        public boolean isUsableModularAxe(ItemStack stack) {
            return TConstructToolData.usableAxe(stack);
        }

        @Override
        public boolean canBlock(ItemStack stackCopy, DamageSource source) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID)) return false;
            return BattlegearShieldAccess.canBlock(stackCopy, source);
        }

        @Override
        public float blockAngle(ItemStack stackCopy) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID))
                return CompatBootstrapTuning.COMPAT_BOOTSTRAP_BLOCK_ANGLE_FACTOR;
            return BattlegearShieldAccess.blockAngle(stackCopy);
        }

        @Override
        public float damageReduction(ItemStack stackCopy, DamageSource source) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID))
                return CompatBootstrapTuning.COMPAT_BOOTSTRAP_BLOCK_ANGLE_FACTOR;
            return BattlegearShieldAccess.reduction(stackCopy, source);
        }

        @Override
        public boolean supportsArrowCapture(ItemStack stack) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID)) return false;
            return BattlegearShieldAccess.supportsArrowCapture(stack);
        }

        @Override
        public ArrowCaptureHandle prepareArrowCapture(ItemStack stack) {
            if (!OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID)) return null;
            return BattlegearShieldAccess.prepareArrowCapture(stack);
        }
    }

    private static final class WeaponProviderImpl implements WeaponCompatProvider {

        @Override
        public boolean handles(Item item) {
            return item != null && CompatBootstrapReflectionSpec.GREGTECH_TOOL_CLASS.equals(
                item.getClass()
                    .getName());
        }

        @Override
        public MaidWeaponProfile inspect(ItemStack stack) {
            if (!OptionalApi.isModLoaded(GregTechCompat.MOD_ID))
                return MaidWeaponProfile.unknown(CompatBootstrapIds.ID_GREGTECH_MOD_ABSENT);
            return GregTechWeaponAdapter.inspect(stack);
        }

        @Override
        public MaidWeaponStrike prepareMelee(EntityMaid maid, ItemStack stack) {
            if (!OptionalApi.isModLoaded(GregTechCompat.MOD_ID)) return null;
            return GregTechWeaponAdapter.prepare(maid, stack);
        }
    }

    private static final class ProjectilePhysicsImpl implements ProjectilePhysicsProvider {

        @Override
        public boolean arrowGravityAvailable() {
            return NativeArrowGravity.available();
        }

        @Override
        public float arrowGravity(EntityArrow arrow) {
            return NativeArrowGravity.forArrow(arrow);
        }

        @Override
        public ArrowLaunchSample sampleArrowLaunch(EntityArrow arrow) {
            return NativeArrowGravity.sampleForLaunch(arrow);
        }

        @Override
        public LiquidFlowSample sampleLiquidFlow() {
            return NativeLiquidPhysics.sample();
        }
    }

    private static final class DoorProviderImpl implements DoorCompatProvider {

        @Override
        public boolean isDoorOpen(Block block, World world, int x, int y, int z) {
            return MaidDoorAccess.open(block, world, x, y, z);
        }

        @Override
        public boolean setDoorOpen(Block block, World world, int x, int y, int z, boolean open) {
            return MaidDoorAccess.setOpen(block, world, x, y, z, open);
        }

        @Override
        public boolean isDoorOpenOrOpening(BlockDoor door, World world, int x, int y, int z) {
            return NativeDoorState.isOpenOrOpening(door, world, x, y, z);
        }

        @Override
        public boolean supportsCombatGeometry(Block block) {
            return MalisisCombatGeometry.matches(block);
        }

        @Override
        public GeometrySample captureCombatGeometry(World world, Block block, int x, int y, int z,
            CombatWorkQuota quota) {
            var sample = MalisisCombatGeometry.capture(world, block, x, y, z, quota);
            if (sample.result == MalisisCombatGeometry.Result.BUDGET) return GeometrySample.BUDGET;
            if (sample.result != MalisisCombatGeometry.Result.SAFE) return GeometrySample.UNKNOWN;
            return new GeometrySample(GeometryResult.SAFE, sample.body, sample.doorCell);
        }
    }

    private static final class WorldContentImpl implements WorldExtraContentProvider {

        @Override
        public boolean isBubbleColumn(Block block) {
            return EtFuturumBlocks.isBubbleColumn(block);
        }

        @Override
        public boolean isHoneyBlock(Block block) {
            return EtFuturumBlocks.isHoney(block);
        }

        @Override
        public boolean isWitherRose(Block block) {
            return EtFuturumBlocks.isWitherRose(block);
        }

        @Override
        public boolean isMagmaBlock(Block block) {
            return EtFuturumBlocks.isMagma(block);
        }

        @Override
        public boolean isArmorStand(Entity entity) {
            return EtFuturumEntities.isArmorStand(entity);
        }

        @Override
        public boolean isElytraItem(String itemId) {
            return EtFuturumItems.isElytra(itemId);
        }

        @Override
        public Entity vehicleOf(Entity passenger) {
            return MaidVehiclePlatform.vehicle(passenger);
        }

        @Override
        public Entity controlledVehicleOf(EntityPlayer player) {
            return MaidVehiclePlatform.controlledVehicle(player);
        }

        @Override
        public boolean canAddPassenger(Entity vehicle) {
            return MaidVehiclePlatform.canAddPassenger(vehicle);
        }

        @Override
        public void mountVehicle(Entity maid, Entity vehicle) {
            MaidVehiclePlatform.mount(maid, vehicle);
        }

        @Override
        public String barrelSound(boolean opening) {
            return EtFuturumSounds.barrel(opening);
        }

        @Override
        public void playShrineFrameSound(World world, int x, int y, int z, boolean inserted) {
            EtFuturumSounds.shrineFrame(world, x, y, z, inserted);
        }
    }

    private static final class NutritionProviderImpl implements NutritionCompatProvider {

        @Override
        public float[] foodValues(ItemStack stack) {
            if (!OptionalApi.isModLoaded(AppleCoreCompat.MOD_ID)) {
                return NutritionCompatProvider.NOOP.foodValues(stack);
            }
            return AppleCoreNutrition.values(stack);
        }

        @Override
        public CustomFoodData inspectCustomFood(ItemStack stack) throws ReflectiveOperationException {
            if (!OptionalApi.isModLoaded(GregTechCompat.MOD_ID)) return null;
            return GregTechFoodAccess.inspect(stack);
        }
    }

    private static final class LifecycleStorageImpl implements LifecycleStorageCompatProvider {

        @Override
        public void beforeLedgerWrite() throws Exception {
            HodgepodgeWorldDataBarrier.beforeLedgerWrite();
        }

        @Override
        public int sectionBlockId(NBTTagCompound section, int index) {
            return EndlessIdsSectionData.blockId(section, index);
        }

        @Override
        public int sectionMetadata(NBTTagCompound section, int index) {
            return EndlessIdsSectionData.metadata(section, index);
        }

        @Override
        public void prepareInitialSize(EntityMaid maid, UUID actor) {
            WitcheryInitialSize.prepare(maid, actor);
        }
    }
}
