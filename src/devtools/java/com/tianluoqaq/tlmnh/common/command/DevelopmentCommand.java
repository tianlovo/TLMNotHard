package com.tianluoqaq.tlmnh.common.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidFaithfulFlightProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.BuiltinModelProbe;
import com.tianluoqaq.tlmnh.common.development.MaidArrowPhysicsProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBowProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBrainProbe;
import com.tianluoqaq.tlmnh.common.development.MaidBreathProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCombatProbe;
import com.tianluoqaq.tlmnh.common.development.MaidContainerProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowCoreAdapterProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidFollowTeleportProbe;
import com.tianluoqaq.tlmnh.common.development.MaidGeckoLibraryProbe;
import com.tianluoqaq.tlmnh.common.development.MaidHomeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidIntentRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidNamingProbe;
import com.tianluoqaq.tlmnh.common.development.MaidNativePhaseProbe;
import com.tianluoqaq.tlmnh.common.development.MaidNavigationBudgetProbe;
import com.tianluoqaq.tlmnh.common.development.MaidPersistenceProbe;
import com.tianluoqaq.tlmnh.common.development.MaidPolicyLifecycleProbe;
import com.tianluoqaq.tlmnh.common.development.MaidPoseRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidRangedMovementProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldNativeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidStateProbe;
import com.tianluoqaq.tlmnh.common.development.MaidTelemetryProbe;
import com.tianluoqaq.tlmnh.common.development.MaidWorldSoundRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidNamingInteractionFixture;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidVisibleStateSnapshot;
import com.tianluoqaq.tlmnh.common.development.inventory.MaidInventoryUiFixture;
import com.tianluoqaq.tlmnh.common.development.life.CabinetVisualFixture;
import com.tianluoqaq.tlmnh.common.development.life.MaidBedVisualFixture;
import com.tianluoqaq.tlmnh.common.development.life.MaidSleepVisualFixture;
import com.tianluoqaq.tlmnh.common.development.life.ShrineVisualFixture;
import com.tianluoqaq.tlmnh.common.development.persistence.MaidPlayerDiskProbe;
import com.tianluoqaq.tlmnh.common.development.visual.MaidBannerVisualFixture;
import com.tianluoqaq.tlmnh.common.development.visual.MaidCarryVisualFixture;
import com.tianluoqaq.tlmnh.common.development.visual.MaidVisualFixtureState;
import com.tianluoqaq.tlmnh.common.development.visual.TombstoneVisualFixture;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleProbe;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleRestartProbe;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 表驱动开发调试命令入口，仅随独立调试模组在显式开发开关下注册 */
public final class DevelopmentCommand extends CommandBase {

    private static final String USAGE_KEY = "commands.tlmnhdev.usage";
    private static final Map<String, Subcommand> PRE_GATE_SUBCOMMANDS = buildPreGateTable();
    private static final Map<String, Subcommand> SUBCOMMANDS = buildMainTable();

    @FunctionalInterface
    private interface Subcommand {

        void execute(ICommandSender sender, String[] args);
    }

    public static Set<String> registeredSubcommands() {
        var names = new LinkedHashMap<String, Boolean>();
        for (String key : PRE_GATE_SUBCOMMANDS.keySet()) names.put(key, Boolean.TRUE);
        for (String key : SUBCOMMANDS.keySet()) names.put(key, Boolean.TRUE);
        return Collections.unmodifiableSet(names.keySet());
    }

