package com.tianluoqaq.tlmnh.common.development.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.ai.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.EvidenceGrade;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 开发场景显式启用的外部观察；不从事件请求量或tick净生命差推造箭命中 */
public final class MaidEpisodeRecorder implements AutoCloseable {

    private static final int VICTIM_LIMIT = 32;
    private static final Set<MaidEpisodeRecorder> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EntityMaid maid;
    private final UUID actorId;
    private final World world;
    private final MaidTelemetryChannel channel;
    private final String spec, session = UUID.randomUUID()
        .toString();
    private final Map<EntityLivingBase, Victim> victims = new IdentityHashMap<>();
    private final JsonArray observationErrors = new JsonArray();
    private final long startedTick;
    private final int dimension;
    private boolean fmlRegistered, forgeRegistered, closed, closing, observationFailed, exportAttempted;
    private long droppedVictims, resetObservations, unexpectedResetObservations, nativeRecords;
    private long boundArrowRecords, directRecords, unboundArrowRecords, otherSourceRecords, incomingNativeRecords;
    private long victimsWatched, finishedVictims;
    private double nativeClampedLossEstimate, incomingClampedLossEstimate, incomingNativeAmount;
    private double boundArrowNativeAmount, boundArrowLossEstimate, directNativeAmount, directLossEstimate;
    private double unboundArrowNativeAmount, unboundArrowLossEstimate;
    private JsonObject result, artifact;

    private MaidEpisodeRecorder(EntityMaid maid, String spec) {
        if (spec == null || spec.trim()
            .isEmpty() || spec.length() > 160)
            throw new IllegalArgumentException("Episode spec must contain 1 to 160 characters");
        this.maid = maid;
        this.spec = spec;
        actorId = maid.getUniqueID();
        world = maid.worldObj;
        channel = maid.getBrainCoordinator()
            .getTelemetry();
        startedTick = world.getTotalWorldTime();
        dimension = world.provider.dimensionId;
    }

