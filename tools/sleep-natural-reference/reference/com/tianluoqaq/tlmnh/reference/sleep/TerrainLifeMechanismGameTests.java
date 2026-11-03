package com.tianluoqaq.tlmnh.reference.sleep;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@GameTestHolder("tlmnh_terrain_life_mechanisms")
@PrefixGameTestTemplate(false)
public final class TerrainLifeMechanismGameTests {
    static final String RESOURCE = "/tlmnh-terrain-life-mechanisms/contract.json", CONTRACT = "TERRAIN_LIFE_MECHANISMS_V2";
    private TerrainLifeMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 600, batch = "terrain_circuit")
    public static void terrainCircuit(GameTestHelper h) { run(h, "terrain_circuit"); }
    @GameTest(template = "cell", timeoutTicks = 600, batch = "terrain_fire")
    public static void fireCliff(GameTestHelper h) { run(h, "fire_cliff"); }
    @GameTest(template = "cell", timeoutTicks = 480, batch = "terrain_creeper")
    public static void nativeCreeper(GameTestHelper h) { run(h, "native_creeper"); }
    @GameTest(template = "cell", timeoutTicks = 2320, batch = "terrain_pantry")
    public static void pantryNear(GameTestHelper h) { run(h, "pantry_near"); }
    @GameTest(template = "cell", timeoutTicks = 1120, batch = "terrain_approach")
    public static void pantryApproach(GameTestHelper h) { run(h, "pantry_approach"); }

    static void run(GameTestHelper h, String mechanism) {
        FollowMechanismGameTests.Session session = mechanism.startsWith("pantry") ? new PantrySession(h, mechanism) : new Session(h, mechanism);
        FollowMechanismGameTests.collect(h, session, "tlmnh.terrainLifeMechanismsProbe");
    }

    static java.lang.reflect.Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Reviewed native input field unavailable", error); }
    }

    static JsonObject raw(Entity entity, net.minecraft.core.BlockPos origin) {
        var row = new JsonObject();
        row.addProperty("class", entity.getClass().getName());
        row.addProperty("uuid", entity.getUUID().toString());
        row.addProperty("age", entity.tickCount);
        row.addProperty("alive", entity.isAlive());
        row.addProperty("registered", entity.level() instanceof net.minecraft.server.level.ServerLevel level && level.getEntity(entity.getUUID()) == entity);
        row.addProperty("dimension", entity.level().dimension().location().toString());
        row.add("position", vector(entity.position().subtract(origin.getX(), origin.getY(), origin.getZ())));
        var tag = new CompoundTag();
        entity.saveWithoutId(tag);
        row.add("binaryNBT", SleepAttributeSnapshot.binaryNbt(tag));
        if (entity instanceof LivingEntity living) {
            row.addProperty("health", living.getHealth());
            row.add("physicalConditions", physical(living));
        }
        return row;
    }

    static final java.lang.reflect.Field ATTRIBUTE_INSTANCES = field(AttributeMap.class, "attributes");
    static final java.lang.reflect.Field ATTRIBUTE_SUPPLIER = field(AttributeMap.class, "supplier");
    static final java.lang.reflect.Field DEFAULT_INSTANCES = field(net.minecraft.world.entity.ai.attributes.AttributeSupplier.class, "instances");

    @SuppressWarnings("unchecked")
    static JsonObject physical(LivingEntity entity) {
        var result = new JsonObject();
        result.addProperty("width", entity.getBbWidth());
        result.addProperty("height", entity.getBbHeight());
        result.addProperty("eyeHeight", entity.getEyeHeight());
        var attributes = new JsonArray();
        try {
            var instances = (java.util.Map<Attribute, AttributeInstance>) ATTRIBUTE_INSTANCES.get(entity.getAttributes());
            var defaults = (java.util.Map<Attribute, AttributeInstance>) DEFAULT_INSTANCES.get(ATTRIBUTE_SUPPLIER.get(entity.getAttributes()));
            int count = instances.size();
            for (var attribute : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE) {
                if (!entity.getAttributes().hasAttribute(attribute)) continue;
                var row = new JsonObject();
                row.addProperty("name", net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString());
                row.addProperty("base", entity.getAttributes().getBaseValue(attribute));
                row.addProperty("value", entity.getAttributes().getValue(attribute));
                var instance = instances.get(attribute);
                row.addProperty("storage", instance == null ? "supplier_default" : "instance");
                if (instance == null) instance = defaults.get(attribute);
                if (instance == null) throw new IllegalStateException("Actual attribute value has no inspectable source");
                row.add("modifiers", SleepAttributeSnapshot.modifiers(instance));
                attributes.add(row);
            }
            if (instances.size() != count) throw new IllegalStateException("Physical observation materialized lazy attributes");
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        result.add("attributes", attributes);
        var potions = new JsonArray();
        for (var effect : entity.getActiveEffects()) potions.add(SleepAttributeSnapshot.binaryNbt(effect.save(new CompoundTag())));
        result.add("activePotionEffects", potions);
        return result;
    }

    static JsonArray vector(Vec3 v) { var a = new JsonArray(); a.add(v.x); a.add(v.y); a.add(v.z); return a; }

    static void enclose(GameTestHelper helper, int size, int height) {
        for (int x = 0; x < size; x++) for (int y = 1; y < height; y++) for (int z = 0; z < size; z++)
            if (x == 0 || x == size - 1 || z == 0 || z == size - 1 || y == height - 1)
                helper.setBlock(x, y, z, Blocks.GLASS);
    }

    public static final class Session extends FollowMechanismGameTests.Session {
        static final java.lang.reflect.Field OWNER_PROTECTION = field(net.minecraft.server.level.ServerPlayer.class, "spawnInvulnerableTime");
        final String mechanism;
        final JsonArray damage = new JsonArray();
        Creeper creeper;
        int waypoint, reachedAt = -1;
        boolean oldFire, oldGrief;

        Session(GameTestHelper h, String mechanism) {
            super(h, mechanism, RESOURCE, CONTRACT, "terrain-life-original-v1", "terrain-life-original-");
            this.mechanism = mechanism;
            output.add("damageEvents", damage);
        }

        @Override String identityProperty() { return "tlmnh.terrainLifeMechanisms.buildIdentity"; }
        @Override int frameLimit() { return scene.get("frames").getAsInt(); }

        @Override void setup() {
            super.setup();
            oldFire = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
            oldGrief = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(false, level.getServer());
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
            for (int x = 0; x < 33; x++) for (int y = 0; y < 12; y++) for (int z = 0; z < 33; z++)
                helper.setBlock(x, y, z, y == 0 ? Blocks.STONE : Blocks.AIR);
            if (mechanism.equals("terrain_circuit")) {
                for (int x = 10; x <= 13; x++) for (int z = 10; z <= 14; z++) helper.setBlock(x, 1, z, Blocks.STONE_SLAB);
                for (int x = 14; x <= 24; x++) for (int z = 10; z <= 14; z++) helper.setBlock(x, 1, z, Blocks.STONE);
                for (int x = 18; x <= 24; x++) for (int z = 15; z <= 19; z++) helper.setBlock(x, 1, z, Blocks.STONE);
                for (int x = 16; x <= 19; x++) for (int y = 2; y <= 4; y++) {
                    helper.setBlock(x, y, 11, Blocks.STONE); helper.setBlock(x, y, 13, Blocks.STONE);
                }
            } else if (mechanism.equals("fire_cliff")) {
                for (int x = 0; x < 33; x++) for (int z = 0; z < 33; z++)
                    if (!(x >= 10 && x <= 14 && z >= 8 && z <= 16)) helper.setBlock(x, 8, z, Blocks.STONE);
                for (int x = 5; x <= 6; x++) for (int z = 11; z <= 13; z++) helper.setBlock(x, 9, z, Blocks.FIRE);
            }
            enclose(helper, 33, 12);
            plannedOwner = absolute(scene.getAsJsonArray("waypoints").get(0).getAsJsonArray());
            if (mechanism.equals("native_creeper")) {
                owner.setGameMode(GameType.SURVIVAL);
                owner.getAbilities().invulnerable = false;
                owner.getAbilities().mayfly = owner.getAbilities().flying = true;
            }
            beforeWorld();
            MinecraftForge.EVENT_BUS.register(this);
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            var p = absolute(scene.getAsJsonArray("maidSpawn"));
            maid.setPos(p.x, p.y, p.z);
            output.addProperty("actualStepHeight", maid.maxUpStep());
            if (mechanism.equals("native_creeper")) {
                int protection;
                try { protection = OWNER_PROTECTION.getInt(owner); }
                catch (IllegalAccessException error) { throw new IllegalStateException(error); }
                output.addProperty("ownerSpawnProtectionAtThreat", protection);
                helper.assertTrue(protection <= 0 && owner.isAlive() && !owner.getAbilities().invulnerable,
                    "Actual native owner protection has not expired");
                creeper = new Creeper(EntityType.CREEPER, level);
                var at = absolute(scene.getAsJsonArray("threatSpawn"));
                creeper.setPos(at.x, at.y, at.z);
                creeper.setPersistenceRequired();
                output.add("threatBeforeJoin", raw(creeper, origin));
                net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(creeper, level,
                    level.getCurrentDifficultyAt(creeper.blockPosition()), net.minecraft.world.entity.MobSpawnType.NATURAL, null, null);
                helper.assertTrue(level.addFreshEntity(creeper) && level.getEntity(creeper.getUUID()) == creeper,
                    "Actual native persistent Creeper rejected/replaced");
                output.add("threatInitial", raw(creeper, origin));
                var goals = new JsonArray();
                for (var goal : creeper.goalSelector.getAvailableGoals()) {
                    var row = new JsonObject();
                    row.addProperty("class", goal.getGoal().getClass().getName());
                    row.addProperty("priority", goal.getPriority());
                    goals.add(row);
                }
                output.add("actualCreeperGoals", goals);
                helper.assertTrue(creeper.isAlive() && creeper.isPersistenceRequired(), "Native threat initialization revoked actor");
            }
        }

        @Override void observe() throws Exception {
            if (maid != null && (!maid.isAlive() || !owner.isAlive())) {
                output.add("terminalActor", raw(maid, origin));
                output.add("terminalOwner", raw(owner, origin));
                output.addProperty("terminalWorldTick", level.getGameTime());
                output.addProperty("behaviorSuccess", false);
                finishObservation(!maid.isAlive() ? "maid_death" : "owner_death");
                return;
            }
            super.observe();
        }

        @Override void afterFrame(int index) {
            var row = frames.get(index).getAsJsonObject();
            row.addProperty("waypoint", waypoint);
            row.addProperty("burning", maid.isOnFire());
            row.addProperty("fallDistance", maid.fallDistance);
            row.addProperty("nativeOnGround", maid.onGround());
            row.addProperty("actualAStarSearches", "UNKNOWN_original_not_instrumented");
            if (creeper != null) {
                row.addProperty("creeperAlive", creeper.isAlive());
                row.addProperty("creeperRegistered", level.getEntity(creeper.getUUID()) == creeper);
                row.addProperty("creeperAge", creeper.tickCount);
                row.addProperty("creeperSwellDirection", creeper.getSwellDir());
                row.addProperty("creeperSwelling", creeper.getSwelling(1));
                row.addProperty("creeperTarget", creeper.getTarget() == null ? "" : creeper.getTarget().getUUID().toString());
                row.add("creeperPosition", relative(creeper.position()));
                row.addProperty("dangerDistanceSquared", maid.distanceToSqr(creeper));
            } else {
                var points = scene.getAsJsonArray("waypoints");
                if (waypoint < points.size() && maid.distanceToSqr(absolute(points.get(waypoint).getAsJsonArray())) <= 4) {
                    var event = new JsonObject();
                    event.addProperty("frame", index); event.addProperty("kind", "owner_waypoint_reached");
                    event.addProperty("waypoint", waypoint++); event.addProperty("phase", "END_after_recorded_frame");
                    events.add(event);
                    if (waypoint < points.size()) plannedOwner = absolute(points.get(waypoint).getAsJsonArray());
                    else reachedAt = index;
                }
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("arrivalFrame", reachedAt);
                output.addProperty("behaviorSuccess", creeper == null ? reachedAt >= 0 : maid.isAlive());
                if (creeper != null) output.add("threatFinal", raw(creeper, origin));
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.getEntity() != maid && event.getEntity() != owner) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("victim", event.getEntity() == maid ? "maid" : "owner");
            row.addProperty("amount", event.getAmount()); row.addProperty("healthBefore", event.getEntity().getHealth());
            row.addProperty("type", event.getSource().getMsgId());
            row.addProperty("explosion", event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION));
            row.addProperty("canceled", event.isCanceled()); damage.add(row);
        }

        @Override JsonArray layout() {
            var rows = new JsonArray();
            for (int x = 0; x < 33; x++) for (int y = 0; y < 12; y++) for (int z = 0; z < 33; z++) {
                var state = level.getBlockState(origin.offset(x, y, z));
                if (state.isAir()) continue;
                var row = new JsonObject();
                row.addProperty("x", x); row.addProperty("y", y); row.addProperty("z", z);
                row.addProperty("registry", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                row.addProperty("rawBlockState", state.toString()); rows.add(row);
            }
            return rows;
        }

        @Override void close() {
            if (closed) return;
            try {
                MinecraftForge.EVENT_BUS.unregister(this);
                if (creeper != null) creeper.discard();
                for (int x = 0; x < 33; x++) for (int y = 0; y < 12; y++) for (int z = 0; z < 33; z++) helper.setBlock(x, y, z, Blocks.AIR);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(oldFire, level.getServer());
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(oldGrief, level.getServer());
            } finally { super.close(); }
        }
    }

    public static final class PantrySession extends ActivityCompetitionGameTests.Session {
        int consumed, externalMouths, lastBites, epoch, placedAt = -1;

        PantrySession(GameTestHelper h, String mechanism) {
            super(h, mechanism, RESOURCE, CONTRACT, "terrain-life-original-v1", "terrain-life-original-");
        }

        @Override String identityProperty() { return "tlmnh.terrainLifeMechanisms.buildIdentity"; }

        @Override void setup() {
            super.setup();
            enclose(helper, 25, 8);
        }

        @Override JsonArray layout() {
            var rows = super.layout();
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (row.get("registry").getAsString().equals("minecraft:glass")) row.addProperty("semantic", "glass");
            }
            return rows;
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            var p = absolute(scene.getAsJsonArray("primarySpawn"));
            var q = absolute(scene.getAsJsonArray("secondarySpawn"));
            maid.setPos(p.x, p.y, p.z); second.setPos(q.x, q.y, q.z);
            maid.setHunger(0);
            if (scene.get("homeEnabled").getAsBoolean()) for (var actor : java.util.List.of(maid, second)) {
                actor.getSchedulePos().setHomeModeEnable(actor, actor.blockPosition());
                actor.setHomeModeEnable(true);
                actor.restrictTo(actor.blockPosition(), 12);
            }
            if (scene.get("backpackCake").getAsInt() > 0) maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.CAKE));
            output.addProperty("initialNonEmptyItemStackCount", scene.get("backpackCake").getAsInt());
            output.add("secondInitial", raw(second));
            output.add("secondAttributeInputs", SleepAttributeSnapshot.capture(second));
            output.addProperty("pantryScope", "natural roots/cooldowns; original2.5 approach versus near entry; no RNG reset or direct task calls");
        }

        @Override void afterFrame(int index) {
            super.afterFrame(index);
            var row = frames.get(index).getAsJsonObject();
            row.add("maidLifeTargets", targets(maid)); row.add("secondLifeTargets", targets(second));
            if (index + 1 == frameLimit()) {
                output.addProperty("naturalPlacementFrame", placedAt);
                output.addProperty("behaviorSuccess", triggerAt >= 0 && releaseAt >= 0
                    && (mechanism.equals("pantry_approach") || placedAt >= 0));
                output.addProperty("resourceAssertionScope", "near: consumed + external withdrawn + world remaining + backpack*7 ==14; approach: original one-cake guard");
            }
        }

        JsonObject targets(EntityMaid actor) {
            var row = new JsonObject();
            var target = actor.getBrain().getMemory(InitEntities.TARGET_POS.get());
            if (target.isPresent()) row.add("workTarget", relative(target.get().currentPosition()));
            row.addProperty("edibleAction", actor.getBrain().getMemory(InitEntities.MAID_EDIBLE_BLOCK_ACTION.get()).map(Object::toString).orElse("none"));
            var walk = actor.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            if (walk.isPresent()) row.add("walkTargetPosition", relative(walk.get().getTarget().currentPosition()));
            row.addProperty("backpackCake", count(actor, Items.CAKE));
            return row;
        }

        @Override void cakeFrame(int index, JsonObject row) {
            if (mechanism.equals("pantry_approach")) { super.cakeFrame(index, row); return; }
            int backpack = count(maid, Items.CAKE) + count(second, Items.CAKE);
            boolean present = level.getBlockState(origin.offset(12, 2, 12)).is(Blocks.CAKE);
            int bites = present ? cakeBites() : 7;
            if (epoch == 1 && present) {
                helper.assertTrue(backpack == 0, "Unowned new cake appeared while original backpack cake remains");
                epoch = 2; lastBites = 0; placedAt = index;
                event(index, "natural_backpack_cake_placement");
            }
            if (epoch != 1) {
                int delta = bites - lastBites;
                helper.assertTrue(delta >= 0 && delta <= 2, "Impossible shared mouth change outside two natural actors");
                consumed += delta; lastBites = bites;
            }
            int remaining = present ? 7 - bites : 0;
            helper.assertTrue(consumed + externalMouths + remaining + backpack * 7 == 14, "Original finite pantry lost cake mouth conservation");
            row.addProperty("cakeBitesConsumed", consumed); row.addProperty("externalWithdrawnMouths", externalMouths);
            row.addProperty("backpackCake", backpack); row.addProperty("worldCakeRemainingMouths", remaining);
            if (triggerAt < 0 && consumed > 0) {
                triggerAt = index; event(index, "first_shared_cake_bite_wait");
                maid.setInSittingPose(true); second.setInSittingPose(true);
            } else if (triggerAt >= 0 && releaseAt < 0 && index == triggerAt + 40) {
                releaseAt = index; event(index, "release_wait_and_withdraw_remaining_first_cake");
                maid.setInSittingPose(false); second.setInSittingPose(false);
                externalMouths = remaining;
                level.setBlock(origin.offset(12, 2, 12), Blocks.AIR.defaultBlockState(), 3);
                epoch = 1; lastBites = 0;
            }
        }
    }
}
