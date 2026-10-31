package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.SelectionResult;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidEpisodeRecorder;
import com.tianluoqaq.tlmnh.common.development.telemetry.NativeCombatLedgerReader;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 原生伤害与观测边界夹具，主动调用伤害管线，不代表自然AI或O/F/N测评 */
public final class MaidTelemetryProbe {

    private static final double EPSILON = 0.0001;

    private MaidTelemetryProbe() {}

    /** 失败也先输出完整机器报告，再抛异常，避免外层编排一直等待PASS */
    public static String run(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !world.isRemote && !AnimationLab.isLab(world),
            "Explicit development test overworld required");
        var fixture = new Fixture(world);
        Throwable failure = null;
        try {
            fixture.begin();
            fixture.exercise("default_off", MaidTelemetryProbe::defaultOff);
            fixture.exercise("unavailable_policy_not_scored", MaidTelemetryProbe::unavailablePolicy);
            fixture.exercise("ordinary_native", MaidTelemetryProbe::ordinary);
            fixture.exercise("overkill_raw_and_clamp", MaidTelemetryProbe::overkill);
            fixture.exercise("hurt_cancelled", c -> suppressed(c, true));
            fixture.exercise("hurt_zeroed", c -> suppressed(c, false));
            fixture.exercise("full_absorption", MaidTelemetryProbe::absorption);
            fixture.exercise("two_sources_same_tick", MaidTelemetryProbe::twoSources);
            fixture.exercise("damage_heal_damage", MaidTelemetryProbe::healing);
            fixture.exercise("nested_native_damage", MaidTelemetryProbe::nested);
            fixture.exercise("fatal_clear_and_dedup", MaidTelemetryProbe::fatalClear);
            fixture.exercise("closed_episode_watermark", MaidTelemetryProbe::watermark);
            fixture.exercise("unbound_arrow_source", MaidTelemetryProbe::unboundArrow);
            fixture.exercise("faulted_active_episode_reopen", MaidTelemetryProbe::faultedReopen);
        } catch (RuntimeException | LinkageError error) {
            failure = error;
        } finally {
            fixture.cleanup();
        }
        boolean passed = failure == null && fixture.cleanupErrors.isEmpty();
        var result = new JsonObject();
        result.addProperty("schema", "maid-telemetry-native-fixture-v1");
        result.addProperty("run", fixture.run);
        result.addProperty("passed", passed);
        result.addProperty(
            "scope",
            "registered vanilla EntityCow attackEntityFrom and native CombatTracker observation; no manual entity or controller ticks; hostile join modifiers outside this fixture");
        result.addProperty("naturalAiVerified", false);
        result.addProperty("productionTargetingVerified", false);
        result.addProperty("behaviourBenchmarkVerified", false);
        result.addProperty("renderingVerified", false);
        result.addProperty("hurtResistanceResetBetweenControlledCalls", true);
        result.addProperty("caseCount", fixture.cases.size());
        result.addProperty("ownedEntities", fixture.owned.size());
        result.addProperty("error", failure == null ? null : failure.toString());
        result.add("cases", fixture.cases);
        var errors = new JsonArray();
        for (String error : fixture.cleanupErrors) errors.add(new com.google.gson.JsonPrimitive(error));
        result.add("cleanupErrors", errors);
        String json = result.toString();
        TLMNotHard.LOG.info("TLMNH_TELEMETRY_NATIVE_{} {}", passed ? "PASS" : "FAIL", json);
        if (!passed) throw new IllegalStateException("Native telemetry fixture failed; see retained JSON", failure);
        return json;
    }

    private static void defaultOff(Case c) {
        var channel = c.actor.getBrainCoordinator()
            .getTelemetry();
        require(!channel.isEnabled(), "New actor unexpectedly records by default");
        boolean unavailable = false;
        try {
            channel.snapshot();
        } catch (IllegalStateException expected) {
            unavailable = true;
        }
        require(unavailable, "Default-off telemetry allocated a recording ledger");
        float before = c.target.getHealth();
        require(c.hit(DamageSource.causeMobDamage(c.actor), 4), "Default-off native call rejected");
        require(
            c.target.getHealth() < before && !channel.isEnabled() && channel.getFault() == null,
            "Default-off observation changed native damage or recording state");
        c.report.addProperty("nativeHealthLoss", before - c.target.getHealth());
        c.report.addProperty("recordingAllocated", false);
    }

    private static void ordinary(Case c) {
        c.target.setCurrentItemOrArmor(3, new ItemStack(Items.iron_chestplate));
        require(c.target.getTotalArmorValue() > 0, "Controlled native armor did not contribute mitigation");
        var recorder = c.open("ordinary");
        float before = c.target.getHealth();
        require(c.hit(DamageSource.causeMobDamage(c.actor), 4), "Ordinary native call rejected");
        double loss = before - c.target.getHealth();
        require(loss > 0 && loss < 4 && c.target.isEntityAlive(), "Ordinary native armor mitigation was not observed");
        var report = c.finish(recorder);
        healthy(report, 1);
        near(raw(report), loss, "Ordinary native amount differs from actual nonlethal health loss");
        near(number(report, "clampedNativeLossEstimate"), loss, "Ordinary native clamp differs");
        noConfirmedHealthSum(report);
        c.report.addProperty("requestedDamage", 4);
        c.report.addProperty("observedHealthLoss", loss);
        c.report.addProperty(
            "armorScope",
            "controlled iron chestplate uses native armor mitigation; expected ledger value is the observed nonlethal loss");
    }

    private static void unavailablePolicy(Case c) {
        var runtime = c.actor.getBrainCoordinator()
            .getPolicyRuntime();
        for (var profile : new MaidBrainProfile[] { MaidBrainProfile.FAITHFUL_TLM, MaidBrainProfile.ENHANCED }) {
            require(runtime.select(profile) == SelectionResult.UNAVAILABLE, "Unavailable profile became active");
            var recorder = c.open(profile.getId());
            var report = c.finish(recorder);
            healthy(report, 0);
            require(
                !report.get("benchmarkEligible")
                    .getAsBoolean(),
                "Unavailable policy received observation eligibility");
            require(
                !report.get("ofnComparisonEligible")
                    .getAsBoolean(),
                "Unavailable policy received O/F/N admission");
            require(
                profile.getId()
                    .equals(
                        report.get("profile")
                            .getAsString()),
                "Observer silently changed unavailable profile");
        }
        c.report.addProperty(
            "scope",
            "valid empty observations do not admit unimplemented policies or imply benchmark completion");
    }

    private static void overkill(Case c) {
        c.target.setHealth(2);
        var recorder = c.open("overkill");
        require(c.hit(absolute(c.actor, "tlmnh_fixture_overkill"), 10), "Overkill native call rejected");
        near(c.target.getHealth(), 0, "Overkill did not reach native zero health");
        var report = c.finish(recorder);
        healthy(report, 1);
        near(raw(report), 10, "Overkill raw native amount was replaced by health delta");
        near(number(report, "clampedNativeLossEstimate"), 2, "Overkill native clamp estimate must be two");
        var entry = nativeEvents(report).get(0)
            .getAsJsonObject("nativeDamage");
        near(number(entry, "healthBefore"), 2, "Fatal record healthBefore was lost");
        noConfirmedHealthSum(report);
        c.report.addProperty(
            "sourceScope",
            "native EntityDamageSource bypasses armor and potion reduction for fixed fixture input");
    }

    private static void suppressed(Case c, boolean cancel) {
        var recorder = c.open(cancel ? "cancel" : "zero");
        var source = absolute(c.actor, cancel ? "tlmnh_fixture_cancel" : "tlmnh_fixture_zero");
        var rule = new HurtRule(c.target, source, cancel);
        MinecraftForge.EVENT_BUS.register(rule);
        boolean accepted;
        try {
            accepted = c.hit(source, 10);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(rule);
        }
        require(rule.calls == 1 && accepted, "Native outer call did not retain its accepted-but-no-health behavior");
        near(c.target.getHealth(), c.target.getMaxHealth(), "Suppressed hurt changed target health");
        var report = c.finish(recorder);
        healthy(report, 0);
        near(raw(report), 0, "Suppressed request became a native damage record");
        near(number(report, "clampedNativeLossEstimate"), 0, "Suppressed request became health loss");
        require(
            number(telemetry(report), "provisionalRequestedDamageAmount") > 0,
            "Suppression control did not exercise provisional observation");
        noConfirmedHealthSum(report);
        c.report.addProperty("nativeCallReturnedTrue", accepted);
        c.report.addProperty("hurtRule", cancel ? "cancel" : "amount_zero");
    }

    private static void absorption(Case c) {
        c.target.setAbsorptionAmount(12);
        var recorder = c.open("absorption");
        require(c.hit(absolute(c.actor, "tlmnh_fixture_absorption"), 4), "Absorbed native call rejected");
        near(c.target.getHealth(), c.target.getMaxHealth(), "Full absorption lost health");
        near(c.target.getAbsorptionAmount(), 8, "Native absorption consumption differs");
        var report = c.finish(recorder);
        healthy(report, 0);
        near(raw(report), 0, "Full absorption produced a health commit record");
        near(number(telemetry(report), "observedAbsorptionDelta"), -4, "Absorption interval was lost");
        noConfirmedHealthSum(report);
    }

    private static void twoSources(Case c) {
        var recorder = c.open("two_sources");
        var cursor = NativeCombatLedgerReader.watch(c.target);
        var first = absolute(c.actor, "tlmnh_fixture_own");
        var second = new DamageSource("tlmnh_fixture_other").setDamageBypassesArmor()
            .setDamageIsAbsolute();
        long tick = c.fixture.world.getTotalWorldTime();
        require(c.hit(first, 3) && c.hit(second, 5), "Two-source native calls rejected");
        require(c.fixture.world.getTotalWorldTime() == tick, "Controlled calls crossed a world tick");
        var observed = cursor.harvest(false);
        require(
            observed.entries.size() == 2 && observed.entries.get(0).source == first
                && observed.entries.get(1).source == second,
            "Native per-source identity or order differs");
        near(observed.entries.get(0).nativeRecordedDamage, 3, "First native source amount differs");
        near(observed.entries.get(1).nativeRecordedDamage, 5, "Second native source amount differs");
        near(c.target.getHealth(), c.target.getMaxHealth() - 8, "Two-source native health sum differs");
        require(cursor.harvest(false).entries.isEmpty(), "Repeated native harvest duplicated entries");
        var report = c.finish(recorder);
        healthy(report, 1);
        require(integer(report, "otherSourceNativeRecords") == 1, "Foreign source was not separated");
        near(raw(report), 3, "Foreign source leaked into this actor's native amount");
        near(number(report, "clampedNativeLossEstimate"), 3, "Foreign source leaked into actor estimate");
        near(number(telemetry(report), "observedHealthDelta"), -8, "Unattributed two-source interval differs");
        noConfirmedHealthSum(report);
    }

    private static void healing(Case c) {
        var recorder = c.open("damage_heal_damage");
        float before = c.target.getHealth();
        require(c.hit(absolute(c.actor, "tlmnh_fixture_before_heal"), 4), "First pre-heal hit rejected");
        c.target.heal(3);
        near(c.target.getHealth(), before - 1, "Native healing did not apply the controlled three points");
        require(c.hit(absolute(c.actor, "tlmnh_fixture_after_heal"), 2), "Second post-heal hit rejected");
        near(c.target.getHealth(), before - 3, "Mixed native health result differs");
        var report = c.finish(recorder);
        healthy(report, 2);
        near(raw(report), 6, "Mixed raw damage was reduced to net delta");
        near(number(report, "clampedNativeLossEstimate"), 6, "Mixed damage clamp estimate differs");
        near(number(telemetry(report), "observedHealthDelta"), -3, "Mixed net interval differs");
        noConfirmedHealthSum(report);
    }

    private static void nested(Case c) {
        var recorder = c.open("nested");
        var outer = absolute(c.actor, "tlmnh_fixture_outer");
        var inner = absolute(c.actor, "tlmnh_fixture_inner");
        var cursor = NativeCombatLedgerReader.watch(c.target);
        var rule = new NestedHurt(c, outer, inner);
        MinecraftForge.EVENT_BUS.register(rule);
        try {
            require(c.hit(outer, 4), "Outer nested native call rejected");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(rule);
        }
        require(rule.calls == 1 && rule.innerAccepted, "Nested control did not enter native damage");
        var observed = cursor.harvest(false);
        require(
            observed.entries.size() == 2 && observed.entries.get(0).source == inner
                && observed.entries.get(1).source == outer,
            "Nested commit order was guessed from request order");
        near(observed.entries.get(0).healthBefore, c.target.getMaxHealth(), "Inner native baseline differs");
        near(observed.entries.get(1).healthBefore, c.target.getMaxHealth() - 3, "Outer baseline ignored nested loss");
        var report = c.finish(recorder);
        healthy(report, 2);
        near(raw(report), 7, "Nested native records were lost or duplicated");
        near(number(report, "clampedNativeLossEstimate"), 7, "Nested loss was counted twice");
        noConfirmedHealthSum(report);
    }

    private static void fatalClear(Case c) {
        var recorder = c.open("fatal_clear");
        require(
            c.hit(absolute(c.actor, "tlmnh_fixture_before_fatal"), c.target.getMaxHealth() - 2),
            "Pre-fatal call rejected");
        near(c.target.getHealth(), 2, "Pre-fatal native baseline differs");
        require(c.hit(absolute(c.actor, "tlmnh_fixture_fatal"), 10), "Fatal native call rejected");
        require(!c.target.isEntityAlive(), "Fatal fixture remained alive");
        var report = c.finish(recorder);
        healthy(report, 2);
        near(raw(report), c.target.getMaxHealth() + 8, "Fatal raw records were lost on native clear");
        near(number(report, "clampedNativeLossEstimate"), c.target.getMaxHealth(), "Fatal clamp estimate differs");
        var entries = nativeEvents(report);
        require(entries.size() == 2, "Fatal observer emitted duplicate entries");
        near(
            number(
                entries.get(1)
                    .getAsJsonObject("nativeDamage"),
                "healthBefore"),
            2,
            "Fatal event did not preserve pre-clear record");
        String frozen = report.toString();
        require(
            frozen.equals(
                recorder.finish()
                    .toString()),
            "Repeated finish changed a closed record");
        noConfirmedHealthSum(report);
    }

    private static void watermark(Case c) {
        var first = c.open("old_episode");
        require(c.hit(absolute(c.actor, "tlmnh_fixture_old"), 4), "First episode call rejected");
        var previous = c.finish(first);
        healthy(previous, 1);
        var second = c.open("new_episode_no_damage");
        var current = c.finish(second);
        healthy(current, 0);
        near(raw(current), 0, "New episode claimed native history from its baseline");
        require(
            !previous.get("session")
                .getAsString()
                .equals(
                    current.get("session")
                        .getAsString())
                && telemetry(current).get("epoch")
                    .getAsLong()
                    > telemetry(previous).get("epoch")
                        .getAsLong(),
            "Closed actor did not get a new recording epoch");
    }

    private static void faultedReopen(Case c) {
        var original = c.open("active_fault");
        var channel = c.actor.getBrainCoordinator()
            .getTelemetry();
        String previousSession = channel.snapshot().sessionId;
        channel.invalidate("fixture_expected_fault");
        require(!channel.isEnabled(), "Expected recording fault did not disable observation");
        boolean rejected = false;
        MaidEpisodeRecorder unexpected = null;
        try {
            unexpected = MaidEpisodeRecorder.open(c.actor, "fixture/forbidden_reopen");
        } catch (IllegalStateException expected) {
            rejected = true;
        } finally {
            if (unexpected != null) c.finish(unexpected);
        }
        require(
            rejected && !channel.isEnabled()
                && "fixture_expected_fault".equals(channel.getFault())
                && previousSession.equals(channel.snapshot().sessionId),
            "Fault-disabled active recorder was silently replaced");
        var invalid = c.finish(original);
        require(
            !invalid.get("validObservation")
                .getAsBoolean(),
            "Expected faulty episode became valid");
        require(
            "fixture_expected_fault".equals(
                invalid.get("fault")
                    .getAsString()),
            "Expected fault evidence lost");
        var fresh = c.open("after_fault_closed");
        var report = c.finish(fresh);
        healthy(report, 0);
        c.report.addProperty("negativeControl", "active fault rejects reopen; close permits fresh episode");
    }

    private static void unboundArrow(Case c) {
        // 显式场景准备创建policy元数据；观察器自身不能为读取元数据而创建运行时
        c.actor.getBrainCoordinator()
            .getPolicyRuntime();
        var baseline = c.open("before_unbound_arrow");
        var control = c.finish(baseline);
        healthy(control, 0);
        require(
            control.get("benchmarkEligible")
                .getAsBoolean(),
            "Unbound control was already ineligible");

        var arrow = new EntityArrow(c.fixture.world, c.actor.posX, c.actor.posY + 1, c.actor.posZ);
        c.fixture.own(arrow);
        arrow.shootingEntity = c.actor;
        require(c.fixture.world.spawnEntityInWorld(arrow), "Controlled arrow identity was not spawned");
        var recorder = c.open("unbound_damage_source_only");
        require(
            !c.actor.getBrainCoordinator()
                .getTelemetry()
                .isConfirmedProjectile(arrow),
            "Controlled old arrow accidentally acquired a current release binding");
        var source = DamageSource.causeArrowDamage(arrow, c.actor)
            .setDamageBypassesArmor()
            .setDamageIsAbsolute();
        require(c.hit(source, 4), "Unbound arrow damage-source call rejected");
        var report = c.finish(recorder);
        healthy(report, 0);
        require(
            integer(report, "unboundArrowNativeRecords") == 1 && integer(report, "directNativeRecords") == 0,
            "Unbound arrow source became a current direct or bound-arrow record");
        near(number(report, "unboundArrowNativeRecordedDamageAmount"), 4, "Unbound raw forensic amount was lost");
        near(number(report, "unboundArrowClampedNativeLossEstimate"), 4, "Unbound forensic clamp estimate differs");
        near(number(report, "clampedNativeLossEstimate"), 0, "Unbound arrow polluted current episode loss");
        near(number(report, "boundArrowNativeRecordedDamageAmount"), 0, "Unbound arrow polluted bound release amount");
        near(raw(report), 4, "Raw forensic ledger silently discarded unbound native damage");
        require(
            !report.get("benchmarkEligible")
                .getAsBoolean(),
            "Unbound arrow did not make comparison ineligible");
        noConfirmedHealthSum(report);
        c.report.addProperty(
            "sourceScope",
            "controlled native arrow DamageSource call; no arrow update, collision or bow release was executed");
        c.report.addProperty("projectileCollisionVerified", false);
        c.report.addProperty("actualBowReleaseVerified", false);
    }

    private static DamageSource absolute(EntityMaid actor, String kind) {
        return new EntityDamageSource(kind, actor).setDamageBypassesArmor()
            .setDamageIsAbsolute();
    }

    private static JsonObject telemetry(JsonObject report) {
        return report.getAsJsonObject("telemetry");
    }

    private static double raw(JsonObject report) {
        return number(telemetry(report), "nativeRecordedDamageAmount");
    }

    private static double number(JsonObject object, String key) {
        double value = object.get(key)
            .getAsDouble();
        require(Double.isFinite(value), "Non-finite native report field: " + key);
        return value;
    }

    private static int integer(JsonObject object, String key) {
        return object.get(key)
            .getAsInt();
    }

    private static List<JsonObject> nativeEvents(JsonObject report) {
        var result = new ArrayList<JsonObject>();
        for (JsonElement value : telemetry(report).getAsJsonArray("events")) {
            var event = value.getAsJsonObject();
            if ("NATIVE_DAMAGE_RECORD".equals(
                event.get("kind")
                    .getAsString()))
                result.add(event);
        }
        return result;
    }

    private static void healthy(JsonObject report, int nativeRecords) {
        require(
            report.get("validObservation")
                .getAsBoolean()
                && report.get("traceComplete")
                    .getAsBoolean(),
            "Native observation is invalid or incomplete: " + report);
        require(integer(report, "nativeRecords") == nativeRecords, "Unexpected native actor record count");
        require(
            integer(report, "boundArrowNativeRecords") == 0 && integer(report, "incomingNativeRecords") == 0,
            "Direct fixture acquired unrelated arrow or incoming records");
    }

    private static void noConfirmedHealthSum(JsonObject report) {
        var snapshot = telemetry(report);
        near(number(snapshot, "nativeCommittedHealthLoss"), 0, "Net interval became confirmed health damage");
        near(number(snapshot, "controlledCallHealthLoss"), 0, "Fixture invented controlled-call attribution");
        for (JsonElement value : snapshot.getAsJsonArray("events")) {
            var event = value.getAsJsonObject();
            if ("DAMAGE_EVIDENCE".equals(
                event.get("kind")
                    .getAsString()))
                require(
                    "UNATTRIBUTED_INTERVAL".equals(
                        event.getAsJsonObject("damage")
                            .get("grade")
                            .getAsString()),
                    "Native fixture promoted an unattributed health interval");
        }
    }

    private static void near(double actual, double expected, String message) {
        require(
            Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual - expected) <= EPSILON,
            message + ": actual=" + actual + ", expected=" + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("TLMNH telemetry native probe: " + message);
    }

    private static final class Case implements AutoCloseable {

        final Fixture fixture;
        final EntityMaid actor;
        final EntityCow target;
        final JsonObject report = new JsonObject();
        private final List<MaidEpisodeRecorder> recorders = new ArrayList<>();
        private final Set<MaidEpisodeRecorder> finished = Collections.newSetFromMap(new IdentityHashMap<>());
        private final JsonArray episodes = new JsonArray();
        private final String name;

        Case(Fixture fixture, String name) {
            this.fixture = fixture;
            this.name = name;
            report.addProperty("case", name);
            report.addProperty("passed", false);
            report.add("episodes", episodes);
            actor = new EntityMaid(fixture.world);
            fixture.own(actor);
            actor.getBrainCoordinator()
                .setSuspended(true);
            actor.setSitting(true);
            actor.setPosition(fixture.x, fixture.y, fixture.z);
            target = new EntityCow(fixture.world);
            fixture.own(target);
            fixture.targets.add(target);
            target.func_110163_bv();
            target.setPosition(fixture.x + 1.5, fixture.y, fixture.z);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            report.addProperty(
                "actor",
                actor.getUniqueID()
                    .toString());
            report.addProperty(
                "target",
                target.getUniqueID()
                    .toString());
            require(
                !actor.getBrainCoordinator()
                    .getTelemetry()
                    .isEnabled(),
                "New case did not begin with observation off");
            require(
                fixture.world.spawnEntityInWorld(actor) && fixture.world.spawnEntityInWorld(target),
                "Native fixture participants did not spawn");
            require(
                fixture.world.getEntityByID(actor.getEntityId()) == actor
                    && fixture.world.getEntityByID(target.getEntityId()) == target,
                "Native participant identity differs");
            report.addProperty(
                "targetClass",
                target.getClass()
                    .getName());
            report.addProperty("targetInitialHealth", target.getHealth());
            report.addProperty("targetMaxHealth", target.getMaxHealth());
        }

        MaidEpisodeRecorder open(String suffix) {
            var recorder = MaidEpisodeRecorder.open(actor, "native_fixture/" + name + "/" + suffix);
            recorders.add(recorder);
            recorder.watch(target);
            return recorder;
        }

        boolean hit(DamageSource source, float amount) {
            var previous = fixture.activeVictim;
            fixture.activeVictim = target;
            target.hurtResistantTime = 0;
            try {
                return target.attackEntityFrom(source, amount);
            } finally {
                fixture.activeVictim = previous;
            }
        }

        JsonObject finish(MaidEpisodeRecorder recorder) {
            var result = recorder.finish();
            if (finished.add(recorder)) {
                var summary = recorder.exportSummary();
                episodes.add(summary);
                require(
                    summary.has("artifact") && summary.getAsJsonObject("artifact")
                        .has("status")
                        && "WRITTEN".equals(
                            summary.getAsJsonObject("artifact")
                                .get("status")
                                .getAsString()),
                    "Native telemetry artifact was not exported");
                require(
                    summary.get("validObservation")
                        .getAsBoolean()
                        == result.get("validObservation")
                            .getAsBoolean()
                        && summary.get("traceComplete")
                            .getAsBoolean()
                            == result.get("traceComplete")
                                .getAsBoolean(),
                    "Export summary changed the frozen observation verdict");
            }
            return result;
        }

        @Override
        public void close() {
            for (var recorder : recorders) if (!finished.contains(recorder)) {
                try {
                    finish(recorder);
                } catch (RuntimeException | LinkageError failure) {
                    fixture.cleanupErrors.add("recorder_close:" + failure);
                    report.addProperty("passed", false);
                }
            }
        }
    }

    public static final class Fixture {

        final WorldServer world;
        final double x, y, z;
        final String run = UUID.randomUUID()
            .toString();
        final JsonArray cases = new JsonArray();
        final List<Entity> owned = new ArrayList<>();
        final Set<Entity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<EntityCow> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<String> cleanupErrors = new ArrayList<>();
        private boolean registered;
        private EntityCow activeVictim;

        Fixture(WorldServer world) {
            this.world = world;
            var spawn = world.getSpawnPoint();
            x = (spawn.posX >> 4 << 4) + 8.5;
            z = (spawn.posZ >> 4 << 4) + 8.5;
            y = Math.min(world.getHeight() - 8, Math.max(110, spawn.posY + 16));
        }

        void begin() {
            require(
                world.getChunkProvider()
                    .chunkExists(((int) x) >> 4, ((int) z) >> 4),
                "Fixture chunk is unloaded");
            var bounds = AxisAlignedBB.getBoundingBox(x - 2, y - 1, z - 2, x + 4, y + 4, z + 2);
            require(
                world.getEntitiesWithinAABB(Entity.class, bounds)
                    .isEmpty(),
                "Fixture volume already contains entities");
            for (int bx = (int) x - 2; bx <= (int) x + 3; bx++)
                for (int bz = (int) z - 2; bz <= (int) z + 2; bz++) for (int by = (int) y - 1; by <= (int) y + 3; by++)
                    require(world.isAirBlock(bx, by, bz), "Fixture requires bounded empty air");
            MinecraftForge.EVENT_BUS.register(this);
            registered = true;
        }

        void own(Entity entity) {
            if (identities.add(entity)) owned.add(entity);
        }

        void exercise(String name, Consumer<Case> scenario) {
            try (var current = new Case(this, name)) {
                cases.add(current.report);
                try {
                    scenario.accept(current);
                    current.report.addProperty("passed", true);
                } catch (RuntimeException | LinkageError failure) {
                    current.report.addProperty("error", failure.toString());
                    throw failure;
                }
            }
            require(cleanupErrors.isEmpty(), "Recorder cleanup failed");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void drops(LivingDropsEvent event) {
            if (targets.contains(event.entityLiving)) for (var item : event.drops) own(item);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void joined(EntityJoinWorldEvent event) {
            if (activeVictim == null || event.world != world
                || !(event.entity instanceof EntityItem || event.entity instanceof EntityXPOrb)) return;
            if (event.entity.getDistanceSqToEntity(activeVictim) <= 64) own(event.entity);
        }

        void cleanup() {
            activeVictim = null;
            for (Entity entity : new ArrayList<>(owned)) {
                try {
                    entity.setDead();
                } catch (RuntimeException | LinkageError failure) {
                    cleanupErrors.add("set_dead:" + failure);
                }
                try {
                    world.removeEntity(entity);
                } catch (RuntimeException | LinkageError failure) {
                    cleanupErrors.add("remove_entity:" + failure);
                }
            }
            if (registered) {
                try {
                    MinecraftForge.EVENT_BUS.unregister(this);
                } catch (RuntimeException | LinkageError failure) {
                    cleanupErrors.add("unregister_fixture:" + failure);
                }
            }
        }
    }

    public static final class HurtRule {

        private final EntityCow target;
        private final DamageSource source;
        private final boolean cancel;
        int calls;

        HurtRule(EntityCow target, DamageSource source, boolean cancel) {
            this.target = target;
            this.source = source;
            this.cancel = cancel;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving != target || event.source != source) return;
            calls++;
            if (cancel) event.setCanceled(true);
            else event.ammount = 0;
        }
    }

    public static final class NestedHurt {

        private final Case current;
        private final DamageSource outer, inner;
        int calls;
        boolean innerAccepted;

        NestedHurt(Case current, DamageSource outer, DamageSource inner) {
            this.current = current;
            this.outer = outer;
            this.inner = inner;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving == current.target && event.source == outer && calls++ == 0)
                innerAccepted = current.hit(inner, 3);
        }
    }
}
