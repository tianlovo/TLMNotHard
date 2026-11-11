package com.tianluoqaq.tlmnh.devtools;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.command.DevelopmentCommand;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.MaidBowRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.MaidLifeRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.MaidShieldRuntimeProbe;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidEpisodeRecorder;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.diagnostic.combat.DevelopmentAccess;
import com.tianluoqaq.tlmnh.common.diagnostic.combat.DevelopmentHooks;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/** 独立调试与探针模组入口，仅在本地开发及 QA 实例随主模组一同挂载 */
@Mod(
    modid = TLMNotHardDevtools.MOD_ID,
    name = TLMNotHardDevtools.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:tlmnh")
public final class TLMNotHardDevtools {

    public static final String MOD_ID = "tlmnhdevtools";
    public static final String MOD_NAME = "Touhou Little Maid (Not Hard) Devtools";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DevelopmentAccess.install(ServerHooks.INSTANCE);
        if (event.getSide()
            .isClient()) {
            DevtoolsClientBootstrap.installHooks();
        }
        AnimationLab.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide()
            .isClient()) {
            DevtoolsClientBootstrap.initClientControl();
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (DevelopmentFlags.isDevCommandsEnabled()) {
            AnimationLab.validateAutoEntry();
            MaidShieldRuntimeProbe.registerForDevelopment();
            event.registerServerCommand(new DevelopmentCommand());
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        Throwable failure = null;
        for (Runnable close : new Runnable[] { MaidCoreInteractionProbe::cancelPanicRuntime,
            MaidBowRuntimeProbe::cancel, MaidLifeRuntimeProbe::cancel, MaidShieldRuntimeProbe::cancel,
            MaidEpisodeRecorder::closeAll }) {
            try {
                close.run();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private enum ServerHooks implements DevelopmentHooks {

        INSTANCE;

        @Override
        public SafeCloseable measureCombat(EntityMaid maid, CombatPhase phase) {
            var mapped = switch (phase) {
                case OBSERVATION -> CombatEffectPilotSession.Phase.OBSERVATION;
                case OFFER -> CombatEffectPilotSession.Phase.OFFER;
                case SNAPSHOT -> CombatEffectPilotSession.Phase.SNAPSHOT;
                case SUBMIT -> CombatEffectPilotSession.Phase.SUBMIT;
            };
            var measurement = CombatEffectPilotSession.measure(maid, mapped);
            return measurement == null ? SafeCloseable.NOOP : measurement::close;
        }

        @Override
        public CombatTrialHook currentCombatTrial(EntityMaid maid) {
            var session = CombatEffectPilotSession.current(maid);
            return session == null ? null : new TrialAdapter(session);
        }
    }

    private static final class TrialAdapter implements DevelopmentHooks.CombatTrialHook {

        private final CombatEffectPilotSession session;

        private TrialAdapter(CombatEffectPilotSession session) {
            this.session = session;
        }

        @Override
        public CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota, CombatPlanner planner) {
            return session.decide(snapshot, quota, planner);
        }

        @Override
        public void applied(CombatPlan.Result result, CombatWorkQuota quota) {
            session.applied(result, quota);
        }

        @Override
        public void accountObservation(CombatWorkQuota quota) {
            session.accountObservation(quota);
        }
    }
}