    @Override
    public String getCommandName() {
        return "tlmnhdev";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE_KEY;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));
        // 状态查询与按 UUID 中止需要穿透运行期夹具占用门禁
        var preGate = PRE_GATE_SUBCOMMANDS.get(args[0]);
        if (preGate != null) {
            preGate.execute(sender, args);
            return;
        }
        if (MaidBowRuntimeProbe.isFixtureReserved() || MaidLifeRuntimeProbe.isFixtureReserved()
            || MaidShieldRuntimeProbe.isActive()) {
            throw new IllegalStateException("A natural runtime experiment owns the development fixture");
        }
        var handler = SUBCOMMANDS.get(args[0]);
        if (handler == null) throw new WrongUsageException(getCommandUsage(sender));
        handler.execute(sender, args);
    }

    private static Map<String, Subcommand> buildPreGateTable() {
        var map = new LinkedHashMap<String, Subcommand>();
        register(
            map,
            "shield-runtime-status",
            arity(
                1,
                (sender, args) -> reply(
                    sender,
                    "commands.tlmnhdev.shield_runtime_status",
                    MaidShieldRuntimeProbe.status()
                        .toString())));
        register(map, "shield-runtime-abort", arity(2, (sender, args) -> {
            MaidShieldRuntimeProbe.abort(args[1]);
            reply(sender, "commands.tlmnhdev.shield_runtime_aborting");
        }));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Subcommand> buildMainTable() {
        var map = new LinkedHashMap<String, Subcommand>();
        register(map, "shield-runtime-test", arity(1, (sender, args) -> {
            String run = MaidShieldRuntimeProbe.start(worldServer(sender));
            reply(sender, "commands.tlmnhdev.shield_runtime_started", run);
        }));
        register(map, "shield-native-test", worldOnly(MaidShieldNativeProbe::run));
        register(map, "follow-core-adapter-test", worldOnly(MaidFollowCoreAdapterProbe::run));
        register(map, "follow-fallback-test", worldOnly(MaidFollowCoreAdapterProbe::runFallback));
        register(map, "navigation-stop-test", worldOnly(MaidFollowCoreAdapterProbe::runNavigationStop));
        register(map, "native-ai-phase-test", (sender, args) -> {
            if (args.length == 1) {
                MaidNativePhaseProbe.run(worldServer(sender));
            } else if (args.length == 2 && "early_component".equals(args[1])) {
                MaidNativePhaseProbe.run(worldServer(sender), true);
            } else {
                throw usage();
            }
        });
        register(map, "follow-teleport-replay", worldArg(MaidFollowTeleportProbe::run));
        register(map, "bow-runtime-test", (sender, args) -> {
            requireArity(args, 1, 2);
            MaidBowRuntimeProbe.start(worldServer(sender), recordingRequested(args));
            reply(sender, "commands.tlmnhdev.bow_runtime_started");
        });
        register(map, "life-runtime-test", (sender, args) -> {
            requireArity(args, 1, 2);
            MaidLifeRuntimeProbe.start(worldServer(sender), recordingRequested(args));
            reply(sender, "commands.tlmnhdev.life_runtime_started");
        });
        register(map, "gecko-library-test", arity(1, (sender, args) -> {
            MaidGeckoLibraryProbe.run();
            reply(sender, "commands.tlmnhdev.gecko_library_passed");
        }));
        register(map, "telemetry-native-test", worldReply(MaidTelemetryProbe::run, "telemetry_native_passed"));
        register(map, "policy-lifecycle-test", worldReply(MaidPolicyLifecycleProbe::run, "policy_lifecycle_passed"));
        register(
            map,
            "lifecycle-persist-create",
            worldReply(MaidLifecycleRestartProbe::create, "lifecycle_restart_pending"));
        register(
            map,
            "lifecycle-persist-check",
            worldReply(MaidLifecycleRestartProbe::verify, "lifecycle_restart_passed"));
        register(map, "lifecycle-test", worldReply(MaidLifecycleProbe::run, "lifecycle_passed"));
        register(map, "bow-core-test", worldReply(MaidBowProbe::run, "bow_core_passed"));
        register(map, "life-test", worldReply(MaidLifeProbe::run, "life_passed"));
        register(map, "home-test", worldReply(MaidHomeProbe::run, "home_passed"));
        register(map, "lab-world-test", arity(1, (sender, args) -> {
            AnimationLab.verifyWorld();
            reply(sender, "commands.tlmnhdev.lab_world_passed");
        }));
        register(map, "lab-enter", DevelopmentCommand::handleLabEnter);
        register(map, "lab-exit", (sender, args) -> {
            requireArity(args, 1, 2);
            AnimationLab.leave(args.length == 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender));
            reply(sender, "commands.tlmnhdev.lab_left");
        });
        register(map, "lab-observer-test", arity(2, (sender, args) -> {
            AnimationLab.verifyObserver(getPlayer(sender, args[1]));
            reply(sender, "commands.tlmnhdev.lab_observer_passed");
        }));
        register(map, "lab-state", arity(2, (sender, args) -> {
            var maid = AnimationLab.ownedSceneMaid(getPlayer(sender, args[1]));
            var snapshot = MaidVisibleStateSnapshot.capture(maid);
            snapshot.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            TLMNotHard.LOG.info("TLMNH_LAB_STATE {}", snapshot);
        }));
        register(map, "lab-naming-fixture", playerArg(MaidNamingInteractionFixture::run));
        register(map, "lab-care-fixture", playerArg(MaidCareInteractionFixture::run));
        register(map, "lab-inventory-fixture", playerArg(MaidInventoryUiFixture::run));
        register(map, "lab-visual-hunger", DevelopmentCommand::handleLabVisualHunger);
        register(map, "lab-open-original-gui", playerOnly(AnimationLab::openOriginalGui));
        register(map, "lab-open-gui", playerOnly(AnimationLab::openOwnerGui));
        register(map, "model-test", arity(1, (sender, args) -> {
            BuiltinModelProbe.run(sender.getEntityWorld());
            reply(sender, "commands.tlmnhdev.model_passed");
        }));
        register(map, "interaction-test", worldReply(MaidInteractionProbe::run, "interaction_passed"));
        register(map, "lab-world-sound", playerArg(MaidWorldSoundRuntimeProbe::run));
        register(map, "config-test", worldOnly(MaidCoreInteractionProbe::runConfig));
        register(map, "back-item-test", worldOnly(MaidCoreInteractionProbe::runBackItem));
        register(map, "favorability-test", worldArg(MaidCoreInteractionProbe::runFavorability));
        register(map, "meal-test", worldArg(MaidCoreInteractionProbe::runMeals));
        register(map, "panic-runtime-test", worldOnly(MaidCoreInteractionProbe::startPanicRuntime));
        register(map, "breath-runtime-test", worldOnly(MaidBreathProbe::start));
        register(map, "aquatic-pose-stage", playerArg(MaidPoseRuntimeProbe::stage));
        register(map, "player-disk", arity(4, (sender, args) -> {
            try {
                MaidPlayerDiskProbe.run(getPlayer(sender, args[1]), args[2], args[3]);
            } catch (Exception failure) {
                throw new IllegalStateException("Native player disk observation failed", failure);
            }
        }));
        register(map, "ranged-movement-test", worldOnly(MaidRangedMovementProbe::start));
        register(map, "navigation-budget-test", worldOnly(MaidNavigationBudgetProbe::start));
        register(map, "intent-runtime-test", worldOnly(MaidIntentRuntimeProbe::start));
        register(map, "self-care-test", worldOnly(MaidCoreInteractionProbe::runSelfCare));
        register(map, "bubbles-test", worldOnly(MaidCoreInteractionProbe::runBubbles));
        register(map, "lab-visual-fixture", playerArg(MaidCoreInteractionProbe::setVisualFixture));
        register(map, "lab-carry-visual", playerArg(MaidCarryVisualFixture::set));
        register(map, "lab-banner-visual", playerArg(MaidBannerVisualFixture::set));
        register(map, "lab-shrine-visual", playerArg(ShrineVisualFixture::set));
        register(map, "lab-cabinet-visual", playerArg(CabinetVisualFixture::set));
        register(map, "lab-sleep-visual", playerArg(MaidSleepVisualFixture::set));
        register(map, "lab-bed-visual", playerArg(MaidBedVisualFixture::set));
        register(map, "lab-tombstone-visual", playerArg(TombstoneVisualFixture::set));
        register(map, "lab-open-default-gui", playerOnly(MaidCoreInteractionProbe::openDefaultGui));
        register(map, "effects-test", worldOnly(MaidCoreInteractionProbe::runEffects));
        register(map, "lab-gui-effects", playerArg(MaidCoreInteractionProbe::setGuiEffectFixture));
        register(map, "pickup-traversal-test", worldOnly(MaidCoreInteractionProbe::runPickupAndTraversal));
        register(map, "pickup-runtime-test", worldOnly(MaidCoreInteractionProbe::startPickupRuntime));
        register(map, "world-sound-test", worldOnly(MaidCoreInteractionProbe::runWorldSound));
        register(map, "sound-test", worldOnly(MaidCoreInteractionProbe::runSound));
        register(map, "core-interactions-test", worldReply(MaidCoreInteractionProbe::run, "core_interactions_passed"));
        register(map, "brain-profile", DevelopmentCommand::handleBrainProfile);
        register(map, "brain-test", worldReply(MaidBrainProbe::run, "brain_passed"));
        register(map, "action-control-test", worldReply(MaidBrainProbe::runActionControl, "brain_passed"));
        register(map, "owner-sensor-test", worldReply(MaidBrainProbe::runOwnerSensor, "brain_passed"));
        register(map, "danger-sensor-test", worldReply(MaidBrainProbe::runDangerSensor, "brain_passed"));
        register(map, "naming-test", worldReply(MaidNamingProbe::run, "naming_passed"));
        register(map, "combat-core-test", worldReply(MaidCombatProbe::run, "combat_core_passed"));
        register(map, "arrow-physics-test", worldReply(MaidArrowPhysicsProbe::run, "arrow_physics_passed"));
        register(map, "follow-runtime-test", arity(2, (sender, args) -> {
            MaidFollowRuntimeProbe.start(getPlayer(sender, args[1]));
            reply(sender, "commands.tlmnhdev.follow_runtime_started");
        }));
        register(map, "spawn", DevelopmentCommand::handleSpawn);
        register(map, "container-test", worldReply(MaidContainerProbe::run, "container_passed"));
        register(map, "state-test", arity(1, (sender, args) -> MaidStateProbe.run(sender)));
        register(map, "persist-create", arity(1, (sender, args) -> {
            MaidPersistenceProbe.create(sender.getEntityWorld());
            reply(sender, "commands.tlmnhdev.persist_created");
        }));
        register(map, "persist-check", arity(1, (sender, args) -> {
            MaidPersistenceProbe.verify(sender.getEntityWorld());
            reply(sender, "commands.tlmnhdev.persist_passed");
        }));
        return Collections.unmodifiableMap(map);
    }

    private static void handleLabEnter(ICommandSender sender, String[] args) {
        requireArity(args, 1, 4);
        var player = args.length >= 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender);
        if (args.length == 4 && !"sitting".equals(args[3]) && !"standing".equals(args[3])) throw usage();
        AnimationLab.enter(
            player,
            args.length >= 3 ? args[2] : "geckolib:winefox_hanfu",
            args.length < 4 || "sitting".equals(args[3]));
        reply(sender, "commands.tlmnhdev.lab_entered");
    }

    private static void handleLabVisualHunger(ICommandSender sender, String[] args) {
        requireArity(args, 3, 3);
        var player = getPlayer(sender, args[1]);
        if ("restore".equals(args[2])) {
            MaidCoreInteractionProbe.setVisualFixture(player, "restore");
        } else {
            if (!"hungry".equals(args[2]) && !"full".equals(args[2])) throw usage();
            var maid = AnimationLab.ownedSceneMaid(player);
            MaidVisualFixtureState.stageHunger(maid, "full".equals(args[2]) ? 20 : 0);
        }
        TLMNotHard.LOG.info("TLMNH_VISUAL_HUNGER action={}", args[2]);
    }

    private static void handleBrainProfile(ICommandSender sender, String[] args) {
        requireArity(args, 3, 4);
        var entity = sender.getEntityWorld()
            .getEntityByID(parseInt(sender, args[1]));
        if (!(entity instanceof EntityMaid maid)) throw usage();
        MaidBrainProfile selected = null;
        for (var profile : MaidBrainProfile.values()) {
            if (profile.getId()
                .equals(args[2])) selected = profile;
        }
        if (selected == null) throw usage();
        var flight = MaidFaithfulFlightProfile.SOURCE_ORIGINAL;
        if (args.length == 4) {
            try {
                flight = MaidFaithfulFlightProfile.valueOf(args[3].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw usage();
            }
        }
        var result = maid.selectBrainProfileForDevelopment(selected, flight);
        reply(sender, "commands.tlmnhdev.brain_profile", selected.getId() + "/" + flight.name(), result.name());
    }

    private static void handleSpawn(ICommandSender sender, String[] args) {
        requireArity(args, 1, 3);
        var player = args.length >= 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender);
        var maid = new EntityMaid(player.worldObj);
        if (args.length == 3) {
            if (!BuiltinModelCatalog.contains(args[2])) throw usage();
            maid.setModelId(args[2]);
        }
        maid.bindTo(player.getUniqueID());
        maid.setPosition(player.posX + 1.5D, player.posY, player.posZ);
        if (!player.worldObj.spawnEntityInWorld(maid)) {
            throw new IllegalStateException("TLMNH development maid spawn rejected");
        }
        TLMNotHard.LOG.info(
            "TLMNH_DEV_MAID_SPAWN uuid={} model={} dimension={}",
            maid.getUniqueID(),
            maid.getModelId(),
            maid.dimension);
        reply(sender, "commands.tlmnhdev.spawned");
    }

    private static void register(Map<String, Subcommand> table, String name, Subcommand handler) {
        if (table.put(name, handler) != null) {
            throw new IllegalStateException("Duplicate tlmnhdev subcommand: " + name);
        }
    }

    private static Subcommand arity(int expected, Subcommand action) {
        return (sender, args) -> {
            requireArity(args, expected, expected);
            action.execute(sender, args);
        };
    }

    private static Subcommand worldOnly(Consumer<WorldServer> action) {
        return arity(1, (sender, args) -> action.accept(worldServer(sender)));
    }

    private static Subcommand worldReply(Consumer<WorldServer> action, String translationSuffix) {
        return arity(1, (sender, args) -> {
            action.accept(worldServer(sender));
            reply(sender, "commands.tlmnhdev." + translationSuffix);
        });
    }

    private static Subcommand worldArg(BiConsumer<WorldServer, String> action) {
        return arity(2, (sender, args) -> action.accept(worldServer(sender), args[1]));
    }

    private static Subcommand playerOnly(Consumer<EntityPlayerMP> action) {
        return arity(2, (sender, args) -> action.accept(getPlayer(sender, args[1])));
    }

    private static Subcommand playerArg(BiConsumer<EntityPlayerMP, String> action) {
        return arity(3, (sender, args) -> action.accept(getPlayer(sender, args[1]), args[2]));
    }

    private static WorldServer worldServer(ICommandSender sender) {
        return (WorldServer) sender.getEntityWorld();
    }

    private static void reply(ICommandSender sender, String key, Object... params) {
        sender.addChatMessage(new ChatComponentTranslation(key, params));
    }

    private static void requireArity(String[] args, int min, int max) {
        if (args.length < min || args.length > max) throw usage();
    }

    private static WrongUsageException usage() {
        return new WrongUsageException(USAGE_KEY);
    }

    private static boolean recordingRequested(String[] args) {
        if (args.length == 1 || "record".equals(args[1])) return true;
        if ("plain".equals(args[1])) return false;
        throw usage();
    }
}
