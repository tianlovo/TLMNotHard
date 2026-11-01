package com.tianluoqaq.tlmnh.common.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.development.shield.MaidShieldFixtures;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 真实物品与Forge伤害组件；不登记实体，不把直接伤害调用冒充飞行碰撞或自然举盾AI */
public final class MaidShieldNativeProbe {

    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();

    private MaidShieldNativeProbe() {}

    public static void run(WorldServer world) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || world == null || world.isRemote)
            throw new IllegalStateException("Development server required");
        var output = new JsonObject();
        output.addProperty("schema", "tlmnh-shield-native-v1");
        output.addProperty("version", Tags.VERSION);
        output.addProperty("worldNatural", false);
        output.addProperty("realArrowCollisionVerified", false);
        output.addProperty("clientRenderingVerified", false);
        output.addProperty(
            "scope",
            "unregistered actual EntityMaid; actual registered shields and ToolBuilder; direct attackEntityFrom and Forge events; no manual event posting or replacement damage algorithm");
        var rows = new JsonArray();
        output.add("cases", rows);
        Throwable failure = null;
        try {
            var construction = new JsonObject();
            output.add("construction", construction);
            Map<String, ItemStack> prototypes = MaidShieldFixtures.createWoodShields(construction);
            try (var identityActor = new Fixture(world, null, "identity", "none")) {
                output.add("identityFixtureCleanup", identityActor.cleanup);
                output.add(
                    "runtimeIdentity",
                    DevelopmentArtifactIdentity.capture(
                        MaidShieldNativeProbe.class,
                        MaidShieldFixtures.class,
                        EntityMaid.class,
                        DevelopmentArtifactIdentity.class,
                        Fixture.class,
                        Observer.class,
                        Canceller.class,
                        identityActor.maid.getShieldController()
                            .getClass(),
                        identityActor.maid.getItemUseState()
                            .getClass(),
                        Class.forName("com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldEvents"),
                        Class.forName("com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems"),
                        Class.forName("com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldRules"),
                        Class.forName("com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator"),
                        Class.forName("com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime")));
            }
            for (var entry : prototypes.entrySet()) {
                String id = entry.getKey();
                boolean tinkers = id.startsWith("tinkersdefense:");
                hit(world, rows, id + "/front", entry.getValue(), 0, 6, "projectile", "none");
                hit(world, rows, id + "/back", entry.getValue(), 180, 6, "projectile", "none");
                hit(world, rows, id + "/unblockable", entry.getValue(), 0, 6, "unblockable", "none");
                hit(world, rows, id + "/without_entity", entry.getValue(), 180, 6, "without_entity", "none");
                hit(
                    world,
                    rows,
                    id + "/wear_below_half",
                    entry.getValue(),
                    0,
                    tinkers ? 1.49F : 0.49F,
                    "projectile",
                    "none");
                hit(
                    world,
                    rows,
                    id + "/wear_at_half",
                    entry.getValue(),
                    0,
                    tinkers ? 1.5F : 0.5F,
                    "projectile",
                    "none");
            }
            String wood = "battlegear2:shield.wood", round = "tinkersdefense:Round Shield";
            for (String id : new String[] { wood, round }) {
                for (double angle : new double[] { -60.01, -60, -59.99, 59.99, 60, 60.01 })
                    hit(world, rows, id + "/angle_" + angle, prototypes.get(id), angle, 6, "projectile", "none");
            }
            hit(world, rows, wood + "/idle_melee", prototypes.get(wood), 0, 6, "melee", "none");
            hit(world, rows, wood + "/cancel_attack", prototypes.get(wood), 0, 6, "projectile", "attack");
            hit(world, rows, wood + "/cancel_hurt", prototypes.get(wood), 0, 6, "projectile", "hurt");
            hit(world, rows, "no_shield/projectile", null, 0, 6, "projectile", "none");
            invulnerability(world, rows, prototypes.get(wood));
            breaker(world, rows, prototypes);
            var worn = prototypes.get(wood)
                .copy();
            worn.setItemDamage(worn.getMaxDamage());
            hit(world, rows, wood + "/exhaustion", worn, 0, 6, "projectile", "none");
            for (String id : new String[] { round, "tinkersdefense:Heater Shield" }) {
                var broken = prototypes.get(id)
                    .copy();
                broken.getTagCompound()
                    .getCompoundTag("InfiTool")
                    .setBoolean("Broken", true);
                hit(world, rows, id + "/explicit_broken_input", broken, 0, 6, "projectile", "none");
            }
        } catch (Exception | LinkageError | AssertionError problem) {
            failure = problem;
        }
        output.addProperty("executionComplete", failure == null);
        if (failure != null) {
            output.addProperty(
                "errorType",
                failure.getClass()
                    .getName());
            output.addProperty("error", failure.getMessage());
        }
        try {
            Path directory = directory();
            String name = java.util.UUID.randomUUID() + ".json";
            byte[] bytes = GSON.toJson(output)
                .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8_388_608)
                throw new IllegalStateException("Shield evidence exceeds bounded artifact size");
            Files.write(directory.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var summary = new JsonObject();
            summary.addProperty("executionComplete", failure == null);
            summary.addProperty("capture", "tlmnh-dev/shield-native-results/" + name);
            summary.addProperty("bytes", bytes.length);
            summary.addProperty("sha256", sha(bytes));
            summary.addProperty("rows", rows.size());
            TLMNotHard.LOG.info("TLMNH_SHIELD_NATIVE_CAPTURE {}", summary);
        } catch (Exception problem) {
            if (failure != null) problem.addSuppressed(failure);
            throw new IllegalStateException("Shield evidence could not be saved", problem);
        }
        if (failure != null) throw new IllegalStateException("Shield capture incomplete", failure);
    }

    private static void hit(WorldServer world, JsonArray rows, String id, ItemStack prototype, double angle,
        float amount, String sourceKind, String cancel) {
        var row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("plannedSourceAngle", angle);
        row.addProperty("sourceKind", sourceKind);
        row.addProperty("cancel", cancel);
        rows.add(row);
        try (var fixture = new Fixture(world, prototype == null ? null : prototype.copy(), id, cancel)) {
            row.add("events", fixture.events);
            row.add("cleanup", fixture.cleanup);
            fixture.positionOpponent(angle);
            row.add("hit", fixture.hit(sourceKind, amount));
        }
    }

    private static void invulnerability(WorldServer world, JsonArray rows, ItemStack shield) {
        var row = new JsonObject();
        row.addProperty("id", "native_invulnerability_differences");
        rows.add(row);
        try (var fixture = new Fixture(world, shield.copy(), "invulnerability", "none")) {
            row.add("events", fixture.events);
            row.add("cleanup", fixture.cleanup);
            fixture.positionOpponent(0);
            row.add("first", fixture.hit("projectile", 6));
            row.add("sameDamage", fixture.hit("projectile", 6));
            row.add("largerDamage", fixture.hit("projectile", 8));
        }
    }

    private static void breaker(WorldServer world, JsonArray rows, Map<String, ItemStack> prototypes) {
        var row = new JsonObject();
        row.addProperty("id", "axe_cooldown_item_identity");
        rows.add(row);
        String wood = "battlegear2:shield.wood", iron = "battlegear2:shield.iron";
        try (var fixture = new Fixture(
            world,
            prototypes.get(wood)
                .copy(),
            "breaker",
            "none")) {
            row.add("events", fixture.events);
            row.add("cleanup", fixture.cleanup);
            fixture.positionOpponent(0);
            row.add("raise", fixture.hit("projectile", 6));
            fixture.maid.hurtResistantTime = 0;
            fixture.opponent.setCurrentItemOrArmor(0, new ItemStack(Items.iron_axe));
            row.add("axe", fixture.hit("melee", 6));
            row.addProperty(
                "woodCooldownAfterAxe",
                fixture.maid.getShieldController()
                    .getCooldownRemaining(
                        prototypes.get(wood)
                            .getItem()));
            fixture.maid.setOffhandItem(
                prototypes.get(wood)
                    .copy());
            fixture.maid.hurtResistantTime = 0;
            row.add("sameItemNewStack", fixture.hit("projectile", 6));
            fixture.maid.setOffhandItem(
                prototypes.get(iron)
                    .copy());
            fixture.maid.hurtResistantTime = 0;
            row.add("differentItem", fixture.hit("projectile", 6));
            row.addProperty(
                "woodCooldownFinal",
                fixture.maid.getShieldController()
                    .getCooldownRemaining(
                        prototypes.get(wood)
                            .getItem()));
            row.addProperty(
                "ironCooldownFinal",
                fixture.maid.getShieldController()
                    .getCooldownRemaining(
                        prototypes.get(iron)
                            .getItem()));
        }
    }

    private static final class Fixture implements AutoCloseable {

        final WorldServer world;
        final String fixtureId;
        final EntityMaid maid;
        final EntityZombie opponent;
        final EntityArrow arrow;
        final JsonArray events = new JsonArray();
        final JsonObject cleanup = new JsonObject();
        final Observer observer;
        final Canceller canceller;

        Fixture(WorldServer world, ItemStack shield, String id, String cancel) {
            this.world = world;
            this.fixtureId = id;
            maid = new EntityMaid(world);
            opponent = new EntityZombie(world);
            arrow = new EntityArrow(world);
            var spawn = world.getSpawnPoint();
            maid.setPosition(spawn.posX + 0.5D, 160, spawn.posZ + 0.5D);
            maid.rotationYaw = 0;
            maid.setOffhandItem(shield);
            arrow.setPosition(maid.posX, 160, maid.posZ - 5);
            arrow.shootingEntity = opponent;
            canceller = new Canceller(maid, cancel);
            observer = new Observer(maid, events);
            try {
                MinecraftForge.EVENT_BUS.register(canceller);
                MinecraftForge.EVENT_BUS.register(observer);
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void positionOpponent(double degrees) {
            double angle = Math.toRadians(degrees);
            opponent.setPosition(maid.posX - Math.sin(angle) * 5, 160, maid.posZ + Math.cos(angle) * 5);
        }

        JsonObject hit(String kind, float amount) {
            DamageSource source = "melee".equals(kind) ? DamageSource.causeMobDamage(opponent)
                : "without_entity".equals(kind) ? new DamageSource("tlmnh_shield_component").setProjectile()
                    : DamageSource.causeArrowDamage(arrow, opponent);
            if ("unblockable".equals(kind)) source.setDamageBypassesArmor();
            var result = new JsonObject();
            result.addProperty("fixtureId", fixtureId);
            result.addProperty("amount", amount);
            result.add("attackerMainHand", MaidShieldFixtures.snapshot(opponent.getHeldItem()));
            result.addProperty("projectile", source.isProjectile());
            result.addProperty("unblockable", source.isUnblockable());
            result.addProperty("sourceHasEntity", source.getEntity() != null);
            result.addProperty("sourceType", source.getDamageType());
            result.addProperty(
                "sourceEntityId",
                source.getEntity() == null ? ""
                    : source.getEntity()
                        .getUniqueID()
                        .toString());
            result.addProperty(
                "directEntityId",
                source.getSourceOfDamage() == null ? ""
                    : source.getSourceOfDamage()
                        .getUniqueID()
                        .toString());
            result.addProperty("sourceEntityIsOpponent", source.getEntity() == opponent);
            result.addProperty("directSourceIsArrow", source.getSourceOfDamage() == arrow);
            result.add("sourcePosition", position(source.getEntity()));
            result.add("directPosition", position(source.getSourceOfDamage()));
            result.add("before", snapshot());
            result.addProperty("eventCountBefore", events.size());
            result.addProperty("nativeAttackResult", maid.attackEntityFrom(source, amount));
            result.addProperty("eventCountAfter", events.size());
            result.add("after", snapshot());
            return result;
        }

        JsonObject snapshot() {
            var result = new JsonObject();
            result.addProperty("health", maid.getHealth());
            result.addProperty("absorption", maid.getAbsorptionAmount());
            result.addProperty("hurtResistantTime", maid.hurtResistantTime);
            result.addProperty("yaw", maid.rotationYaw);
            result.addProperty("entityTick", maid.ticksExisted);
            result.addProperty("worldTime", world.getTotalWorldTime());
            result.addProperty("maidRegistered", world.loadedEntityList.contains(maid));
            result.addProperty("opponentRegistered", world.loadedEntityList.contains(opponent));
            result.addProperty("arrowRegistered", world.loadedEntityList.contains(arrow));
            result.add("maidPosition", position(maid));
            result.add("offhand", MaidShieldFixtures.snapshot(maid.getOffhandItem()));
            result.addProperty("usingItemPose", maid.hasPose(MaidPose.USING_ITEM));
            result.addProperty("usingOffhandPose", maid.hasPose(MaidPose.USING_OFFHAND));
            result.add(
                "itemUseState",
                GSON.toJsonTree(
                    maid.getItemUseState()
                        .getSnapshot()));
            result.add(
                "shieldState",
                GSON.toJsonTree(
                    maid.getShieldController()
                        .getSnapshot()));
            result.add(
                "lastBlock",
                GSON.toJsonTree(
                    maid.getShieldController()
                        .getLastBlock()));
            return result;
        }

        @Override
        public void close() {
            Throwable failure = null;
            Runnable[] actions = { () -> MinecraftForge.EVENT_BUS.unregister(observer),
                () -> MinecraftForge.EVENT_BUS.unregister(canceller), maid::setDead, opponent::setDead,
                arrow::setDead };
            String[] names = { "observerUnregisterCompleted", "cancellerUnregisterCompleted", "maidSetDeadCompleted",
                "opponentSetDeadCompleted", "arrowSetDeadCompleted" };
            for (int i = 0; i < actions.length; i++) {
                try {
                    actions[i].run();
                    cleanup.addProperty(names[i], true);
                } catch (RuntimeException | Error problem) {
                    cleanup.addProperty(names[i], false);
                    if (failure == null) failure = problem;
                    else if (failure != problem) failure.addSuppressed(problem);
                }
            }
            cleanup.addProperty("maidDead", maid.isDead);
            cleanup.addProperty("opponentDead", opponent.isDead);
            cleanup.addProperty("arrowDead", arrow.isDead);
            cleanup.addProperty(
                "actorsRegistered",
                world.loadedEntityList.contains(maid) || world.loadedEntityList.contains(opponent)
                    || world.loadedEntityList.contains(arrow));
            if (failure != null) throw new IllegalStateException("Shield fixture cleanup failed", failure);
        }
    }

    public static final class Observer {

        private final EntityMaid maid;
        private final JsonArray events;

        Observer(EntityMaid maid, JsonArray events) {
            this.maid = maid;
            this.events = events;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void attack(LivingAttackEvent event) {
            if (event.entityLiving != maid) return;
            append("attack", event.ammount, event.isCanceled());
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void hurtBefore(LivingHurtEvent event) {
            if (event.entityLiving != maid) return;
            append("hurt_before", event.ammount, event.isCanceled());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurtAfter(LivingHurtEvent event) {
            if (event.entityLiving == maid) append("hurt_after", event.ammount, event.isCanceled());
        }

        private void append(String stage, float amount, boolean cancelled) {
            var event = new JsonObject();
            event.addProperty("stage", stage);
            event.addProperty("amount", amount);
            event.addProperty("cancelled", cancelled);
            events.add(event);
        }
    }

    /** 与只读观察器分离，模拟其他模组在盾牌处理前取消伤害 */
    public static final class Canceller {

        private final EntityMaid maid;
        private final String cancel;

        Canceller(EntityMaid maid, String cancel) {
            this.maid = maid;
            this.cancel = cancel;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void attack(LivingAttackEvent event) {
            if (event.entityLiving == maid && "attack".equals(cancel)) event.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving == maid && "hurt".equals(cancel)) event.setCanceled(true);
        }
    }

    private static com.google.gson.JsonElement position(Entity entity) {
        if (entity == null) return JsonNull.INSTANCE;
        var p = new JsonArray();
        p.add(new com.google.gson.JsonPrimitive(entity.posX));
        p.add(new com.google.gson.JsonPrimitive(entity.posY));
        p.add(new com.google.gson.JsonPrimitive(entity.posZ));
        return p;
    }

    private static Path directory() throws Exception {
        Path directory = Paths.get("")
            .toRealPath();
        for (String part : new String[] { "tlmnh-dev", "shield-native-results" }) {
            directory = directory.resolve(part);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files
                    .readAttributes(
                        directory,
                        java.nio.file.attribute.DosFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS)
                    .isOther())
                throw new IllegalStateException("Capture directory is not an ordinary local directory");
        }
        return directory;
    }

    private static String sha(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            result.append(Character.forDigit((b & 255) >>> 4, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
