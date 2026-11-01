package com.tianluoqaq.tlmnh.common.development;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityLookHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.Creation;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.EndpointRelation;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.Installation;
import com.tianluoqaq.tlmnh.common.ai.faithful.DryPlatformFollowActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowCoreComponent;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowCoreLookDriver;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowCoreMoveDriver;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowFallbackSampler;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.NativeNavigationStopSource;
import com.tianluoqaq.tlmnh.common.ai.faithful.NativeNavigationStopState;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** 未登记实体的真实native AI阶段组件采集，不是自然世界tick或真实登录主人测试 */
public final class MaidFollowCoreAdapterProbe {

    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();
    private static final int FOOT_Y = 160;
    private static final int PLATFORM_RADIUS = 32;
    private static final int DOMAIN_PADDING = 24;
    private static final Field LOOK_PENDING = lookField(boolean.class, "isLooking", "field_75655_d");
    private static final Field LOOK_X = lookField(double.class, "posX", "field_75656_e");
    private static final Field LOOK_Y = lookField(double.class, "posY", "field_75653_f");
    private static final Field LOOK_Z = lookField(double.class, "posZ", "field_75654_g");
    private static final Field LOOK_MAX_YAW = lookField(float.class, "deltaLookYaw", "field_75657_b");
    private static final Field LOOK_MAX_PITCH = lookField(float.class, "deltaLookPitch", "field_75658_c");

    private MaidFollowCoreAdapterProbe() {}

    public static void run(WorldServer world) {
        run(world, false);
    }

    public static void runFallback(WorldServer world) {
        run(world, true);
    }

    private static void run(WorldServer world, boolean fallback) {
        run(world, fallback, false);
    }

    public static void runNavigationStop(WorldServer world) {
        run(world, false, true);
    }

