package com.tianluoqaq.tlmnh.qa;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.config.Configuration;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowImpactState;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.config.MaidCombatConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ProjectileImpactTests {

    private ProjectileImpactTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 230, batch = "projectile_allied_impact")
    public static void nativeAndSourceArrowsPassAlliedActorsWhoEnterAfterRelease(GameTestHelper helper) {
        requireMode(helper, false);
        var e = new Environment(helper);
        var actors = new EntityLivingBase[] { e.owner, e.wolf, e.friend, e.ally };
        int[] trial = { 0 }, step = { 0 };
        helper.succeedWhen(() -> {
            helper.assertTrue(
                !e.owner.capabilities.disableDamage && !e.ally.capabilities.disableDamage,
                "A test player became damage immune during actual arrow flight");
            if (step[0] == 0) {
                e.resetActors();
                e.newTarget();
                e.shoot(
                    trial[0] < actors.length ? ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                        : ModernFlightProfile.SOURCE_ORIGINAL);
                helper.assertTrue(
                    e.arrow instanceof MaidArrowImpactState,
                    "Normal native collision hook was not actually applied");
            }
            if (step[0] == 1) e.cross(actors[trial[0] % actors.length]);
            if (e.target.getHealth() < 200) {
                var victim = actors[trial[0] % actors.length];
                helper.assertTrue(
                    victim.getHealth() == 20 && !victim.isBurning()
                        && ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() > 0,
                    "Late allied collision was not skipped before the arrow reached its original target");
                e.items();
                helper.assertTrue(
                    MaidProjectileImpactProtection.available(e.f.maid),
                    "Working native impact incorrectly invoked failure fallback");
                System.out.println(
                    "TLMNH_ALLIED_IMPACT_PASS actor=" + victim.getClass()
                        .getName()
                        + " profile="
                        + e.bow.flightProfile()
                        + " ignored="
                        + ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts());
                e.removeArrowAndTarget();
                step[0] = 0;
                return ++trial[0] == actors.length * 2;
            }
            helper.assertTrue(
                ++step[0] < 24,
                "Late allied collision stopped the arrow instead of preserving native flight");
            return false;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 70, batch = "projectile_ignore_and_scope")
    public static void rangedIgnoreIsFreshAtImpactAndOtherShootersRemainUnchanged(GameTestHelper helper) {
        requireMode(helper, false);
        var e = new Environment(helper);
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.setHealth(200);
        helper.spawnEntity(pig, 1.5, 1, 7);
        helper.afterTest(pig::setDead);
        var config = new Configuration(
            Paths.get(System.getProperty("horizonqa.reportDir"), "impact-config.cfg")
                .toFile());
        String[] oldAttack = MaidCombatConfig.ignoredTypes(), oldRanged = MaidCombatConfig.rangedIgnoredTypes();
        config.get("combat", "MaidAttackIgnore", oldAttack)
            .set(oldAttack);
        helper.afterTest(() -> {
            config.get("combat", "MaidRangedAttackIgnore", oldRanged)
                .set(oldRanged);
            MaidCombatConfig.load(config);
        });
        int[] phase = { 0 }, step = { 0 };
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                e.newTarget();
                e.shoot(ModernFlightProfile.NATIVE_STATIC_COMPENSATION);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1 && step[0]++ == 0) {
                config.get("combat", "MaidRangedAttackIgnore", new String[] {})
                    .set(new String[] { "minecraft:pig" });
                MaidCombatConfig.load(config);
                e.cross(pig);
            }
            if (phase[0] == 1) {
                if (e.target.getHealth() == 200) return false;
                helper.assertTrue(
                    pig.getHealth() == 200 && e.arrow instanceof MaidArrowImpactState
                        && ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() > 0,
                    "Current ranged-only ignore list did not suppress actual impact");
                e.items();
                e.removeArrowAndTarget();
                pig.setPosition(e.f.x + 1.5, e.f.y + 1, e.f.z + 7);
                e.owner.setPosition(e.f.x + 8.5, e.f.y + 1, e.f.z + 4.5);
                var other = new EntityCow(helper.getWorld());
                helper.spawnEntity(other, 1.5, 1, 7);
                helper.afterTest(other::setDead);
                e.arrow = new net.minecraft.entity.projectile.EntityArrow(
                    helper.getWorld(),
                    e.f.x + 3.5,
                    e.f.y + 1.8,
                    e.f.z + 4.5);
                e.arrow.shootingEntity = other;
                e.arrow.setThrowableHeading(1, 0, 0, 3, 0);
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(e.arrow),
                    "Other shooter arrow did not spawn");
                System.out.println("TLMNH_RANGED_IGNORE_IMPACT_PASS id=" + MaidCombatEntities.id(pig));
                phase[0] = 2;
                step[0] = 0;
                return false;
            }
            if (e.owner.getHealth() == 20) {
                helper.assertTrue(
                    ++step[0] < 12,
                    "Other shooter missed player: dead=" + e.arrow.isDead
                        + " arrow="
                        + e.arrow.posX
                        + ","
                        + e.arrow.posY
                        + ","
                        + e.arrow.posZ);
                return false;
            }
            helper.assertTrue(
                e.owner.getHealth() < 20 && ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() == 0,
                "Maid-specific impact policy changed another shooter's native arrow damage");
            return true;
        });
    }

    private static void requireMode(GameTestHelper helper, boolean fallback) {
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands")
                && Boolean.getBoolean("tlmnh.qaProjectileImpactFallback") == fallback,
            "Wrong native impact test JVM mode");
    }

    public static final class DamageObservation {

        final EntityPlayerMP player;
        boolean received, canceled;

        DamageObservation(EntityPlayerMP player) {
            this.player = player;
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent(
            priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST,
            receiveCanceled = true)
        public void observe(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
            if (event.entityLiving != player) return;
            received = true;
            canceled = event.isCanceled();
        }
    }

    static final class Environment {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture f;
        final EntityPlayerMP owner, ally;
        final EntityWolf wolf;
        final EntityMaid friend;
        final ItemStack original = new ItemStack(Items.golden_apple, 3, 1);
        EntityCow target;
        net.minecraft.entity.projectile.EntityArrow arrow;
        MaidBowAttack bow;
        ItemStack weapon;

        Environment(GameTestHelper helper) {
            this.helper = helper;
            IntegrationGuard.resetForDevelopment(Side.SERVER);
            helper.afterTest(() -> IntegrationGuard.resetForDevelopment(Side.SERVER));
            f = new ModernCoreTests.Fixture(helper, false, 1, 16);
            f.maid.getBrainCoordinator()
                .setSuspended(true);
            f.maid.setTask(MaidTask.RANGED_ATTACK);
            helper.getWorld().playerEntities.remove(f.owner);
            MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.remove(f.owner);
            f.owner.setDead();
            owner = player(f.owner.getUniqueID(), f.owner.getCommandSenderName());
            ally = player(UUID.randomUUID(), "ImpactAlly");
            helper.assertTrue(f.maid.getOwner() == owner, "Actual owner lookup still used the fake placeholder");
            var scoreboard = helper.getWorld()
                .getScoreboard();
            var team = scoreboard.createTeam("qaImpact" + Integer.toHexString(f.maid.getEntityId()));
            helper.afterTest(() -> scoreboard.removeTeam(team));
            scoreboard.func_151392_a(owner.getCommandSenderName(), team.getRegisteredName());
            scoreboard.func_151392_a(ally.getCommandSenderName(), team.getRegisteredName());
            wolf = new EntityWolf(helper.getWorld());
            wolf.setTamed(true);
            wolf.func_152115_b(
                owner.getUniqueID()
                    .toString());
            wolf.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            wolf.setHealth(20);
            helper.spawnEntity(wolf, 1.5, 1, 7);
            helper.afterTest(wolf::setDead);
            friend = new EntityMaid(helper.getWorld());
            helper.assertTrue(friend.bindTo(owner.getUniqueID()), "Fresh allied maid owner binding failed");
            friend.getBrainCoordinator()
                .setSuspended(true);
            helper.spawnEntity(friend, 1.5, 1, 7);
            helper.afterTest(friend::setDead);
            original.setStackDisplayName("Projectile original owned stack");
            f.maid.setOffhandItem(original.copy());
            helper.afterTest(this::removeArrowAndTarget);
            resetActors();
        }

        private EntityPlayerMP player(UUID id, String name) {
            var server = MinecraftServer.getServer();
            var player = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(id, name),
                new ItemInWorldManager(helper.getWorld()));
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            new NetHandlerPlayServer(server, manager, player);
            IsolatedPlayerChannel.verify(manager, channel);
            helper.afterTest(() -> {
                helper.getWorld()
                    .removePlayerEntityDangerously(player);
                server.getConfigurationManager().playerEntityList.remove(player);
                player.setDead();
                channel.finish();
                Object message;
                while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
            });
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            player.capabilities.disableDamage = false;
            player.setPosition(f.x + 1.5, f.y + 1, f.z + 7);
            // 注册世界实体和碰撞，但不冒充已完成真实网络登录的全局连接玩家
            helper.assertTrue(
                !server.getConfigurationManager().playerEntityList.contains(player),
                "Isolated actor appeared in connected-player registry");
            helper.assertTrue(
                helper.getWorld()
                    .spawnEntityInWorld(player)
                    && helper.getWorld()
                        .getEntityByID(player.getEntityId()) == player,
                "Real player absent from actual world table");
            System.out.println(
                "TLMNH_IMPACT_POST_JOIN_PLAYER disableDamage=" + player.capabilities.disableDamage
                    + " gameMode="
                    + player.theItemInWorldManager.getGameType()
                        .getID());
            player.setGameType(GameType.SURVIVAL);
            player.capabilities.disableDamage = false;
            // 只取消本夹具新建玩家的60tick出生免伤；用实际伤害校准，不能利用FakePlayer恒免伤造通过
            ReflectionHelper.setPrivateValue(EntityPlayerMP.class, player, 0, "field_147101_bU");
            player.hurtResistantTime = 0;
            float before = player.getHealth();
            var observation = new DamageObservation(player);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(observation);
            boolean accepted;
            try {
                accepted = player.attackEntityFrom(DamageSource.generic, 1);
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(observation);
            }
            System.out.println(
                "TLMNH_IMPACT_DAMAGE_OBSERVATION received=" + observation.received
                    + " canceled="
                    + observation.canceled
                    + " disableDamage="
                    + player.capabilities.disableDamage
                    + " remote="
                    + player.worldObj.isRemote
                    + " hurtResistantTime="
                    + player.hurtResistantTime);
            System.out.println(
                "TLMNH_IMPACT_PLAYER_CALIBRATION class=" + player.getClass()
                    .getName()
                    + " invulnerable="
                    + player.isEntityInvulnerable()
                    + " accepted="
                    + accepted
                    + " before="
                    + before
                    + " after="
                    + player.getHealth()
                    + " birth="
                    + ReflectionHelper.getPrivateValue(EntityPlayerMP.class, player, "field_147101_bU"));
            helper.assertTrue(
                player.getClass() == EntityPlayerMP.class && !player.isEntityInvulnerable()
                    && accepted
                    && player.getHealth() < before
                    && player.getHealth() > 0,
                "Actual server player fixture is not damageable: accepted=" + accepted
                    + " before="
                    + before
                    + " after="
                    + player.getHealth());
            player.setHealth(20);
            player.hurtResistantTime = 0;
            return player;
        }

        void resetActors() {
            for (var actor : new EntityLivingBase[] { owner, ally, wolf, friend }) {
                actor.setPosition(f.x + 1.5, f.y + 1, f.z + 7);
                actor.motionX = actor.motionY = actor.motionZ = 0;
                helper.assertTrue(actor.getHealth() == 20, "Allied actor health changed");
            }
        }

        void newTarget() {
            target = new EntityCow(helper.getWorld());
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(200);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.setHealth(200);
            helper.spawnEntity(target, 15.5, 1, 4.5);
            f.maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        }

        void setupBow(ModernFlightProfile profile) {
            weapon = new ItemStack(Items.bow);
            weapon.addEnchantment(Enchantment.flame, 1);
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
            bow = new MaidBowAttack(f.maid, profile);
            bow.observe(
                target,
                true,
                helper.getWorld()
                    .getTotalWorldTime());
        }

        void shoot(ModernFlightProfile profile) {
            setupBow(profile);
            helper.assertTrue(bow.fireForModern(target, 1, () -> true), "Actual production arrow did not release");
            arrow = bow.getLastArrow();
            helper.assertTrue(arrow != null && arrow.shootingEntity == f.maid, "Projectile source identity changed");
        }

        void cross(EntityLivingBase actor) {
            actor.setPosition(
                arrow.posX + arrow.motionX * .5,
                arrow.posY + arrow.motionY * .5 - actor.height * .5,
                arrow.posZ + arrow.motionZ * .5);
            actor.motionX = actor.motionY = actor.motionZ = 0;
        }

        void items() {
            helper.assertTrue(
                ItemStack.areItemStacksEqual(original, f.maid.getOffhandItem()) && weapon.getItemDamage() == 1
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 7,
                "Allied impact changed owned offhand NBT or finite shot payment");
        }

        void unspentItems() {
            helper.assertTrue(
                ItemStack.areItemStacksEqual(original, f.maid.getOffhandItem()) && weapon.getItemDamage() == 0
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 8,
                "Refused arrow consumed finite bow/ammunition or changed original offhand NBT");
        }

        int drain(EntityPlayerMP player, String code) {
            var channel = (EmbeddedChannel) player.playerNetServerHandler.netManager.channel();
            channel.runPendingTasks();
            int count = 0;
            Object message;
            while ((message = channel.readOutbound()) != null) {
                try {
                    if (code != null && message instanceof S02PacketChat chat) {
                        var bytes = new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
                        try {
                            chat.writePacketData(bytes);
                            var component = net.minecraft.util.IChatComponent.Serializer
                                .func_150699_a(bytes.readStringFromBuffer(32767));
                            if (component instanceof ChatComponentTranslation translation
                                && Arrays.asList(translation.getFormatArgs())
                                    .contains(code))
                                count++;
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        } finally {
                            bytes.release();
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
            return count;
        }

        void removeArrowAndTarget() {
            if (arrow != null) arrow.setDead();
            if (target != null) target.setDead();
            arrow = null;
            target = null;
        }
    }
}