    public static MaidEpisodeRecorder open(EntityMaid maid, String spec) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || maid == null || maid.worldObj.isRemote || ACTIVE.size() >= 32)
            throw new IllegalStateException("Development server recorder is unavailable");
        for (var active : ACTIVE) {
            if (active.maid == maid || active.actorId.equals(maid.getUniqueID()))
                throw new IllegalStateException("Actor already has a recorder; close it before retrying");
        }
        var recorder = new MaidEpisodeRecorder(maid, spec);
        ACTIVE.add(recorder);
        try {
            recorder.channel.start(recorder.session, 8192, 2048);
            // 注册可能在抛错前已部分成功，先置位以保证close仍尝试注销
            recorder.fmlRegistered = true;
            FMLCommonHandler.instance()
                .bus()
                .register(recorder);
            recorder.forgeRegistered = true;
            MinecraftForge.EVENT_BUS.register(recorder);
            recorder.watch(maid);
            recorder.observe("episode_start", () -> {
                var runtime = maid.getBrainCoordinator()
                    .peekPolicyRuntime();
                recorder.channel.state(
                    "episode",
                    "",
                    "spec=" + spec
                        + ";profile="
                        + (runtime == null ? "unknown"
                            : runtime.getProfile()
                                .getId()));
            });
            return recorder;
        } catch (RuntimeException | LinkageError failure) {
            recorder.error("registration", failure);
            recorder.close();
            throw failure;
        }
    }

    public void watch(EntityLivingBase victim) {
        observe("watch", () -> watchActual(victim));
    }

    private void watchActual(EntityLivingBase victim) {
        if (victims.containsKey(victim)) return;
        if (victim == null) throw new IllegalArgumentException("Victim is required");
        if (victim.worldObj != world || victims.size() >= VICTIM_LIMIT) {
            droppedVictims = Math.incrementExact(droppedVictims);
            return;
        }
        double health = finite(victim.getHealth()), absorption = finite(victim.getAbsorptionAmount());
        victims.put(victim, new Victim(NativeCombatLedgerReader.watch(victim), health, absorption));
        victimsWatched = Math.incrementExact(victimsWatched);
    }

    /** 脚本退役目标前结束观察，不把setDead之后无法证明来源的清表当作已知死亡 */
    public void finishVictim(EntityLivingBase victim) {
        observe("finish_victim", () -> {
            if (victim == maid) throw new IllegalArgumentException("Actor observation ends with the episode");
            if (!victims.containsKey(victim)) return;
            harvest(victim, "victim_observation_end", false);
            interval(victim);
            channel.state("victim", MaidTelemetryChannel.id(victim), "observation_finished_before_script_retirement");
            victims.remove(victim);
            finishedVictims = Math.incrementExact(finishedVictims);
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void attack(LivingAttackEvent event) {
        observe(
            "attack_callback",
            () -> damageStage(
                event.entityLiving,
                event.source,
                event.ammount,
                "raw_living_attack_candidate",
                event.isCanceled()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void hurt(LivingHurtEvent event) {
        observe(
            "hurt_callback",
            () -> damageStage(
                event.entityLiving,
                event.source,
                event.ammount,
                "raw_living_hurt_before_mitigation",
                event.isCanceled()));
    }

    private void damageStage(EntityLivingBase victim, DamageSource source, float amount, String stage,
        boolean canceled) {
        if (source == null) throw new IllegalStateException("Damage source unavailable");
        boolean own = own(source);
        if (!own && !victims.containsKey(victim)) return;
        if (own) watchActual(victim);
        harvest(victim, stage, false);
        if (own || victim == maid) channel.provisional(
            victim == maid ? "incoming/" + source.getDamageType() : action(source),
            sourceId(source),
            victim,
            stage + ";sourceType=" + source.getDamageType() + ";canceled_at_observation=" + canceled,
            amount);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void heal(LivingHealEvent event) {
        observe("heal_callback", () -> {
            if (victims.containsKey(event.entityLiving)) {
                harvest(event.entityLiving, "before_heal", false);
                channel.state(
                    "victim",
                    MaidTelemetryChannel.id(event.entityLiving),
                    "heal_requested;not_committed_at_event");
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void death(LivingDeathEvent event) {
        observe("death_callback", () -> {
            if (!victims.containsKey(event.entityLiving) && own(event.source))
                throw new IllegalStateException("No pre-damage watermark for dying victim");
            if (victims.containsKey(event.entityLiving)) harvest(event.entityLiving, "death_before_native_clear", true);
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        observe("tick_callback", () -> {
            if (event.phase != TickEvent.Phase.END) return;
            if (maid.worldObj != world) throw new IllegalStateException("Recording world changed");
            for (var victim : new ArrayList<>(victims.keySet())) {
                harvest(victim, "server_tick_end", false);
                interval(victim);
            }
        });
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        observe("unload_callback", () -> {
            if (event.world == world) {
                error("world_unloaded", new IllegalStateException("World unloaded during recording"));
                close();
            }
        });
    }

    /** 所有总线回调都在此隔离，采集失败不能改变原生伤害返回或中断实体更新 */
    private void observe(String stage, Runnable observation) {
        if (closed || observationFailed) return;
        try {
            if (channel.isEnabled()) observation.run();
        } catch (RuntimeException | LinkageError failure) {
            error(stage, failure);
        }
    }

    private void harvest(EntityLivingBase victim, String phase, boolean death) {
        var tracked = victims.get(victim);
        if (tracked == null) return;
        var harvest = tracked.cursor.harvest(death);
        if (harvest.tailIncomplete)
            throw new IllegalStateException("Native history exceeds bounded observation window");
        if (harvest.resetObserved) {
            resetObservations = Math.incrementExact(resetObservations);
            if (!harvest.resetWasExpected)
                unexpectedResetObservations = Math.incrementExact(unexpectedResetObservations);
            channel.state(
                "native_ledger",
                MaidTelemetryChannel.id(victim),
                "reset_observed;reason=" + harvest.resetReason);
        }
        for (var entry : harvest.entries) {
            if (victim == maid) {
                incomingNativeRecords = Math.incrementExact(incomingNativeRecords);
                incomingClampedLossEstimate = sum(incomingClampedLossEstimate, entry.clampedNativeLossEstimate);
                incomingNativeAmount = sum(incomingNativeAmount, entry.nativeRecordedDamage);
                channel.nativeCommit(
                    "incoming/" + entry.source.getDamageType(),
                    sourceId(entry.source),
                    victim,
                    "phase=" + phase + ";victimTick=" + entry.victimTick,
                    entry.nativeRecordedDamage,
                    entry.healthBefore);
                continue;
            }
            if (!own(entry.source)) {
                otherSourceRecords = Math.incrementExact(otherSourceRecords);
                continue;
            }
            boolean arrowSource = entry.source.getSourceOfDamage() instanceof EntityArrow;
            boolean bound = arrowSource
                && channel.isConfirmedProjectile((EntityArrow) entry.source.getSourceOfDamage());
            String binding;
            if (arrowSource && !bound) {
                unboundArrowRecords = Math.incrementExact(unboundArrowRecords);
                unboundArrowNativeAmount = sum(unboundArrowNativeAmount, entry.nativeRecordedDamage);
                unboundArrowLossEstimate = sum(unboundArrowLossEstimate, entry.clampedNativeLossEstimate);
                binding = "unbound_arrow_not_current_episode";
            } else {
                nativeRecords = Math.incrementExact(nativeRecords);
                nativeClampedLossEstimate = sum(nativeClampedLossEstimate, entry.clampedNativeLossEstimate);
                if (bound) {
                    boundArrowRecords = Math.incrementExact(boundArrowRecords);
                    boundArrowNativeAmount = sum(boundArrowNativeAmount, entry.nativeRecordedDamage);
                    boundArrowLossEstimate = sum(boundArrowLossEstimate, entry.clampedNativeLossEstimate);
                    binding = "confirmed_release_bound_arrow";
                } else {
                    directRecords = Math.incrementExact(directRecords);
                    directNativeAmount = sum(directNativeAmount, entry.nativeRecordedDamage);
                    directLossEstimate = sum(directLossEstimate, entry.clampedNativeLossEstimate);
                    binding = "direct_source_no_projectile_binding";
                }
            }
            channel.nativeCommit(
                arrowSource && !bound ? "unbound_arrow/" + entry.source.getDamageType() : action(entry.source),
                sourceId(entry.source),
                victim,
                "phase=" + phase
                    + ";victimTick="
                    + entry.victimTick
                    + ";sourceType="
                    + entry.source.getDamageType()
                    + ";binding="
                    + binding,
                entry.nativeRecordedDamage,
                entry.healthBefore);
        }
    }

    /** 净差值可能含治疗、其他攻击和观察重叠，只作未归因区间，不计确认伤害 */
    private void interval(EntityLivingBase victim) {
        var tracked = victims.get(victim);
        if (tracked == null) return;
        double health = finite(victim.getHealth()), absorption = finite(victim.getAbsorptionAmount());
        if (health != tracked.health || absorption != tracked.absorption) channel.healthEvidence(
            "victim_interval",
            "",
            victim,
            "unattributed_net_interval;not_arrow_damage",
            EvidenceGrade.UNATTRIBUTED_INTERVAL,
            tracked.health,
            health,
            true,
            tracked.absorption,
            absorption);
        tracked.health = health;
        tracked.absorption = absorption;
    }

    private boolean own(DamageSource source) {
        return source != null && source.getEntity() == maid;
    }

    private static String sourceId(DamageSource source) {
        return source.getSourceOfDamage() == null ? source.getDamageType()
            : MaidTelemetryChannel.id(source.getSourceOfDamage());
    }

    private static String action(DamageSource source) {
        return (source.getSourceOfDamage() instanceof EntityArrow ? "bow/" : "direct/") + source.getDamageType();
    }

    private void error(String stage, Throwable failure) {
        observationFailed = true;
        // 异常message可能包含本机路径，只保留固定阶段及类型
        try {
            if (observationErrors.size() < 16) observationErrors.add(
                new JsonPrimitive(
                    stage + ":"
                        + failure.getClass()
                            .getSimpleName()));
        } catch (RuntimeException | LinkageError ignored) {}
        try {
            channel.invalidate("native_observation_failed:" + stage);
        } catch (RuntimeException | LinkageError ignored) {}
        if (result != null) {
            try {
                result.addProperty("validObservation", false);
                result.addProperty("traceComplete", false);
                result.addProperty("benchmarkEligible", false);
                result.add("observationErrors", observationErrors);
            } catch (RuntimeException | LinkageError ignored) {}
        }
    }

    /** 返回完整且独立的JSON副本；普通日志应使用exportSummary避免输出全部事件 */
    public JsonObject finish() {
        close();
        try {
            return copy(result);
        } catch (RuntimeException | LinkageError failure) {
            error("finish_copy", failure);
            return minimalResult();
        }
    }

    @Override
    public void close() {
        if (closing || closed && !fmlRegistered && !forgeRegistered) return;
        closing = true;
        boolean first = !closed;
        closed = true;
        try {
            if (first && !observationFailed && channel.isEnabled()) {
                for (var victim : new ArrayList<>(victims.keySet())) {
                    harvest(victim, "recording_close", false);
                    interval(victim);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            error("close_observation", failure);
        } finally {
            try {
                channel.close();
            } catch (RuntimeException | LinkageError failure) {
                error("close_channel", failure);
            }
            unregister();
            if (result == null) {
                try {
                    result = buildResult();
                } catch (RuntimeException | LinkageError failure) {
                    error("close_result", failure);
                    result = minimalResult();
                }
            }
            victims.clear();
            closing = false;
        }
    }

    private void unregister() {
        if (forgeRegistered) {
            try {
                MinecraftForge.EVENT_BUS.unregister(this);
                forgeRegistered = false;
            } catch (RuntimeException | LinkageError failure) {
                error("unregister_forge", failure);
            }
        }
        if (fmlRegistered) {
            try {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(this);
                fmlRegistered = false;
            } catch (RuntimeException | LinkageError failure) {
                error("unregister_fml", failure);
            }
        }
        // 注销失败时保留ACTIVE预约并允许下次close重试，避免不断泄漏已注册观察器
        if (!forgeRegistered && !fmlRegistered) ACTIVE.remove(this);
    }

    private JsonObject buildResult() {
        var snapshot = channel.snapshot();
        var output = baseResult();
        output.addProperty("endWorldTick", world.getTotalWorldTime());
        var runtime = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        output.addProperty("policyMetadataAvailable", runtime != null);
        if (runtime == null) {
            output.add("profile", JsonNull.INSTANCE);
            output.add("runtimeEpoch", JsonNull.INSTANCE);
        } else {
            output.addProperty(
                "profile",
                runtime.getProfile()
                    .getId());
            output.addProperty(
                "runtimeEpoch",
                runtime.getContext()
                    .getEpoch());
        }
        boolean valid = !observationFailed && observationErrors.size() == 0
            && channel.getFault() == null
            && droppedVictims == 0;
        boolean complete = valid && snapshot.droppedEvents == 0
            && snapshot.rejectedEvents == 0
            && channel.getProjectileBindingsDropped() == 0
            && unexpectedResetObservations == 0;
        output.addProperty("validObservation", valid);
        output.addProperty("traceComplete", complete);
        output.addProperty(
            "benchmarkEligible",
            complete && unboundArrowRecords == 0
                && runtime != null
                && runtime.isReady()
                && runtime.getProfile()
                    .isAvailable());
        output.addProperty(
            "benchmarkEligibilityScope",
            "observation protocol for the selected available policy only; not faithful/enhanced admission or O/F/N completion");
        output.addProperty("ofnComparisonEligible", false);
        output.addProperty("fault", channel.getFault());
        output.addProperty("victimsObserved", victimsWatched);
        output.addProperty("victimsActiveAtClose", victims.size());
        output.addProperty("finishedVictims", finishedVictims);
        output.addProperty("droppedVictims", droppedVictims);
        output.addProperty("projectileBindingsDropped", channel.getProjectileBindingsDropped());
        output.addProperty("nativeRecords", nativeRecords);
        output.addProperty("incomingNativeRecords", incomingNativeRecords);
        output.addProperty("boundArrowNativeRecords", boundArrowRecords);
        output.addProperty("directNativeRecords", directRecords);
        output.addProperty("unboundArrowNativeRecords", unboundArrowRecords);
        output.addProperty("otherSourceNativeRecords", otherSourceRecords);
        output.addProperty("nativeResetObservations", resetObservations);
        output.addProperty("unexpectedNativeResetObservations", unexpectedResetObservations);
        output.addProperty("clampedNativeLossEstimate", nativeClampedLossEstimate);
        output.addProperty("boundArrowNativeRecordedDamageAmount", boundArrowNativeAmount);
        output.addProperty("boundArrowClampedNativeLossEstimate", boundArrowLossEstimate);
        output.addProperty("directNativeRecordedDamageAmount", directNativeAmount);
        output.addProperty("directClampedNativeLossEstimate", directLossEstimate);
        output.addProperty("unboundArrowNativeRecordedDamageAmount", unboundArrowNativeAmount);
        output.addProperty("unboundArrowClampedNativeLossEstimate", unboundArrowLossEstimate);
        output.addProperty("incomingNativeRecordedDamageAmount", incomingNativeAmount);
        output.addProperty("incomingClampedNativeLossEstimate", incomingClampedLossEstimate);
        output.addProperty("actorAliveAtClose", maid.isEntityAlive());
        output.addProperty("actorHealthAtClose", finite(maid.getHealth()));
        output.addProperty(
            "clampEstimateScope",
            "native post-setHealth derived estimate, not observed healthAfter; outgoing estimate includes bound arrows and direct sources only; unbound arrows excluded");
        output.addProperty(
            "nativeTelemetryAmountScope",
            "all emitted incoming, bound-arrow, direct and unbound-arrow raw records; never a confirmed health-loss total");
        output.add("observationErrors", observationErrors);
        output.add("telemetry", new Gson().toJsonTree(snapshot));
        return output;
    }

    private JsonObject baseResult() {
        var output = new JsonObject();
        output.addProperty("schema", "maid-native-episode-v1");
        output.addProperty("version", Tags.VERSION);
        output.addProperty("session", session);
        output.addProperty("spec", spec);
        output.addProperty("actor", actorId.toString());
        output.addProperty("dimension", dimension);
        output.addProperty("startWorldTick", startedTick);
        output.addProperty("behaviourBenchmarkVerified", false);
        return output;
    }

    private JsonObject minimalResult() {
        var output = baseResult();
        output.addProperty("validObservation", false);
        output.addProperty("traceComplete", false);
        output.addProperty("benchmarkEligible", false);
        output.add("observationErrors", observationErrors);
        return output;
    }

    /** 仅开发服务端导出；完整结果压缩存盘，返回值保留计数但省略事件明细 */
    public JsonObject exportSummary() {
        close();
        if (!exportAttempted) {
            exportAttempted = true;
            Path partial = null, written = null;
            boolean partialOwned = false;
            try {
                if (!Boolean.getBoolean("tlmnh.devCommands") || fmlRegistered || forgeRegistered)
                    throw new IllegalStateException("Episode export is unavailable or cleanup incomplete");
                byte[] json = new Gson().toJson(result)
                    .getBytes(StandardCharsets.UTF_8);
                Path directory = episodeDirectory();
                partial = directory.resolve(session + ".json.gz.part");
                Path target = directory.resolve(session + ".json.gz");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new FileAlreadyExistsException("Episode artifact exists");
                try (var stream = Files
                    .newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    partialOwned = true;
                    try (var gzip = new GZIPOutputStream(stream)) {
                        gzip.write(json);
                    }
                }
                checkedDirectory(directory);
                Files.move(partial, target);
                partialOwned = false;
                written = target;
                long compressedBytes = Files.size(target);
                String sha = artifactDigest(target, compressedBytes);
                artifact = new JsonObject();
                artifact.addProperty("status", "WRITTEN");
                artifact.addProperty("relativePath", "tlmnh-dev/episodes/" + session + ".json.gz");
                artifact.addProperty("sha256", sha);
                artifact.addProperty("compressedBytes", compressedBytes);
                artifact.addProperty("jsonBytes", json.length);
            } catch (IOException | RuntimeException | LinkageError failure) {
                artifactFailure(failure);
                if (written != null) deleteOwnedPartial(written);
            } finally {
                if (partialOwned && partial != null) deleteOwnedPartial(partial);
            }
        } else if (artifact != null && "WRITTEN".equals(
            artifact.get("status")
                .getAsString())) {
                    try {
                        Path target = episodeDirectory().resolve(session + ".json.gz");
                        String digest = artifactDigest(
                            target,
                            artifact.get("compressedBytes")
                                .getAsLong());
                        if (!digest.equals(
                            artifact.get("sha256")
                                .getAsString()))
                            throw new IOException("Episode artifact changed");
                    } catch (IOException | RuntimeException | LinkageError failure) {
                        artifactFailure(failure);
                    }
                }
        try {
            var summary = copy(result);
            if (summary.has("telemetry")) summary.getAsJsonObject("telemetry")
                .remove("events");
            summary.add("artifact", copy(artifact));
            return summary;
        } catch (RuntimeException | LinkageError failure) {
            artifactFailure(failure);
            var summary = minimalResult();
            summary.add("artifact", artifact);
            return summary;
        }
    }

    private void artifactFailure(Throwable failure) {
        error("artifact_export", failure);
        artifact = new JsonObject();
        artifact.addProperty("status", "ERROR");
        artifact.addProperty(
            "error",
            failure.getClass()
                .getSimpleName());
    }

    private void deleteOwnedPartial(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException | LinkageError failure) {
            artifactFailure(failure);
        }
    }

    private static Path episodeDirectory() throws IOException {
        Path anchor = Paths.get("")
            .toRealPath();
        Path parent = checkedDirectory(anchor.resolve("tlmnh-dev"));
        return checkedDirectory(parent.resolve("episodes"));
    }

    private static Path checkedDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException existing) {}
        var attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()
            || attrs.isOther()
            || !directory.toRealPath()
                .equals(
                    directory.toAbsolutePath()
                        .normalize()))
            throw new IOException("Unsafe episode directory");
        return directory;
    }

    private static String artifactDigest(Path target, long expectedBytes) throws IOException {
        var attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther() || attrs.size() != expectedBytes)
            throw new IOException("Episode artifact is not the expected regular file");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        long read = 0;
        byte[] buffer = new byte[8192];
        try (var input = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            for (int length; (length = input.read(buffer)) != -1;) {
                read = Math.addExact(read, length);
                if (read > expectedBytes) throw new IOException("Episode artifact grew during reading");
                digest.update(buffer, 0, length);
            }
        }
        if (read != expectedBytes) throw new IOException("Episode artifact changed during reading");
        var hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int number = value & 255;
            hex.append(Character.forDigit(number >>> 4, 16))
                .append(Character.forDigit(number & 15, 16));
        }
        return hex.toString();
    }

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString())
            .getAsJsonObject();
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite observed value");
        return value;
    }

    private static double sum(double previous, double amount) {
        return finite(previous + amount);
    }

    public static void closeAll() {
        for (var recorder : new ArrayList<>(ACTIVE)) {
            try {
                recorder.close();
            } catch (RuntimeException | LinkageError failure) {
                recorder.error("close_all", failure);
            }
        }
    }

    private static final class Victim {

        final NativeCombatLedgerReader.Cursor cursor;
        double health, absorption;

        Victim(NativeCombatLedgerReader.Cursor cursor, double health, double absorption) {
            this.cursor = cursor;
            this.health = health;
            this.absorption = absorption;
        }
    }
}