    private static void run(WorldServer world, boolean fallback, boolean watchdog) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !world.isRemote && world.provider.dimensionId == 0,
            "Dedicated development overworld required");
        var output = new JsonObject();
        output.addProperty(
            "schema",
            watchdog ? "follow-navigation-stop-native-v1"
                : fallback ? "follow-fallback-native-v1" : "follow-core-adapter-native-v2");
        output.addProperty("componentContract", "FOLLOW_CORE_COMPONENT_V2");
        output.addProperty("componentSchedule", "start-pass look0/move2/follow3; running-pass look0/move2/follow3");
        output.addProperty("componentWorldRngOrderVerified", false);
        output.addProperty("lookVisibilitySource", "CURRENT_NATIVE_LOS");
        output.addProperty("version", Tags.VERSION);
        output.addProperty("worldNatural", false);
        output.addProperty("equivalenceVerified", false);
        output.addProperty("realOnlineOwnerVerified", false);
        output.addProperty("globalPlayerListResolverExercised", true);
        output.addProperty(
            "scope",
            "unregistered maid; manual inherited updateAITasks; V2 look0/move2/follow3 component, native pathfinder/navigation and synthetic global-list UUID owner; read-only native look helper observations after component and inherited AI return; no natural world tick, real player login, modern sensor equivalence or combined O/F replay");
        var rows = new JsonArray();
        output.add("cases", rows);
        var platformReport = new JsonObject();
        output.add("platform", platformReport);
        Throwable failure = null;
        Platform platform = null;
        try {
            var identityClasses = new ArrayList<Class<?>>();
            java.util.Collections.addAll(
                identityClasses,
                MaidFollowCoreAdapterProbe.class,
                StageMaid.class,
                FollowCoreComponent.class,
                FollowCoreLookDriver.class,
                FollowCoreLookDriver.State.class,
                FollowCoreMoveDriver.class,
                DryPlatformFollowActor.class,
                MaidBoundedPathPlanner.class,
                MaidPolicyRuntime.class,
                EntityMaid.class,
                DevelopmentArtifactIdentity.class);
            if (fallback) identityClasses.add(FollowFallbackSampler.class);
            if (watchdog) {
                identityClasses.add(NativeNavigationStopState.class);
                identityClasses.add(NativeNavigationStopSource.class);
            }
            output
                .add("runtimeIdentity", DevelopmentArtifactIdentity.capture(identityClasses.toArray(new Class<?>[0])));
            var source = new JsonObject();
            source.addProperty(
                "worldClass",
                world.getClass()
                    .getName());
            source.addProperty(
                "chunkProviderClass",
                world.getChunkProvider()
                    .getClass()
                    .getName());
            source.addProperty("nativeQuery", "World.getEntityPathToXYZ via MaidBoundedPathPlanner.create");
            source.addProperty(
                "stage",
                "EntityLiving.updateAITasks: tasks, native navigator, updateAITick, move/look/jump");
            output.add("sourceProvider", source);
            platform = new Platform(world, platformReport, fallback, watchdog);
            if (watchdog) {
                output.addProperty(
                    "scope",
                    "controlled manual inherited AI; original native watchdog clear callsite observation, owned stop/retry and unknown-clear refusal; no modern watchdog equivalence or natural F/N");
                pipeline(platform, rows);
                watchdogRecovery(platform, rows);
                unknownClear(platform, rows);
            } else if (fallback) {
                output.addProperty(
                    "scope",
                    "unregistered maid with synthetic owner; explicit loaded bright dry volume; direct fallback generation and native create/install, not natural primary-path failure recovery or full F/N");
                fallbackPaths(platform, rows);
            } else {
                pipeline(platform, rows);
                foreignCancellation(platform, rows);
                createInstallGuards(platform, rows);
            }
        } catch (RuntimeException | LinkageError problem) {
            failure = problem;
        } catch (Exception problem) {
            failure = problem;
        } finally {
            if (platform != null) {
                try {
                    platform.close();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure = collect(failure, cleanup);
                }
            }
        }
        output.addProperty("executionComplete", failure == null);
        if (failure != null) output.addProperty(
            "errorType",
            failure.getClass()
                .getSimpleName());
        try {
            String subdirectory = watchdog ? "navigation-stop-results"
                : fallback ? "follow-fallback-results" : "follow-adapter-results";
            Path directory = checkedDirectory(Paths.get("tlmnh-dev", subdirectory));
            String name = UUID.randomUUID() + ".json";
            byte[] bytes = GSON.toJson(output)
                .getBytes(StandardCharsets.UTF_8);
            require(bytes.length <= 8_388_608, "Adapter evidence exceeds bounded artifact size");
            Files.write(directory.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var summary = new JsonObject();
            summary.addProperty("executionComplete", failure == null);
            summary.addProperty("worldNatural", false);
            summary.addProperty("equivalenceVerified", false);
            summary.addProperty("capture", "tlmnh-dev/" + subdirectory + "/" + name);
            summary.addProperty("sha256", sha(bytes));
            summary.addProperty("bytes", bytes.length);
            summary.addProperty("rows", rows.size());
            TLMNotHard.LOG.info(
                watchdog ? "TLMNH_NAVIGATION_STOP_CAPTURE {}"
                    : fallback ? "TLMNH_FOLLOW_FALLBACK_CAPTURE {}" : "TLMNH_FOLLOW_CORE_ADAPTER_CAPTURE {}",
                summary);
        } catch (Exception | LinkageError writing) {
            if (failure != null && failure != writing) writing.addSuppressed(failure);
            throw new IllegalStateException("Follow adapter evidence could not be saved", writing);
        }
        if (failure != null)
            throw new IllegalStateException("Follow adapter capture incomplete; inspect retained artifact", failure);
    }

    private static void watchdogRecovery(Platform platform, JsonArray rows) {
        var row = row(rows, "native_watchdog_retry");
        try (var fixture = new Fixture(platform, row)) {
            var frames = new JsonArray();
            row.add("frames", frames);
            fixture.frame(frames, "first_walk");
            fixture.frame(frames, "install");
            var actor = fixture.component.getActor();
            long generation = actor.getStopGeneration();
            require(
                generation > 0 && fixture.maid.getNavigator()
                    .getPath() != null,
                "Observed installation missing");
            int installedTick = fixture.maid.ticksExisted;
            for (int index = 0; index < 100; index++) fixture.advanceWithoutCapture();
            row.add("beforeWatchdog", fixture.snapshot());
            require(
                fixture.maid.ticksExisted == installedTick + 100 && actor.peekLastNativeStop() == null
                    && fixture.maid.getNavigator()
                        .getPath() != null,
                "Native watchdog fired before its strict boundary");
            fixture.frame(frames, "watchdog_stop");
            var event = actor.peekLastNativeStop();
            int cooldown = fixture.component.getMoveState()
                .getRemainingCooldown();
            require(
                event != null && event.installationGeneration == generation
                    && event.entityTick == fixture.maid.ticksExisted
                    && event.worldTick == platform.world.getTotalWorldTime(),
                "Native clear event was not accepted in its actual frame");
            require(
                cooldown >= 0 && cooldown < 40
                    && actor.peekWalkTarget() == null
                    && actor.peekPathMemory() == null
                    && fixture.maid.getNavigator()
                        .getPath() == null
                    && fixture.component.getMoveState()
                        .getStatus() == FollowMoveState.Status.STOPPED,
                "Original MOVE stop did not clear owned resources and apply cooldown");
            row.addProperty("cooldown", cooldown);
            row.addProperty("stoppedInstallationGeneration", generation);
            fixture.frame(frames, "follow_rebuilds_walk");
            require(actor.peekWalkTarget() != null, "FOLLOW did not rebuild WALK after stop");
            for (int index = 0; index < cooldown; index++) fixture.advanceWithoutCapture();
            fixture.frame(frames, "retry_install");
            row.addProperty("retryInstallationGeneration", actor.getStopGeneration());
            require(
                actor.getStopGeneration() > generation && actor.peekLastNativeStop() == null
                    && fixture.maid.getNavigator()
                        .getPath() != null
                    && actor.peekPathMemory() != null
                    && fixture.component.getMoveState()
                        .getStatus() == FollowMoveState.Status.RUNNING,
                "Cooldown did not lead to a fresh owned path installation");
            row.addProperty("complete", true);
        }
    }

    private static void unknownClear(Platform platform, JsonArray rows) {
        var row = row(rows, "unknown_clear_refused");
        try (var fixture = new Fixture(platform, row)) {
            var frames = new JsonArray();
            row.add("frames", frames);
            fixture.frame(frames, "first_walk");
            fixture.frame(frames, "install");
            var actor = fixture.component.getActor();
            fixture.maid.getNavigator()
                .clearPathEntity();
            row.add("afterExternalClear", fixture.snapshot());
            try {
                fixture.frame(frames, "unknown_clear");
                throw new IllegalStateException("Ordinary clear was accepted as native watchdog");
            } catch (DryPlatformFollowActor.OutsideContractException expected) {
                require(
                    expected.getMessage()
                        .contains("navigation_disappeared_cause_unknown"),
                    "Unexpected cause refusal");
                row.addProperty("refusal", expected.getMessage());
            }
            row.add("afterRefusal", fixture.snapshot());
            require(
                !fixture.runtime()
                    .isReady() && actor.peekLastNativeStop() == null
                    && actor.peekWalkTarget() == null
                    && actor.peekPathMemory() == null
                    && fixture.maid.getNavigator()
                        .getPath() == null,
                "Unknown clear did not retire its owned action");
            row.addProperty("complete", true);
        }
    }

    private static void fallbackPaths(Platform platform, JsonArray rows) {
        long[] seeds = { 0L, Long.MIN_VALUE };
        for (int index = 0; index < seeds.length; index++) {
            var row = row(rows, index == 1 ? "diagonal_positive" : "east_null_" + seeds[index]);
            try (var fixture = new Fixture(platform, row)) {
                var frames = new JsonArray();
                row.add("frames", frames);
                fixture.frame(frames, "supply_owner_walk");
                var actor = fixture.component.getActor();
                require(
                    actor.peekWalkTarget() != null && actor.getLastCreation() == null,
                    "Fallback fixture must begin with WALK and no query");
                var origin = new FollowMoveActor.Vec3(fixture.maid.posX, fixture.maid.posY, fixture.maid.posZ);
                int directionZ = index == 1 ? 1 : 0;
                var target = new FollowMoveActor.Vec3(origin.x + 1, origin.y, origin.z + directionZ);
                row.addProperty("seed", Long.toString(seeds[index]));
                row.add("origin", point(origin.x, origin.y, origin.z));
                row.add("direction", point(1, 0, directionZ));
                row.addProperty("horizontalRange", 10);
                row.addProperty("verticalRange", 7);
                row.addProperty("angle", 1.5707963705062866D);
                row.addProperty("dimension", platform.world.provider.dimensionId);
                row.addProperty(
                    "providerClass",
                    platform.world.provider.getClass()
                        .getName());
                row.addProperty("skySubtracted", platform.world.skylightSubtracted);
                fixture.maid.getRNG()
                    .setSeed(seeds[index]);
                var selected = actor.findFallbackTowards(10, 7, target, 1.5707963705062866D);
                row.add("selected", selected == null ? JsonNull.INSTANCE : point(selected.x, selected.y, selected.z));
                row.addProperty(
                    "nextFloatHex",
                    Float.toHexString(
                        fixture.maid.getRNG()
                            .nextFloat()));
                row.addProperty(
                    "nextDoubleHex",
                    Double.toHexString(
                        fixture.maid.getRNG()
                            .nextDouble()));
                row.addProperty(
                    "nextInt97",
                    fixture.maid.getRNG()
                        .nextInt(97));
                row.addProperty(
                    "entityRandomClass",
                    fixture.maid.getRNG()
                        .getClass()
                        .getName());
                row.add("afterSampling", fixture.snapshot());
                require(
                    actor.getLastCreation() == null && fixture.maid.getNavigator()
                        .getPath() == null,
                    "Sampling must not query or install a native path");
                require(
                    (selected != null) == (index == 1),
                    "Actual fallback result differs from fixed original utility case");
                if (selected != null) {
                    row.addProperty(
                        "selectedRawLight",
                        platform.world.getBlockLightValue(
                            MathHelper.floor_double(selected.x),
                            MathHelper.floor_double(selected.y),
                            MathHelper.floor_double(selected.z)));
                    var path = actor.createPath(selected, 0);
                    require(
                        path != null && path.canReach(),
                        "Positive fallback did not create an actual complete path");
                    actor.setPathMemory(path);
                    require(actor.moveTo(path, 0.5D), "Positive fallback native install did not start");
                    row.add("afterInstall", fixture.snapshot());
                } else row.add("afterInstall", JsonNull.INSTANCE);
                fixture.component.cancel();
                row.add("afterCancel", fixture.snapshot());
                require(
                    actor.peekWalkTarget() == null && actor.peekPathMemory() == null
                        && !actor.hasLookTarget()
                        && fixture.maid.getNavigator()
                            .getPath() == null,
                    "Fallback fixture did not release owned resources");
                row.addProperty("complete", true);
            }
        }
    }

    private static void pipeline(Platform platform, JsonArray rows) {
        var row = row(rows, "after_native_pipeline");
        try (var fixture = new Fixture(platform, row)) {
            var frames = new JsonArray();
            row.add("frames", frames);
            fixture.frame(frames, "first_walk");
            var actor = fixture.component.getActor();
            require(
                actor.peekWalkTarget() != null && actor.peekPathMemory() == null
                    && actor.getLastCreation() == null
                    && actor.getLastInstallation() == null
                    && fixture.maid.getNavigator()
                        .getPath() == null,
                "First phase did not leave WALK without query/PATH");
            require(
                fixture.component.getMoveState()
                    .getStatus() == FollowMoveState.Status.STOPPED,
                "First phase started sink before follow supplied WALK");
            require(
                actor.hasLookTarget() && fixture.component.getLookState()
                    .getStatus() == FollowCoreLookDriver.Status.STOPPED
                    && fixture.component.getLookState()
                        .getEndTimestamp() == 0,
                "First phase ran LOOK before follow supplied its target");

            fixture.frame(frames, "create_install");
            Creation created = actor.getLastCreation();
            Installation installed = actor.getLastInstallation();
            PathEntity path = fixture.maid.getNavigator()
                .getPath();
            require(
                created != null && created.queryExecuted
                    && created.path != null
                    && created.endpointRelation == EndpointRelation.MATCHES_NATIVE_TARGET,
                "Real native creation did not provide the admitted full flat path");
            require(
                installed != null && installed.nativeSetPathCalled
                    && installed.started
                    && !installed.contextChanged
                    && installed.installedPath == path
                    && path != null
                    && actor.peekPathMemory() != null
                    && actor.peekPathMemory()
                        .getIdentity() == path,
                "Created path, actual installation and memory identity differ");
            require(
                fixture.component.getMoveState()
                    .getStatus() == FollowMoveState.Status.RUNNING,
                "Sink did not remain running after real install");
            long behaviorTime = platform.world.getTotalWorldTime();
            long lookDeadline = fixture.component.getLookState()
                .getEndTimestamp();
            require(
                actor.hasLookTarget() && fixture.component.getLookState()
                    .getStatus() == FollowCoreLookDriver.Status.RUNNING
                    && lookDeadline >= behaviorTime + 45
                    && lookDeadline <= behaviorTime + 90,
                "LOOK did not start with the admitted 45..90 duration");
            fixture.requireOwnerLookConsumed();
            row.addProperty("nativeLookHelperConsumptionVerified", true);
            int installedIndex = path.getCurrentPathIndex();
            fixture.frame(frames, "native_navigation");
            require(
                fixture.maid.getNavigator()
                    .getPath() == path && actor.getLastCreation() == created
                    && actor.getLastInstallation() == installed,
                "Third stage unexpectedly replaced the path or repeated the query");
            require(
                path.getCurrentPathIndex() > installedIndex && fixture.maid.getMoveHelper()
                    .getSpeed() > 0 && fixture.maid.getAIMoveSpeed() > 0,
                "Inherited AI stage did not advance native path and movement control");

            var mutation = new JsonObject();
            mutation.addProperty("kind", "controlled_reached_position");
            mutation.add("before", fixture.position(fixture.maid));
            mutation.add("plannedBlock", point(platform.baseX + 5, FOOT_Y, platform.baseZ));
            fixture.maid.setLocationAndAngles(platform.baseX + 5.5, FOOT_Y, platform.baseZ + .5, 0, 0);
            fixture.maid.onGround = true;
            mutation.add("actualAfter", fixture.position(fixture.maid));
            row.add("reachedInput", mutation);
            fixture.frame(frames, "reached_manhattan_two");
            require(
                fixture.component.getMoveState()
                    .getStatus() == FollowMoveState.Status.STOPPED && actor.peekWalkTarget() == null
                    && actor.peekPathMemory() == null
                    && fixture.maid.getNavigator()
                        .getPath() == null,
                "Reached native phase did not clear its own WALK/PATH/navigation");
            row.addProperty("complete", true);
        }
    }

    private static void foreignCancellation(Platform platform, JsonArray rows) {
        var row = row(rows, "foreign_path_cancellation");
        try (var fixture = new Fixture(platform, row)) {
            var frames = new JsonArray();
            row.add("frames", frames);
            fixture.frame(frames, "first_walk");
            fixture.frame(frames, "create_install");
            PathEntity owned = fixture.maid.getNavigator()
                .getPath();
            require(owned != null, "No owned path to replace");
            row.add("beforeReplacement", fixture.snapshot());
            var target = new FollowMoveActor.BlockPos(platform.baseX + 4, FOOT_Y, platform.baseZ + 4);
            platform.verify(fixture.maid, target);
            var planner = new MaidBoundedPathPlanner();
            var creation = planner.create(fixture.maid, target.x, target.y, target.z, .5);
            require(
                creation.queryExecuted && creation.path != null
                    && creation.endpointRelation == EndpointRelation.MATCHES_NATIVE_TARGET,
                "Foreign native path creation was outside the flat component domain");
            require(
                fixture.maid.getNavigator()
                    .getPath() == owned,
                "Read-only foreign creation changed active path");
            var installation = planner.install(fixture.maid, creation);
            PathEntity foreign = fixture.maid.getNavigator()
                .getPath();
            require(
                installation.nativeSetPathCalled && installation.started
                    && !installation.contextChanged
                    && foreign != null
                    && foreign != owned
                    && installation.installedPath == foreign,
                "Distinct foreign native path was not installed");
            fixture.id(foreign);
            row.add("foreignCreation", fixture.creation(creation));
            row.add("foreignInstallation", fixture.installation(installation));
            row.add("afterReplacement", fixture.snapshot());
            fixture.runtime()
                .invalidate(MaidPolicyRuntime.InvalidationReason.RESET);
            row.add("afterInvalidation", fixture.snapshot());
            require(
                fixture.maid.getNavigator()
                    .getPath() == foreign
                    && fixture.component.getActor()
                        .peekWalkTarget() == null
                    && fixture.component.getActor()
                        .peekPathMemory() == null
                    && fixture.component.getMoveState()
                        .getStatus() == FollowMoveState.Status.STOPPED
                    && !fixture.component.getActor()
                        .hasLookTarget()
                    && fixture.component.getLookState()
                        .getStatus() == FollowCoreLookDriver.Status.STOPPED,
                "Runtime cancellation erased the foreign path or retained old LOOK/movement state");
            row.addProperty("complete", true);
        }
    }

    private static void createInstallGuards(Platform platform, JsonArray rows) {
        var row = row(rows, "create_install_token_guard");
        try (var fixture = new Fixture(platform, row)) {
            var planner = new MaidBoundedPathPlanner();
            var runtime = fixture.runtime();
            Token token = runtime.getContext()
                .getToken();
            row.add("beforeCreate", fixture.snapshot());
            var target = new FollowMoveActor.BlockPos(platform.baseX + 7, FOOT_Y, platform.baseZ);
            platform.verify(fixture.maid, target);
            var creation = planner.create(fixture.maid, target.x, target.y, target.z, .5);
            row.add("creation", fixture.creation(creation));
            row.add("afterCreate", fixture.snapshot());
            require(
                creation.queryExecuted && creation.path != null
                    && creation.endpointRelation == EndpointRelation.MATCHES_NATIVE_TARGET
                    && fixture.maid.getNavigator()
                        .getPath() == null,
                "Creation failed or installed a path before the explicit install stage");
            runtime.invalidate(MaidPolicyRuntime.InvalidationReason.RESET);
            row.addProperty(
                "createdTokenStillCurrent",
                runtime.getContext()
                    .isCurrent(token));
            var refused = planner.install(fixture.maid, creation);
            row.add("staleInstallation", fixture.installation(refused));
            row.add("afterStaleInstall", fixture.snapshot());
            require(
                !runtime.getContext()
                    .isCurrent(token) && refused.contextChanged
                    && !refused.nativeSetPathCalled
                    && !refused.started
                    && fixture.maid.getNavigator()
                        .getPath() == null,
                "Stale creation installed a path after its token was invalidated");
            row.addProperty("complete", true);
        }
    }

    private static JsonObject row(JsonArray rows, String id) {
        var row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("complete", false);
        rows.add(row);
        return row;
    }

    private static Field lookField(Class<?> type, String... names) {
        Field field = ReflectionHelper.findField(EntityLookHelper.class, names);
        require(field.getType() == type, "Native look helper field type differs");
        return field;
    }

    /** 只读取原生 helper，不替换实例，也不调用 setter 或 tick */
    private static JsonObject lookHelperSnapshot(EntityMaid maid) {
        EntityLookHelper helper = maid.getLookHelper();
        require(helper.getClass() == EntityLookHelper.class, "Native look helper implementation outside probe scope");
        try {
            var result = new JsonObject();
            result.addProperty(
                "class",
                helper.getClass()
                    .getName());
            result.addProperty("pending", LOOK_PENDING.getBoolean(helper));
            result.addProperty("x", LOOK_X.getDouble(helper));
            result.addProperty("y", LOOK_Y.getDouble(helper));
            result.addProperty("z", LOOK_Z.getDouble(helper));
            result.addProperty("maxYaw", LOOK_MAX_YAW.getFloat(helper));
            result.addProperty("maxPitch", LOOK_MAX_PITCH.getFloat(helper));
            result.addProperty("headYaw", maid.rotationYawHead);
            result.addProperty("pitch", maid.rotationPitch);
            return result;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native look helper fields are not readable", failure);
        }
    }

    private static final class StageMaid extends EntityMaid {

        JsonObject afterComponentLookHelper;
        EntityLookHelper componentLookHelper;
        int nativeHookObservations;

        StageMaid(WorldServer world) {
            super(world);
        }

        void stepInheritedAi() {
            ticksExisted++;
            super.updateAITasks();
        }

        @Override
        protected void updateAITick() {
            super.updateAITick();
            componentLookHelper = getLookHelper();
            afterComponentLookHelper = lookHelperSnapshot(this);
            nativeHookObservations++;
        }
    }

    private static final class Fixture implements AutoCloseable {

        final Platform platform;
        final StageMaid maid;
        final EntityPlayerMP owner;
        final FollowCoreComponent component;
        final List<Object> globalPlayers, originalPlayers;
        final Map<PathEntity, String> pathIds = new IdentityHashMap<>();
        final JsonObject row;
        Token actorToken;
        boolean joined, closed;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        Fixture(Platform platform, JsonObject row) {
            this.platform = platform;
            this.row = row;
            maid = new StageMaid(platform.world);
            owner = new EntityPlayerMP(
                MinecraftServer.getServer(),
                platform.world,
                new GameProfile(UUID.randomUUID(), "TLMNHCoreOwner"),
                new ItemInWorldManager(platform.world));
            globalPlayers = (List) MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList;
            originalPlayers = new ArrayList<>(globalPlayers);
            component = new FollowCoreComponent(
                maid,
                new DryPlatformFollowActor.Contract(8, false, false, new DryPlatformFollowActor.DryDomain() {

                    public void verify(EntityMaid current, FollowMoveActor.BlockPos target) {
                        platform.verify(current, target);
                    }

                    public void verifyFallbackVolume(EntityMaid current, int horizontalRange, int verticalRange) {
                        if (!platform.fallback || horizontalRange != 10 || verticalRange != 7)
                            throw outside("fallback_volume_not_prepared");
                        platform.verify(
                            current,
                            new FollowMoveActor.BlockPos(
                                MathHelper.floor_double(current.posX),
                                MathHelper.floor_double(current.posY),
                                MathHelper.floor_double(current.posZ)));
                    }
                }));
            try {
                for (Object value : globalPlayers) require(
                    !(value instanceof EntityPlayerMP player) || !player.getUniqueID()
                        .equals(owner.getUniqueID()),
                    "Synthetic owner UUID already present");
                owner.setLocationAndAngles(platform.baseX + 7.5, FOOT_Y, platform.baseZ + .5, 0, 0);
                maid.setLocationAndAngles(platform.baseX + .5, FOOT_Y, platform.baseZ + .5, 0, 0);
                maid.onGround = true;
                require(maid.bindTo(owner.getUniqueID()), "Fixture owner binding failed");
                globalPlayers.add(owner);
                joined = true;
                platform.bind(this);
                maid.getBrainCoordinator()
                    .setAfterNavigationActionsForDevelopment(component);
                var participants = new JsonObject();
                participants.addProperty(
                    "maidClass",
                    maid.getClass()
                        .getName());
                participants.addProperty(
                    "maidUuid",
                    maid.getUniqueID()
                        .toString());
                participants.addProperty(
                    "ownerUuid",
                    owner.getUniqueID()
                        .toString());
                participants.addProperty("maidOwnerUuid", maid.func_152113_b());
                participants.addProperty("ownerSynthetic", true);
                participants.addProperty("ownerReallyLoggedIn", false);
                participants.addProperty("ownerNetworkHandlerPresent", owner.playerNetServerHandler != null);
                participants.addProperty(
                    "ownerGameType",
                    owner.theItemInWorldManager.getGameType()
                        .getID());
                participants.addProperty("ownerGlobalListIdentityCount", ownerReferences());
                participants.addProperty("globalPlayersBefore", originalPlayers.size());
                participants.addProperty("maidWorldRegistered", registered(maid));
                participants.addProperty("ownerWorldRegistered", registered(owner));
                participants.addProperty("controlledOnGround", maid.onGround);
                participants.addProperty("worldGameTime", platform.world.getTotalWorldTime());
                row.add("participants", participants);
                row.add("initialState", snapshot());
                require(
                    ownerReferences() == 1 && !registered(maid) && !registered(owner),
                    "Fixture registration scope changed");
            } catch (RuntimeException | LinkageError failure) {
                try {
                    close();
                } catch (RuntimeException | LinkageError cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        MaidPolicyRuntime runtime() {
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            require(runtime != null, "Development runtime missing");
            return runtime;
        }

        void frame(JsonArray frames, String stage) {
            var frame = new JsonObject();
            frame.addProperty("stage", stage);
            frames.add(frame);
            frame.add("before", snapshot());
            int beforeTick = maid.ticksExisted;
            int beforeHookObservations = maid.nativeHookObservations;
            EntityLookHelper beforeLookHelper = maid.getLookHelper();
            frame.addProperty("nativeHookBefore", beforeHookObservations);
            maid.afterComponentLookHelper = null;
            long beforeWorldTick = platform.world.getTotalWorldTime();
            try {
                maid.stepInheritedAi();
                actorToken = runtime().getContext()
                    .getToken();
                frame.add("afterComponentLookHelper", maid.afterComponentLookHelper);
                frame.addProperty("nativeHookAfter", maid.nativeHookObservations);
                frame.addProperty("componentHelperIsOriginal", maid.componentLookHelper == beforeLookHelper);
                frame.addProperty("afterControlsHelperIsOriginal", maid.getLookHelper() == beforeLookHelper);
                frame.add("after", snapshot());
                require(
                    maid.ticksExisted == beforeTick + 1 && platform.world.getTotalWorldTime() == beforeWorldTick,
                    "Manual component stage or world clock changed unexpectedly");
                require(
                    maid.nativeHookObservations == beforeHookObservations + 1 && maid.afterComponentLookHelper != null
                        && maid.componentLookHelper == beforeLookHelper
                        && maid.getLookHelper() == beforeLookHelper,
                    "Native AI hook observation count or look helper identity changed");
                require(runtime().isReady(), "Component runtime faulted");
            } catch (RuntimeException | LinkageError failure) {
                frame.addProperty(
                    "errorType",
                    failure.getClass()
                        .getSimpleName());
                throw failure;
            }
        }

        void advanceWithoutCapture() {
            int tick = maid.ticksExisted;
            long worldTick = platform.world.getTotalWorldTime();
            maid.stepInheritedAi();
            actorToken = runtime().getContext()
                .getToken();
            require(
                maid.ticksExisted == tick + 1 && platform.world.getTotalWorldTime() == worldTick && runtime().isReady(),
                "Controlled native navigation update failed");
        }

        void requireOwnerLookConsumed() {
            var beforeControls = maid.afterComponentLookHelper;
            var afterControls = lookHelperSnapshot(maid);
            require(
                beforeControls != null && beforeControls.get("pending")
                    .getAsBoolean()
                    && !afterControls.get("pending")
                        .getAsBoolean(),
                "Actual native controls did not consume the component LOOK request");
            require(
                beforeControls.get("x")
                    .getAsDouble() == owner.posX
                    && beforeControls.get("y")
                        .getAsDouble() == owner.posY + owner.getEyeHeight()
                    && beforeControls.get("z")
                        .getAsDouble() == owner.posZ
                    && beforeControls.get("maxYaw")
                        .getAsFloat() == 10.0F
                    && beforeControls.get("maxPitch")
                        .getAsFloat() == maid.getVerticalFaceSpeed(),
                "Component LOOK request did not target the actual owner eye position and native turn limits");
            for (String key : new String[] { "x", "y", "z", "maxYaw", "maxPitch" }) require(
                beforeControls.get(key)
                    .equals(afterControls.get(key)),
                "Native controls changed the submitted LOOK request: " + key);
            require(
                Float.isFinite(maid.rotationYawHead) && Float.isFinite(maid.rotationPitch),
                "Native look helper returned non-finite angles");
        }

        JsonObject snapshot() {
            require(
                !registered(maid) && !registered(owner) && ownerReferences() == 1,
                "Unregistered fixture or synthetic resolver identity changed");
            var actor = component.getActor();
            var runtime = runtime();
            boolean readable = actorToken != null && runtime.isReady()
                && runtime.getContext()
                    .isCurrent(actorToken);
            var result = new JsonObject();
            result.addProperty("worldGameTime", platform.world.getTotalWorldTime());
            result.addProperty("entityTick", maid.ticksExisted);
            result.add("maidPosition", position(maid));
            result.add("ownerPosition", position(owner));
            result.add("ownerEyePosition", point(owner.posX, owner.posY + owner.getEyeHeight(), owner.posZ));
            result.addProperty("maidOnGround", maid.onGround);
            result.addProperty("maidAlive", maid.isEntityAlive() && !maid.isDead);
            result.addProperty("ownerAlive", owner.isEntityAlive() && !owner.isDead);
            result.addProperty(
                "ownerGameType",
                owner.theItemInWorldManager.getGameType()
                    .getID());
            result.addProperty("width", maid.width);
            result.addProperty("height", maid.height);
            result.addProperty("yOffset", maid.yOffset);
            result.addProperty("ySize", maid.ySize);
            result.addProperty("boundingBoxMinY", maid.boundingBox.minY);
            result.addProperty(
                "movementBase",
                maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getBaseValue());
            result.addProperty(
                "movementValue",
                maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue());
            result.addProperty("aiMoveSpeed", maid.getAIMoveSpeed());
            result.addProperty(
                "moveHelperSpeed",
                maid.getMoveHelper()
                    .getSpeed());
            result.addProperty("moveForward", maid.moveForward);
            result.addProperty(
                "runtimeProfile",
                runtime.getProfile()
                    .getId());
            result.addProperty("runtimeReady", runtime.isReady());
            result.addProperty(
                "epoch",
                runtime.getContext()
                    .getEpoch());
            result.addProperty(
                "invalidation",
                runtime.getLastInvalidationReason()
                    .name());
            result.addProperty("actorLeaseReadable", readable);
            if (readable) {
                var ownerView = actor.getOwner();
                result.addProperty(
                    "uuidResolverReturnedOwner",
                    ownerView != null && ownerView.getWorldIdentity() == owner.worldObj);
                result.addProperty("ownerIsWalkTarget", ownerView != null && actor.ownerIsWalkTarget(ownerView));
            } else {
                result.add("uuidResolverReturnedOwner", JsonNull.INSTANCE);
                result.add("ownerIsWalkTarget", JsonNull.INSTANCE);
            }
            var memory = new JsonObject();
            var walk = actor.peekWalkTarget();
            if (walk == null) memory.add("walk", JsonNull.INSTANCE);
            else {
                require(readable, "Cannot inspect stale walk target as current memory");
                var actual = new JsonObject();
                var block = walk.currentBlockPosition();
                actual.add("block", point(block.x, block.y, block.z));
                actual.addProperty("speed", walk.getSpeedModifier());
                actual.addProperty("closeEnough", walk.getCloseEnoughDistance());
                actual.addProperty("entityTrackerSpectator", walk.isEntityTrackerSpectator());
                memory.add("walk", actual);
            }
            var pathMemory = actor.peekPathMemory();
            PathEntity nativePath = maid.getNavigator()
                .getPath();
            memory.addProperty("pathId", pathMemory == null ? null : id((PathEntity) pathMemory.getIdentity()));
            memory.addProperty("pathIsActualNavigation", pathMemory != null && pathMemory.getIdentity() == nativePath);
            memory.addProperty("cantReachSince", actor.peekCantReachSince());
            memory.addProperty("hasLook", actor.hasLookTarget());
            result.add("memory", memory);
            var lookState = component.getLookState();
            var look = new JsonObject();
            look.addProperty(
                "status",
                lookState.getStatus()
                    .name());
            look.addProperty("endTimestamp", lookState.getEndTimestamp());
            look.addProperty("visibilitySource", actor.getLookVisibilitySource());
            result.add("look", look);
            result.add("lookHelper", lookHelperSnapshot(maid));
            var state = component.getMoveState();
            var sink = new JsonObject();
            sink.addProperty(
                "status",
                state.getStatus()
                    .name());
            sink.addProperty("remainingCooldown", state.getRemainingCooldown());
            sink.addProperty(
                "pathId",
                state.getPath() == null ? null
                    : id(
                        (PathEntity) state.getPath()
                            .getIdentity()));
            sink.addProperty("endTimestamp", state.getEndTimestamp());
            sink.addProperty("speedModifier", state.getSpeedModifier());
            var last = state.getLastTargetPos();
            sink.add("lastTargetPos", last == null ? JsonNull.INSTANCE : point(last.x, last.y, last.z));
            result.add("sink", sink);
            var nav = new JsonObject();
            nav.addProperty(
                "class",
                maid.getNavigator()
                    .getClass()
                    .getName());
            nav.addProperty(
                "noPath",
                maid.getNavigator()
                    .noPath());
            nav.add("path", path(nativePath));
            result.add("navigation", nav);
            result.add("lastCreation", creation(actor.getLastCreation()));
            result.add("lastInstallation", installation(actor.getLastInstallation()));
            result.addProperty("domainVerifications", platform.verifications.size());
            if (platform.watchdog) {
                result.addProperty("domainVerificationCalls", platform.totalVerifications);
                result.addProperty("nativeFeedbackApplied", maid.getNavigator() instanceof NativeNavigationStopSource);
                result.addProperty("stopGeneration", actor.getStopGeneration());
                var event = actor.peekLastNativeStop();
                var stop = new JsonObject();
                if (event == null) result.add("lastNativeStop", JsonNull.INSTANCE);
                else {
                    stop.addProperty("cause", event.cause);
                    stop.addProperty("installationGeneration", event.installationGeneration);
                    stop.addProperty("worldTick", event.worldTick);
                    stop.addProperty("entityTick", event.entityTick);
                    result.add("lastNativeStop", stop);
                }
            }
            return result;
        }

        JsonObject creation(Creation value) {
            if (value == null) return null;
            var result = GSON.toJsonTree(value)
                .getAsJsonObject();
            result.add("rawPath", path(value.path));
            return result;
        }

        JsonObject installation(Installation value) {
            if (value == null) return null;
            var result = GSON.toJsonTree(value)
                .getAsJsonObject();
            result.addProperty("requestedPathId", id(value.requestedPath));
            result.addProperty("installedPathId", id(value.installedPath));
            return result;
        }

        JsonObject path(PathEntity value) {
            if (value == null) return null;
            var result = new JsonObject();
            result.addProperty("id", id(value));
            result.addProperty("index", value.getCurrentPathIndex());
            result.addProperty("length", value.getCurrentPathLength());
            result.addProperty("finished", value.isFinished());
            var points = new JsonArray();
            require(value.getCurrentPathLength() <= 256, "Native fixture path exceeds capacity");
            for (int i = 0; i < value.getCurrentPathLength(); i++) points.add(point(value.getPathPointFromIndex(i)));
            result.add("points", points);
            PathPoint end = value.getFinalPathPoint();
            result.add("endpoint", end == null ? JsonNull.INSTANCE : point(end));
            return result;
        }

        String id(PathEntity value) {
            if (value == null) return null;
            String id = pathIds.get(value);
            if (id == null) {
                require(pathIds.size() < 16, "Fixture path identity capacity exceeded");
                id = "path" + pathIds.size();
                pathIds.put(value, id);
            }
            return id;
        }

        JsonObject position(Entity entity) {
            var result = new JsonObject();
            result.addProperty("x", entity.posX);
            result.addProperty("y", entity.posY);
            result.addProperty("z", entity.posZ);
            result.add(
                "block",
                point(
                    MathHelper.floor_double(entity.posX),
                    MathHelper.floor_double(entity.posY),
                    MathHelper.floor_double(entity.posZ)));
            var relative = new JsonObject();
            relative.addProperty("x", entity.posX - platform.baseX);
            relative.addProperty("y", entity.posY - FOOT_Y);
            relative.addProperty("z", entity.posZ - platform.baseZ);
            result.add("relative", relative);
            return result;
        }

        int ownerReferences() {
            int count = 0;
            for (Object value : globalPlayers) if (value == owner) count++;
            return count;
        }

        boolean registered(Entity value) {
            if (platform.world.getEntityByID(value.getEntityId()) == value || value.addedToChunk) return true;
            for (Object item : platform.world.loadedEntityList) if (item == value) return true;
            for (Object item : platform.world.playerEntities) if (item == value) return true;
            return false;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            try {
                maid.getBrainCoordinator()
                    .setSuspended(true);
            } catch (RuntimeException | LinkageError problem) {
                failure = collect(failure, problem);
            }
            try {
                PathEntity actual = maid.getNavigator()
                    .getPath();
                if (actual != null) {
                    require(pathIds.containsKey(actual), "Unowned path appeared during fixture cleanup");
                    maid.getNavigator()
                        .clearPathEntity();
                }
                maid.setDead();
            } catch (RuntimeException | LinkageError problem) {
                failure = collect(failure, problem);
            }
            try {
                if (joined) {
                    for (int i = globalPlayers.size() - 1; i >= 0; i--)
                        if (globalPlayers.get(i) == owner) globalPlayers.remove(i);
                    joined = false;
                }
                require(
                    ownerReferences() == 0 && sameReferences(globalPlayers, originalPlayers),
                    "Global player list was not restored by identity");
            } catch (RuntimeException | LinkageError problem) {
                failure = collect(failure, problem);
            }
            try {
                owner.setDead();
            } catch (RuntimeException | LinkageError problem) {
                failure = collect(failure, problem);
            }
            try {
                platform.unbind(this);
            } catch (RuntimeException | LinkageError problem) {
                failure = collect(failure, problem);
            }
            row.addProperty("cleanupComplete", failure == null);
            row.addProperty("ownerReferencesAfterCleanup", ownerReferences());
            if (failure != null)
                throw new IllegalStateException("Follow component participant cleanup failed", failure);
        }
    }

    private static final class Platform implements AutoCloseable {

        final WorldServer world;
        final int baseX, baseZ;
        final int minY, maxY;
        final boolean fallback, watchdog;
        final List<Placed> blocks = new ArrayList<>();
        final JsonArray verifications = new JsonArray();
        final JsonObject report;
        Fixture fixture;
        boolean closed;
        int totalVerifications, fixtureVerifications;

        Platform(WorldServer world, JsonObject report, boolean fallback, boolean watchdog) {
            this.world = world;
            this.report = report;
            this.fallback = fallback;
            this.watchdog = watchdog;
            minY = FOOT_Y - (fallback ? 9 : 2);
            maxY = FOOT_Y + (fallback ? 9 : 3);
            var spawn = world.getSpawnPoint();
            baseX = (spawn.posX >> 4 << 4) + 8;
            baseZ = (spawn.posZ >> 4 << 4) + 8;
            report.add("origin", point(baseX, FOOT_Y, baseZ));
            report.addProperty("radius", PLATFORM_RADIUS);
            report.addProperty("padding", DOMAIN_PADDING);
            report.addProperty("fallbackVolume", fallback);
            report.addProperty("minY", minY);
            report.addProperty("maxY", maxY);
            report.addProperty("forceLoadedChunks", false);
            report.addProperty("creationComplete", false);
            report.addProperty("cleanupComplete", false);
            report.add("domainVerifications", verifications);
            try {
                checkLoaded(
                    baseX - PLATFORM_RADIUS,
                    baseX + PLATFORM_RADIUS,
                    baseZ - PLATFORM_RADIUS,
                    baseZ + PLATFORM_RADIUS);
                emptyEntities(
                    baseX - PLATFORM_RADIUS,
                    baseX + PLATFORM_RADIUS,
                    baseZ - PLATFORM_RADIUS,
                    baseZ + PLATFORM_RADIUS);
                int checked = 0;
                for (int x = baseX - PLATFORM_RADIUS; x <= baseX + PLATFORM_RADIUS; x++)
                    for (int z = baseZ - PLATFORM_RADIUS; z <= baseZ + PLATFORM_RADIUS; z++)
                        for (int y = minY; y <= maxY; y++) {
                            require(air(x, y, z), "Platform requires original air/meta0 without tile entities");
                            checked++;
                        }
                report.addProperty("preflightAirCells", checked);
                for (int x = baseX - PLATFORM_RADIUS; x <= baseX + PLATFORM_RADIUS; x++)
                    for (int z = baseZ - PLATFORM_RADIUS; z <= baseZ + PLATFORM_RADIUS; z++) {
                        require(air(x, FOOT_Y - 1, z), "Platform placement source changed");
                        var placed = new Placed(x, FOOT_Y - 1, z);
                        blocks.add(placed);
                        require(
                            world.setBlock(x, placed.y, z, Blocks.stone, 0, 2) && stone(x, placed.y, z),
                            "Platform block placement failed");
                    }
                report.addProperty("placedBlocks", blocks.size());
                report.addProperty("creationComplete", true);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    close();
                } catch (RuntimeException | LinkageError cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        void bind(Fixture fixture) {
            require(this.fixture == null && !closed, "Platform already has a fixture");
            this.fixture = fixture;
            fixtureVerifications = 0;
        }

        void unbind(Fixture fixture) {
            if (this.fixture == fixture) this.fixture = null;
            else require(this.fixture == null, "Platform fixture changed");
        }

        void verify(EntityMaid maid, FollowMoveActor.BlockPos target) {
            if (fixture == null || fixture.maid != maid || maid.worldObj != world || target.y != FOOT_Y)
                throw outside("fixture_identity_or_y_changed");
            int x = MathHelper.floor_double(maid.posX), z = MathHelper.floor_double(maid.posZ);
            int minX = x - DOMAIN_PADDING, maxX = x + DOMAIN_PADDING;
            int minZ = z - DOMAIN_PADDING, maxZ = z + DOMAIN_PADDING;
            if (minX < baseX - PLATFORM_RADIUS || maxX > baseX + PLATFORM_RADIUS
                || minZ < baseZ - PLATFORM_RADIUS
                || maxZ > baseZ + PLATFORM_RADIUS
                || target.x < minX
                || target.x > maxX
                || target.z < minZ
                || target.z > maxZ) throw outside("complete_search_domain_outside_owned_plane");
            checkLoaded(minX, maxX, minZ, maxZ);
            emptyEntities(minX, maxX, minZ, maxZ);
            if (fixture.registered(maid) || fixture.registered(fixture.owner) || fixture.ownerReferences() != 1)
                throw outside("fixture_or_resolver_registration_changed");
            int checked = 0;
            for (int atX = minX; atX <= maxX; atX++)
                for (int atZ = minZ; atZ <= maxZ; atZ++) for (int y = minY; y <= maxY; y++) {
                    if (!(y == FOOT_Y - 1 ? stone(atX, y, atZ) : air(atX, y, atZ)))
                        throw outside("unknown_or_changed_block_in_flat_search_domain");
                    checked++;
                }
            totalVerifications++;
            fixtureVerifications++;
            if (watchdog) {
                report.addProperty("verificationCalls", totalVerifications);
                if (fixtureVerifications > 2) return;
            }
            var observed = new JsonObject();
            observed.addProperty(
                "caseId",
                fixture.row.get("id")
                    .getAsString());
            observed.addProperty("entityTick", maid.ticksExisted);
            observed.addProperty("worldGameTime", world.getTotalWorldTime());
            observed.add("maidBlock", point(x, MathHelper.floor_double(maid.posY), z));
            observed.add("targetBlock", point(target.x, target.y, target.z));
            observed.add("min", point(minX, minY, minZ));
            observed.add("max", point(maxX, maxY, maxZ));
            observed.addProperty("padding", DOMAIN_PADDING);
            observed.addProperty("checkedCells", checked);
            observed.addProperty("loadedWithoutForce", true);
            observed.addProperty("externalActors", 0);
            require(verifications.size() < 64, "Domain verification capacity exceeded");
            verifications.add(observed);
        }

        void checkLoaded(int minX, int maxX, int minZ, int maxZ) {
            for (int x = minX >> 4; x <= maxX >> 4; x++) for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!world.getChunkProvider()
                    .chunkExists(x, z)) throw outside("chunk_unloaded_no_force");
                if (!world.getChunkFromChunkCoords(x, z).isChunkLoaded) throw outside("chunk_not_loaded");
            }
        }

        void emptyEntities(int minX, int maxX, int minZ, int maxZ) {
            if (!world
                .getEntitiesWithinAABBExcludingEntity(
                    null,
                    AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1))
                .isEmpty()) throw outside("external_entity_in_search_domain");
        }

        boolean air(int x, int y, int z) {
            return world.getBlock(x, y, z) == Blocks.air && world.getBlockMetadata(x, y, z) == 0
                && world.getTileEntity(x, y, z) == null;
        }

        boolean stone(int x, int y, int z) {
            return world.getBlock(x, y, z) == Blocks.stone && world.getBlockMetadata(x, y, z) == 0
                && world.getTileEntity(x, y, z) == null;
        }

        @Override
        public void close() {
            if (closed) return;
            Throwable failure = null;
            int restored = 0;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                var at = blocks.get(i);
                try {
                    checkLoaded(at.x, at.x, at.z, at.z);
                    if (!air(at.x, at.y, at.z)) {
                        require(stone(at.x, at.y, at.z), "Owned platform changed externally; refusing overwrite");
                        require(world.setBlockToAir(at.x, at.y, at.z), "Platform restoration rejected");
                    }
                    require(air(at.x, at.y, at.z), "Original air/meta0 was not restored");
                    restored++;
                } catch (RuntimeException | LinkageError problem) {
                    failure = collect(failure, problem);
                }
            }
            closed = failure == null;
            report.addProperty("restoredBlocks", restored);
            report.addProperty("cleanupComplete", closed);
            if (failure != null) throw new IllegalStateException("Follow component platform cleanup failed", failure);
        }
    }

    private static final class Placed {

        final int x, y, z;

        Placed(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static JsonArray point(PathPoint value) {
        return point(value.xCoord, value.yCoord, value.zCoord);
    }

    private static JsonArray point(int x, int y, int z) {
        var value = new JsonArray();
        value.add(GSON.toJsonTree(x));
        value.add(GSON.toJsonTree(y));
        value.add(GSON.toJsonTree(z));
        return value;
    }

    private static JsonArray point(double x, double y, double z) {
        var value = new JsonArray();
        value.add(GSON.toJsonTree(x));
        value.add(GSON.toJsonTree(y));
        value.add(GSON.toJsonTree(z));
        return value;
    }

    private static boolean sameReferences(List<?> left, List<?> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    private static Throwable collect(Throwable previous, Throwable next) {
        if (previous == null) return next;
        if (previous != next) previous.addSuppressed(next);
        return previous;
    }

    private static DryPlatformFollowActor.OutsideContractException outside(String reason) {
        return new DryPlatformFollowActor.OutsideContractException(reason);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Path checkedDirectory(Path relative) throws Exception {
        Path directory = Paths.get("")
            .toRealPath();
        for (Path part : relative) {
            directory = directory.resolve(part);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            require(
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)
                    && directory.toRealPath()
                        .equals(
                            directory.toAbsolutePath()
                                .normalize()),
                "Redirected adapter evidence directory");
        }
        return directory;
    }

    private static String sha(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
