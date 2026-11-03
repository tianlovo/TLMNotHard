package com.tianluoqaq.tlmnh.reference.sleep;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 三机制探索：原完整Brain自然tick；原生内存玩家是输入，不冒充真实客户端登录 */
@GameTestHolder("tlmnh_follow_mechanisms")
@PrefixGameTestTemplate(false)
@Mod.EventBusSubscriber(modid = "touhou_little_maid")
public final class FollowMechanismGameTests {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Session active;

    private FollowMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 300, batch = "follow_obstacle")
    public static void temporaryObstacle(GameTestHelper helper) { collect(helper, "temporary_obstacle"); }

    @GameTest(template = "cell", timeoutTicks = 300, batch = "follow_reversal")
    public static void ownerReversal(GameTestHelper helper) { collect(helper, "owner_reversal"); }

    @GameTest(template = "cell", timeoutTicks = 300, batch = "follow_waiting")
    public static void waitingOwnerFood(GameTestHelper helper) { collect(helper, "waiting_owner_food"); }

    private static void collect(GameTestHelper helper, String mechanism) {
        collect(helper, new Session(helper, mechanism), "tlmnh.followMechanismProbe");
    }

    static void collect(GameTestHelper helper, Session session, String mode) {
        helper.assertTrue(Boolean.getBoolean(mode) && active == null,
            "Independent follow-mechanism reference mode required");
        active = session;
        helper.onEachTick(() -> {
            if (session.failure != null) helper.fail(session.failure);
            if (session.complete) helper.succeed();
        });
        try {
            session.setup();
            helper.runAfterDelay(session.contract.get("warmupTicks").getAsLong(), () -> {
                try { session.spawn(); }
                catch (Exception | LinkageError | AssertionError error) { session.abort(error); }
            });
        } catch (Exception | LinkageError | AssertionError error) { session.abort(error); }
    }

    @SubscribeEvent
    public static void living(LivingEvent.LivingTickEvent event) {
        if (active != null) active.onLiving(event.getEntity());
    }

    @SubscribeEvent
    public static void level(TickEvent.LevelTickEvent event) {
        var session = active;
        if (session == null || session.level != event.level) return;
        try {
            if (event.phase == TickEvent.Phase.START) session.beforeWorld();
            else session.observe();
        } catch (Exception | LinkageError | AssertionError error) { session.abort(error); }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null) active.abort(new IllegalStateException("Reference stopped before capture completion"));
    }

    static class Session {
        final GameTestHelper helper;
        final ServerLevel level;
        final JsonObject contract, scene, output = new JsonObject();
        final JsonArray frames = new JsonArray(), events = new JsonArray();
        final Path capture;
        final long oldTime;
        final boolean oldDaylight, oldSpawning, oldRain, oldThunder;
        final int oldRainTime, oldThunderTime, oldClearTime;
        final Difficulty oldDifficulty;
        final String expectedContract;
        BlockPos origin;
        ServerPlayer owner;
        Connection connection;
        EmbeddedChannel channel;
        EntityMaid maid;
        ItemEntity food;
        Vec3 plannedOwner;
        JsonObject ownerCall;
        int livingTicks, previousAge;
        boolean complete, closed;
        String failure;

        Session(GameTestHelper helper, String mechanism) {
            this(helper, mechanism, "/tlmnh-follow-mechanisms/contract.json", "FOLLOW_PAIRED_MECHANISMS_V1",
                "follow-paired-reference-mechanisms-v1", "follow-mechanism-original-");
        }

        Session(GameTestHelper helper, String mechanism, String resource, String expectedContract, String schema, String prefix) {
            this.helper = helper; level = helper.getLevel();
            this.expectedContract = expectedContract;
            try (var stream = FollowMechanismGameTests.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing follow-mechanism contract");
                byte[] bytes = stream.readAllBytes();
                contract = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
                output.addProperty("contractSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (Exception error) { throw new IllegalStateException(error); }
            scene = java.util.stream.StreamSupport.stream(contract.getAsJsonArray("mechanisms").spliterator(), false)
                .map(value -> value.getAsJsonObject()).filter(value -> value.get("id").getAsString().equals(mechanism))
                .findFirst().orElseThrow();
            oldTime = level.getDayTime(); oldDifficulty = level.getDifficulty();
            oldDaylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            oldSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            var data = level.getServer().getWorldData().overworldData();
            oldRain = data.isRaining(); oldThunder = data.isThundering();
            oldRainTime = data.getRainTime(); oldThunderTime = data.getThunderTime(); oldClearTime = data.getClearWeatherTime();
            capture = Path.of("captures", prefix + mechanism + "-" + System.nanoTime() + ".json");
            output.addProperty("schema", schema);
            output.addProperty("profile", "original"); output.addProperty("mechanism", mechanism);
            output.add("contract", contract.deepCopy()); output.add("scene", scene.deepCopy());
            output.add("frames", frames); output.add("events", events);
            output.addProperty("benchmarkVerdict", "NOT_EVALUATED"); output.addProperty("statisticalAdmission", false);
            output.addProperty("complete14ClassBenchmarkAccepted", false); output.addProperty("randomSourcesReset", false);
            output.addProperty("realLoggedInOwner", false); output.addProperty("ownerProfile", contract.get("ownerProfile").getAsString());
        }

        void setup() {
            helper.assertTrue(contract.get("contract").getAsString().equals(expectedContract), "Wrong follow contract");
            origin = helper.absolutePos(BlockPos.ZERO);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
            level.setDayTime(contract.get("dayTime").getAsLong());
            level.getServer().setDifficulty(Difficulty.valueOf(contract.get("difficulty").getAsString()), true);
            level.setWeatherParameters(1000000, 1000000, false, false);
            for (int x = 0; x < 17; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 9; z++)
                helper.setBlock(x, y, z, y == 0 || (z == 2 || z == 6) && y <= 3 ? Blocks.STONE : Blocks.AIR);
            plannedOwner = absolute(contract.getAsJsonArray("ownerStart"));
            owner = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "FollowOwner"));
            connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY);
            // 原EntityMaid.getOwner在服务端读取PlayerList的UUID索引，只有world登记不足以建立主人
            level.getServer().getPlayerList().placeNewPlayer(connection, owner);
            owner.setPos(plannedOwner.x, plannedOwner.y, plannedOwner.z); owner.setGameMode(GameType.CREATIVE);
            owner.getAbilities().flying = true; owner.getAbilities().mayfly = true; owner.getAbilities().invulnerable = true;
            helper.assertTrue(owner.getClass() == ServerPlayer.class && owner.connection != null && connection.isConnected()
                && level.getPlayerByUUID(owner.getUUID()) == owner && level.players().contains(owner)
                && level.getServer().getPlayerList().getPlayer(owner.getUUID()) == owner
                && level.getServer().getPlayerList().getPlayers().contains(owner), "Exact isolated native owner registration failed");
            beforeWorld();
        }

        Vec3 absolute(JsonArray values) {
            return new Vec3(origin.getX() + values.get(0).getAsDouble(), origin.getY() + values.get(1).getAsDouble(),
                origin.getZ() + values.get(2).getAsDouble());
        }

        void beforeWorld() {
            if (owner == null) return;
            ownerCall = new JsonObject(); ownerCall.add("before", ownerSnapshot(false));
            ownerCall.add("plannedPosition", relative(plannedOwner));
            ownerCall.addProperty("method", "ServerPlayer.setPos");
            owner.setPos(plannedOwner.x, plannedOwner.y, plannedOwner.z); owner.setDeltaMovement(Vec3.ZERO);
            owner.fallDistance = 0; ownerCall.add("afterCall", ownerSnapshot(false));
        }

        void spawn() {
            maid = new EntityMaid(level); maid.setOwnerUUID(owner.getUUID()); maid.setTame(true);
            maid.setTask(TaskManager.getIdleTask()); maid.setSchedule(MaidSchedule.ALL); maid.setHomeModeEnable(false);
            maid.setPickup(scene.get("foodPresent").getAsBoolean()); maid.setHunger(contract.get("hunger").getAsInt());
            maid.setInSittingPose(scene.get("initialSitting").getAsBoolean());
            maid.setModelId(contract.get("model").getAsString()); maid.setSoundPackId(contract.get("initialSoundPackId").getAsString());
            var p = absolute(contract.getAsJsonArray("maidSpawn")); maid.setPos(p.x, p.y, p.z);
            maid.setYRot(0); maid.setXRot(0); maid.setYHeadRot(0); maid.setYBodyRot(0);
            maid.yRotO = maid.xRotO = maid.yHeadRotO = maid.yBodyRotO = 0;
            if (scene.get("foodPresent").getAsBoolean()) {
                var at = absolute(contract.getAsJsonObject("food").getAsJsonArray("position"));
                food = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.APPLE));
                food.setDeltaMovement(Vec3.ZERO); food.setPickUpDelay(0);
                helper.assertTrue(level.addFreshEntity(food), "Food input registration rejected");
            }
            beforeSpawn();
            boolean added = level.addFreshEntity(maid);
            helper.assertTrue(added && maid.getOwner() == owner && !maid.isNoAi(),
                "Natural original root registration failed: added=" + added + ", exactOwner=" + (maid.getOwner() == owner)
                    + ", playerListOwner=" + (level.getServer().getPlayerList().getPlayer(owner.getUUID()) == owner)
                    + ", noAi=" + maid.isNoAi());
            livingTicks = previousAge = 0;
            output.addProperty("maidUuid", maid.getUUID().toString()); output.addProperty("entityClass", maid.getClass().getName());
            output.addProperty("brainClass", maid.getBrain().getClass().getName());
            output.add("identity", SleepNaturalGameTests.identity(identityProperty()));
            output.add("attributeInputs", SleepAttributeSnapshot.capture(maid));
            var inputs = new JsonObject(); var raw = new CompoundTag(); maid.saveWithoutId(raw);
            inputs.addProperty("rawInitialNBT", raw.toString()); inputs.add("rawInitialNbtBinary", SleepAttributeSnapshot.binaryNbt(raw));
            inputs.addProperty("model", maid.getModelId()); inputs.addProperty("task", maid.getTask().getUid().toString());
            inputs.addProperty("schedule", maid.getSchedule().name()); inputs.addProperty("hunger", maid.getHunger());
            inputs.addProperty("health", maid.getHealth()); inputs.addProperty("width", maid.getBbWidth()); inputs.addProperty("height", maid.getBbHeight());
            inputs.addProperty("pickup", maid.isPickup()); inputs.addProperty("sitting", maid.isInSittingPose());
            inputs.addProperty("homeEnabled", maid.isHomeModeEnable()); inputs.add("position", relative(maid.position()));
            inputs.addProperty("restrictionRadius", maid.getRestrictRadius());
            inputs.add("owner", ownerSnapshot(true)); inputs.add("layout", layout()); inputs.add("food", foodSnapshot());
            inputs.addProperty("difficulty", level.getDifficulty().name()); inputs.addProperty("dayTime", level.getDayTime());
            inputs.addProperty("raining", level.isRaining()); inputs.addProperty("thundering", level.isThundering());
            inputs.addProperty("worldSeed", level.getSeed()); inputs.addProperty("dimension", level.dimension().location().toString());
            output.add("actualInputs", inputs);
        }

        void beforeSpawn() {
            int nonEmpty = 0;
            for (var inventory : java.util.List.of(maid.getMaidInv(), maid.getHideInv(), maid.getMaidBauble()))
                for (int slot = 0; slot < inventory.getSlots(); slot++) if (!inventory.getStackInSlot(slot).isEmpty()) nonEmpty++;
            if (!maid.getMainHandItem().isEmpty()) nonEmpty++;
            if (!maid.getOffhandItem().isEmpty()) nonEmpty++;
            for (var stack : maid.getArmorSlots()) if (!stack.isEmpty()) nonEmpty++;
            output.addProperty("initialNonEmptyItemStackCount", nonEmpty);
            output.addProperty("initialActiveEffectCount", maid.getActiveEffects().size());
            helper.assertTrue(nonEmpty == 0 && maid.getActiveEffects().isEmpty(), "Fresh maid inventory/effects changed");
            helper.assertTrue(maid.getRestrictRadius() == contract.get("restrictionRadius").getAsFloat(), "Actual non-home radius differs from the follow input contract");
        }

        void observe() throws Exception {
            if (maid == null || maid.tickCount == 0 || complete || closed) return;
            helper.assertTrue(level.getEntity(maid.getUUID()) == maid && maid.isAlive() && !maid.isNoAi()
                && maid.getOwner() == owner && maid.tickCount == previousAge + 1 && livingTicks == 1,
                "Original actor or natural tick phase changed");
            var f = new JsonObject(); int index = frames.size();
            f.addProperty("frame", index); f.addProperty("age", maid.tickCount); f.addProperty("livingTicks", livingTicks);
            f.addProperty("gameTime", level.getGameTime()); f.addProperty("dayTime", level.getDayTime());
            f.add("position", relative(maid.position())); f.add("motion", vector(maid.getDeltaMovement()));
            f.addProperty("health", maid.getHealth()); f.addProperty("sitting", maid.isInSittingPose());
            f.add("ownerInput", ownerCall.deepCopy()); f.add("owner", ownerSnapshot(false));
            f.addProperty("walkTarget", maid.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET));
            var since = maid.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            if (since.isPresent()) f.addProperty("cantReachSince", since.get());
            f.addProperty("navigationDone", maid.getNavigation().isDone());
            var path = maid.getNavigation().getPath(); f.addProperty("pathPresent", path != null);
            if (path != null) { f.addProperty("pathLength", path.getNodeCount()); f.addProperty("pathIndex", path.getNextNodeIndex()); f.addProperty("pathCanReach", path.canReach()); }
            f.addProperty("activeActivities", maid.getBrain().getActiveActivities().toString()); f.add("food", foodSnapshot());
            frames.add(f); afterFrame(index); previousAge = maid.tickCount; livingTicks = 0; drain();
            if (frames.size() == frameLimit()) {
                output.addProperty("observationComplete", true); close(); write(); complete = true; active = null;
            }
        }

        int frameLimit() { return contract.get("framesPerActor").getAsInt(); }
        String identityProperty() { return "tlmnh.followMechanism.buildIdentity"; }
        void onLiving(Entity entity) { if (entity == maid) livingTicks++; }

        void afterFrame(int index) {
            for (var value : scene.getAsJsonArray("events")) {
                var event = value.getAsJsonObject(); if (event.get("frame").getAsInt() != index) continue;
                var recorded = event.deepCopy(); recorded.addProperty("phase", "END_after_recorded_frame");
                recorded.add("ownerBefore", ownerSnapshot(true)); recorded.addProperty("sittingBefore", maid.isInSittingPose());
                String kind = event.get("kind").getAsString();
                if (kind.equals("close_obstacle") || kind.equals("open_obstacle")) {
                    boolean close = kind.equals("close_obstacle");
                    for (int y = 1; y <= 3; y++) for (int z = 3; z <= 5; z++) helper.setBlock(8, y, z, close ? Blocks.STONE : Blocks.AIR);
                    recorded.add("layoutAfter", layout());
                } else if (kind.equals("owner_position")) plannedOwner = absolute(event.getAsJsonArray("position"));
                else if (kind.equals("release_wait")) maid.setInSittingPose(false);
                else throw new IllegalArgumentException("Unsupported follow event " + kind);
                recorded.add("nextOwnerPosition", relative(plannedOwner)); recorded.addProperty("sittingAfter", maid.isInSittingPose());
                events.add(recorded);
            }
        }

        JsonObject ownerSnapshot(boolean full) {
            var r = new JsonObject(); r.addProperty("class", owner.getClass().getName()); r.addProperty("uuid", owner.getUUID().toString());
            r.add("position", relative(owner.position())); r.add("eyePosition", relative(owner.getEyePosition()));
            r.addProperty("eyeHeight", owner.getEyeHeight()); r.addProperty("width", owner.getBbWidth()); r.addProperty("height", owner.getBbHeight());
            r.addProperty("alive", owner.isAlive()); r.addProperty("spectator", owner.isSpectator()); r.addProperty("sneaking", owner.isShiftKeyDown());
            r.addProperty("invisible", owner.isInvisible()); r.addProperty("mode", owner.gameMode.getGameModeForPlayer().getName());
            r.addProperty("flying", owner.getAbilities().flying); r.addProperty("mayFly", owner.getAbilities().mayfly);
            r.addProperty("disableDamage", owner.getAbilities().invulnerable); r.addProperty("entityInvulnerable", owner.isInvulnerable());
            r.addProperty("worldRegistered", level.getEntity(owner.getUUID()) == owner); r.addProperty("worldPlayerList", level.players().contains(owner));
            r.addProperty("connectedPlayerRegistry", level.getServer().getPlayerList().getPlayers().contains(owner));
            r.addProperty("uuidOwnerLookup", level.getServer().getPlayerList().getPlayer(owner.getUUID()) == owner);
            r.addProperty("registrationMethod", "PlayerList.placeNewPlayer with isolated EmbeddedChannel; no real client login");
            r.addProperty("nativeHandlerPresent", owner.connection != null); r.addProperty("memoryChannelOpen", channel.isOpen() && connection.isConnected());
            r.addProperty("realNetworkLogin", false); r.addProperty("dimension", owner.level().dimension().location().toString());
            if (full) { var tag = new CompoundTag(); owner.saveWithoutId(tag); r.addProperty("rawNBT", tag.toString()); r.add("binaryNBT", SleepAttributeSnapshot.binaryNbt(tag)); }
            return r;
        }

        JsonObject foodSnapshot() {
            var r = new JsonObject(); r.addProperty("present", food != null && !food.isRemoved());
            if (food != null) {
                var stack = food.getItem(); var raw = new CompoundTag(); stack.save(raw);
                r.addProperty("class", food.getClass().getName()); r.add("position", relative(food.position()));
                r.addProperty("registry", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); r.addProperty("count", stack.getCount());
                r.addProperty("itemNBT", raw.toString()); r.add("itemNbtBinary", SleepAttributeSnapshot.binaryNbt(raw));
            }
            return r;
        }

        JsonArray layout() {
            var a = new JsonArray();
            for (int x = 0; x < 17; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 9; z++) {
                var state = level.getBlockState(origin.offset(x, y, z)); if (state.isAir()) continue;
                helper.assertTrue(state.is(Blocks.STONE), "Unknown follow terrain input");
                var r = new JsonObject(); r.addProperty("x", x); r.addProperty("y", y); r.addProperty("z", z); r.addProperty("semantic", "stone");
                r.addProperty("registry", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                r.addProperty("rawBlockState", state.toString()); a.add(r);
            }
            return a;
        }

        JsonArray relative(Vec3 p) { return vector(p.subtract(origin.getX(), origin.getY(), origin.getZ())); }
        JsonArray vector(Vec3 p) { var a = new JsonArray(); a.add(p.x); a.add(p.y); a.add(p.z); return a; }

        void drain() {
            if (channel == null) return;
            Object packet; while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
            while ((packet = channel.readInbound()) != null) ReferenceCountUtil.release(packet);
        }

        void close() {
            if (closed) return; closed = true;
            if (maid != null) maid.discard(); if (food != null) food.discard();
            if (owner != null) {
                level.getServer().getPlayerList().remove(owner);
                if (level.players().contains(owner)) level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
                helper.assertTrue(!level.players().contains(owner)
                    && level.getServer().getPlayerList().getPlayer(owner.getUUID()) == null,
                    "Owned native player leaked from world or PlayerList UUID registry");
            }
            if (channel != null) { channel.finish(); drain(); }
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(oldDaylight, level.getServer());
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(oldSpawning, level.getServer()); level.setDayTime(oldTime);
            level.getServer().setDifficulty(oldDifficulty, true);
            var data = level.getServer().getWorldData().overworldData(); data.setRaining(oldRain); data.setThundering(oldThunder);
            data.setRainTime(oldRainTime); data.setThunderTime(oldThunderTime); data.setClearWeatherTime(oldClearTime);
            output.addProperty("ownCleanupComplete", true);
        }

        void write() throws Exception { Files.createDirectories(capture.getParent()); Files.writeString(capture, GSON.toJson(output) + "\n", StandardCharsets.UTF_8); }
        void abort(Throwable error) {
            failure = error.toString(); output.addProperty("error", failure); output.addProperty("observationComplete", false);
            try { close(); } catch (Throwable cleanup) { output.addProperty("cleanupError", cleanup.toString()); }
            try { write(); } catch (Exception io) { io.printStackTrace(); }
            active = null; error.printStackTrace();
        }
    }
}
