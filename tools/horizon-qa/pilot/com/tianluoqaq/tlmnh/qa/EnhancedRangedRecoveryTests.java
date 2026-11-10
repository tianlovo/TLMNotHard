package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.ArrowInterceptSolver;
import com.tianluoqaq.tlmnh.common.combat.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.combat.NativeArrowMedium;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 完整N工厂自然tick；当前友方实体是阻挡输入，不直接调用预瞄、发射或导航 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedRangedRecoveryTests {

    private EnhancedRangedRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_ranged_yield")
    public static void confirmedFriendlyObstructionYieldsThenWeaponAndBlockerChangesPermitRecovery(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "yield-and-revoke");
        var alternate = s.target(h, 18.5, 1.5);
        s.bind(alternate);
        int[] phase = { 0 };
        ItemStack[] replacement = { null };
        h.succeedWhen(() -> {
            s.sample(phase[0]);
            if (phase[0] == 0) {
                if (s.combat()
                    .rangedRefusals() == 0) return false;
                h.assertTrue(
                    s.bow()
                        .getShots() == 0 && s.ammunition.stackSize == 8
                        && s.weapon.getItemDamage() == 0,
                    "Confirmed friendly refusal spent finite resources");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                if (alternate.getHealth() == 2000) return false;
                h.assertTrue(
                    s.bow()
                        .getShots() == 1 && s.primary.getHealth() == 2000
                        && s.hits(alternate) == 1
                        && s.ammunition.stackSize == 7
                        && s.weapon.getItemDamage() == 1
                        && s.combat()
                            .rangedRecoveryMoves() == 0,
                    "Stationary refused target starved the clear alternate or movement bypassed sitting");
                // 玩家换弓、目标退出及友方让开都是实际输入，不替换目标内存或控制器
                alternate.setDead();
                replacement[0] = new ItemStack(Items.bow);
                s.f.maid.setCurrentItemOrArmor(0, replacement[0]);
                s.f.owner.setPosition(s.f.x + 8.5, s.f.y + 1, s.f.z + 7);
                s.event("clear alternate exits; held bow identity replaced; native owner leaves firing segment");
                phase[0] = 2;
            }
            if (phase[0] != 2 || s.primary.getHealth() == 2000) return false;
            h.assertTrue(
                s.bow()
                    .getShots() == 2 && s.hits(s.primary) == 1
                    && s.ammunition.stackSize == 6
                    && s.weapon.getItemDamage() == 1
                    && replacement[0].getItemDamage() == 1
                    && s.f.maid.getHeldItem() == replacement[0],
                "Old target/weapon rejection survived actual input changes or charged the wrong bow");
            s.complete = true;
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_ranged_reposition")
    public static void stationaryRefusalWaitsThenAllowedLocalNavigationRestoresOneRealShot(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "local-reposition");
        s.bind();
        int[] phase = { 0 }, refusalAt = { -1 };
        double startX = s.f.maid.posX, startZ = s.f.maid.posZ;
        h.succeedWhen(() -> {
            s.sample(phase[0]);
            if (phase[0] == 0) {
                if (s.combat()
                    .rangedRefusals() == 0) return false;
                refusalAt[0] = s.f.maid.ticksExisted;
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                h.assertTrue(
                    s.bow()
                        .getShots() == 0 && s.ammunition.stackSize == 8
                        && s.weapon.getItemDamage() == 0
                        && s.combat()
                            .rangedRecoveryMoves() == 0
                        && s.f.maid.getDistanceSq(startX, s.f.maid.posY, startZ) < .01,
                    "Stationary refusal moved or paid before permission changed");
                if (s.f.maid.ticksExisted < refusalAt[0] + 12) return false;
                s.f.maid.setSitting(false);
                s.event("actual sitting input released; original navigation budget controls local recovery");
                phase[0] = 2;
            }
            if (phase[0] != 2 || s.primary.getHealth() == 2000) return false;
            h.assertTrue(
                s.combat()
                    .rangedRecoveryMoves() > 0 && s.f.navigation.searchCount() > 0
                    && s.f.maid.getDistanceSq(startX, s.f.maid.posY, startZ) > .25
                    && s.bow()
                        .getShots() == 1
                    && s.weapon.getItemDamage() == 1
                    && s.ammunition.stackSize == 7
                    && s.hits(s.primary) == 1,
                "Permitted recovery did not use a real bounded path and exactly one paid native collision");
            s.complete = true;
            return true;
        });
    }

    public static final class Scene {

        final GameTestHelper h;
        final ModernCoreTests.Fixture f;
        final String name;
        final ItemStack weapon = new ItemStack(Items.bow), ammunition = new ItemStack(Items.arrow, 8);
        final EntityPig primary;
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray(), events = new JsonArray();
        final JsonArray damage = new JsonArray();
        final java.util.Map<EntityLivingBase, Integer> actualHits = new java.util.IdentityHashMap<>();
        boolean complete;

        Scene(GameTestHelper h, String name) {
            this.h = h;
            this.name = name;
            f = new ModernCoreTests.Fixture(h, false, 1, 24, MaidBrainProfile.ENHANCED);
            f.maid.setSchedule(MaidSchedule.ALL);
            f.maid.setTask(MaidTask.RANGED_ATTACK);
            f.maid.setSitting(true);
            f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
            f.owner.setPosition(f.x + 11.5, f.y + 1, f.z + 4.5);
            f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = null;
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.setOffhandItem(null);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, ammunition);
            primary = target(h, 15.5, 4.5);
            report.addProperty("schema", "enhanced-ranged-recovery-v1");
            report.addProperty(
                "scope",
                "complete factory N natural ticks; controlled native pigs and connected memory owner; no real network client; not O/F/N or statistical admission");
            report.addProperty("rootSuspended", false);
            report.addProperty("manualActorTick", false);
            report.add("frames", frames);
            report.add("inputEvents", events);
            report.add("nativeArrowDamage", damage);
            MinecraftForge.EVENT_BUS.register(this);
            h.afterTest(() -> {
                MinecraftForge.EVENT_BUS.unregister(this);
                // 只回收本例射手拥有的箭，不删其它case的实体
                for (Object value : new java.util.ArrayList<>(h.getWorld().loadedEntityList))
                    if (value instanceof EntityArrow arrow && arrow.shootingEntity == f.maid) arrow.setDead();
                report.addProperty("assertionsComplete", complete);
                try {
                    Files.write(
                        Paths.get(
                            System.getProperty("horizonqa.reportDir"),
                            "enhanced-ranged-recovery-" + name + ".json"),
                        new GsonBuilder().setPrettyPrinting()
                            .create()
                            .toJson(report)
                            .getBytes(StandardCharsets.UTF_8));
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot retain ranged recovery evidence", failure);
                }
            });
        }

        EntityPig target(GameTestHelper h, double x, double z) {
            var target = new EntityPig(h.getWorld());
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(2000);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.setHealth(2000);
            h.afterTest(target::setDead);
            h.spawnEntity(target, x, 1, z);
            f.maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
            return target;
        }

        EnhancedCoreBrain core() {
            return f.maid.getBrainCoordinator()
                .peekEnhancedCore();
        }

        EnhancedCombatController combat() {
            return core().combat();
        }

        MaidBowAttack bow() {
            return core().actor()
                .rangedBow();
        }

        void bind(Entity... otherTargets) throws Exception {
            h.assertTrue(
                bow().flightProfile() == MaidBowAttack.ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                    && f.maid.getOwner() == f.owner
                    && f.maid.canEntityBeSeen(primary),
                "Complete N initial factory/owner/visibility differs");
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    EnhancedCoreBrain.class,
                    EnhancedCombatController.class,
                    Class.forName("com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedRangedRecovery"),
                    MaidBowAttack.class,
                    NativeArrowMedium.class,
                    ArrowPhysics.class,
                    ArrowInterceptSolver.class,
                    MaidProjectileImpactProtection.class,
                    MaidArrowSourceState.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(
                    EnhancedRangedRecoveryTests.class,
                    Scene.class,
                    ModernCoreTests.Fixture.class,
                    SleepAttributePortSnapshot.class,
                    NaturalRangedPairTests.class));
            report.add("connectionArtifact", DevelopmentArtifactIdentity.capture(IsolatedPlayerChannel.class));
            var before = new NBTTagCompound();
            f.maid.writeToNBT(before);
            before = (NBTTagCompound) before.copy();
            report.add("maidInitial", input(f.maid));
            report.add("ownerInitial", input(f.owner));
            report.add("primaryInitial", input(primary));
            var targets = new JsonArray();
            for (var target : otherTargets) targets.add(input(target));
            report.add("otherTargetsInitial", targets);
            var after = new NBTTagCompound();
            f.maid.writeToNBT(after);
            h.assertTrue(before.equals(after), "Initial recovery input recording mutated full maid NBT");
            report.add("maidInitialBindingBefore", SleepAttributePortSnapshot.binaryNbt(before));
            report.add("maidInitialBindingAfter", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) after.copy()));
            report.addProperty("sameSideMaidInitialStructureBound", true);
            report.addProperty(
                "provider",
                h.getWorld().provider.getClass()
                    .getName());
            report.addProperty("dimension", h.getWorld().provider.dimensionId);
            report.addProperty(
                "worldTime",
                h.getWorld()
                    .getWorldTime());
        }

        JsonObject input(Entity entity) {
            var tag = new NBTTagCompound();
            entity.writeToNBT(tag);
            var row = new JsonObject();
            row.addProperty(
                "class",
                entity.getClass()
                    .getName());
            row.addProperty(
                "uuid",
                entity.getUniqueID()
                    .toString());
            row.addProperty("rawNBT", tag.toString());
            row.add("typedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) tag.copy()));
            row.addProperty(
                "registered",
                h.getWorld()
                    .getEntityByID(entity.getEntityId()) == entity);
            row.addProperty("x", entity.posX - f.x);
            row.addProperty("y", entity.posY - f.y);
            row.addProperty("z", entity.posZ - f.z);
            row.addProperty("dimension", entity.dimension);
            if (entity instanceof EntityLivingBase living)
                row.add("physicalConditions", NaturalRangedPairTests.physicalConditions(living));
            if (entity instanceof EntityPlayerMP player) {
                row.addProperty(
                    "mode",
                    player.theItemInWorldManager.getGameType()
                        .getName());
                row.addProperty("flying", player.capabilities.isFlying);
                row.addProperty("mayFly", player.capabilities.allowFlying);
                row.addProperty("disableDamage", player.capabilities.disableDamage);
                row.addProperty("entityInvulnerable", player.isEntityInvulnerable());
                row.addProperty("worldPlayerList", h.getWorld().playerEntities.contains(player));
                row.addProperty(
                    "connectedPlayerRegistry",
                    net.minecraft.server.MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList.contains(player));
                row.addProperty("nativeHandlerPresent", player.playerNetServerHandler != null);
                row.addProperty("realNetworkLogin", false);
            }
            return row;
        }

        void event(String value) {
            var row = new JsonObject();
            row.addProperty("age", f.maid.ticksExisted);
            row.addProperty("input", value);
            events.add(row);
        }

        int hits(EntityLivingBase target) {
            return actualHits.getOrDefault(target, 0);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (!(event.source.getSourceOfDamage() instanceof EntityArrow arrow) || arrow.shootingEntity != f.maid)
                return;
            h.assertTrue(
                arrow.getClass() == EntityArrow.class && arrow instanceof MaidArrowSourceState
                    && ((MaidArrowSourceState) arrow).tlmnh$source() instanceof NBTTagCompound,
                "Natural recovery damage lost exact native arrow source identity");
            var source = (NBTTagCompound) ((MaidArrowSourceState) arrow).tlmnh$source();
            h.assertTrue(
                source.getString("Shooter")
                    .equals(
                        f.maid.getUniqueID()
                            .toString())
                    && source.getString("Owner")
                        .equals(
                            f.owner.getUniqueID()
                                .toString()),
                "Natural arrow source owner differs");
            var row = new JsonObject();
            row.addProperty("age", f.maid.ticksExisted);
            row.addProperty(
                "arrow",
                arrow.getUniqueID()
                    .toString());
            row.addProperty(
                "victim",
                event.entityLiving.getUniqueID()
                    .toString());
            row.addProperty("amount", event.ammount);
            row.addProperty("canceled", event.isCanceled());
            row.add("source", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) source.copy()));
            damage.add(row);
            if (!event.isCanceled()) actualHits.merge(event.entityLiving, 1, Integer::sum);
        }

        void sample(int phase) {
            h.assertTrue(
                f.maid.isEntityAlive() && f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady()
                    && f.maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && f.owner.getHealth() == f.owner.getMaxHealth(),
                "Root/owner health changed during natural recovery");
            var row = new JsonObject();
            row.addProperty("age", f.maid.ticksExisted);
            row.addProperty("phase", phase);
            row.addProperty("x", f.maid.posX - f.x);
            row.addProperty("z", f.maid.posZ - f.z);
            row.addProperty("selected", String.valueOf(core().selectedKind()));
            row.addProperty(
                "target",
                combat().target() == null ? "none"
                    : combat().target()
                        .getUniqueID()
                        .toString());
            row.addProperty("refusals", combat().rangedRefusals());
            row.addProperty("probes", combat().rangedRecoveryProbes());
            row.addProperty("moves", combat().rangedRecoveryMoves());
            row.addProperty(
                "outcome",
                combat().rangedRecoveryOutcome()
                    .name());
            row.addProperty("shots", bow().getShots());
            row.addProperty("rejectedShots", bow().getRejectedShots());
            row.addProperty("arrows", ammunition.stackSize);
            row.addProperty("oldBowWear", weapon.getItemDamage());
            row.addProperty("nativeSearches", f.navigation.searchCount());
            row.addProperty(
                "navigation",
                core().navigation()
                    .outcome()
                    .name());
            frames.add(row);
        }
    }
}
