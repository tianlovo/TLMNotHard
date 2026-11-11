package com.tianluoqaq.tlmnh.qa;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowImpactState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.qa.ProjectileImpactTests.Environment;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 受控且可受伤的原生服务端玩家，生产发射器与自然箭碰撞；不代表真实多人网络验收 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ProjectilePvpTests {

    private ProjectilePvpTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 140, batch = "projectile_dynamic_pvp")
    public static void realFlameArrowsReadPvpAtImpactIncludingBothInFlightRuleChanges(GameTestHelper helper) {
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && !Boolean.getBoolean("tlmnh.qaProjectileImpactFallback"),
            "Actual native collision hook mode required");
        var server = MinecraftServer.getServer();
        boolean oldPvp = server.isPVPEnabled();
        helper.afterTest(() -> server.setAllowPvp(oldPvp));
        var e = new Environment(helper);
        helper.assertTrue(
            helper.getWorld()
                .getScoreboard()
                .removePlayerFromTeams(e.ally.getCommandSenderName()) && !e.f.maid.isOnSameTeam(e.ally)
                && !e.f.maid.isOwner(e.ally)
                && !e.ally.capabilities.disableDamage,
            "Controlled player must be a damageable non-owner outside the maid's team");
        var observation = new Impacts(e);
        MinecraftForge.EVENT_BUS.register(observation);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(observation));
        // 开关保持、true->false、false->true；在已自然飞行至少一tick后才变更规则和玩家位置
        boolean[] atLaunch = { false, true, true, false };
        boolean[] atImpact = { false, true, false, true };
        int[] trial = { 0 }, phase = { 0 }, frames = { 0 };
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                e.ally.extinguish();
                e.ally.setHealth(20);
                e.ally.hurtResistantTime = 0;
                e.resetActors();
                observation.attacks = 0;
                observation.hurts = 0;
                observation.burningBeforeAllAttackGates = true;
                observation.burningAtHurt = false;
                observation.cancelled = false;
                server.setAllowPvp(atLaunch[trial[0]]);
                e.newTarget();
                e.shoot(
                    trial[0] < 2 ? ModernFlightProfile.NATIVE_STATIC_COMPENSATION
                        : ModernFlightProfile.SOURCE_ORIGINAL);
                helper.assertTrue(
                    e.arrow instanceof MaidArrowImpactState && e.arrow instanceof MaidArrowSourceState
                        && ((MaidArrowSourceState) e.arrow).tlmnh$source() != null
                        && e.arrow.isBurning(),
                    "Production flame arrow lacks its native hook or retained maid source");
                phase[0] = 1;
                frames[0] = 0;
                return false;
            }
            helper.assertTrue(++frames[0] < 24, "PvP natural arrow case did not reach a real collision");
            if (phase[0] == 1) {
                if (e.arrow.ticksExisted < 1) return false;
                helper.assertTrue(!e.arrow.isDead, "Arrow stopped before the controlled in-flight rule change");
                server.setAllowPvp(atImpact[trial[0]]);
                e.cross(e.ally);
                phase[0] = 2;
                return false;
            }
            helper.assertTrue(server.isPVPEnabled() == atImpact[trial[0]], "Controlled server PvP input changed");
            if (!atImpact[trial[0]]) {
                helper.assertTrue(
                    e.ally.getHealth() == 20 && !e.ally.isBurning()
                        && observation.attacks == 0
                        && observation.hurts == 0,
                    "PvP-disabled flame arrow ignited a player or reached the damage boundary: " + observation.state());
                if (e.target.getHealth() == 200) return false;
                helper.assertTrue(
                    ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() > 0,
                    "Protected player collision was not skipped before fire/damage");
            } else {
                if (e.ally.getHealth() == 20) return false;
                // 原生玩家先在EntityPlayer、再在EntityLivingBase发出attack门事件，实际扣血只有一条hurt路径
                helper.assertTrue(
                    observation.attacks == 2 && observation.hurts == 1
                        && observation.burningBeforeAllAttackGates
                        && observation.burningAtHurt
                        && !observation.cancelled
                        && e.ally.isBurning()
                        && e.target.getHealth() == 200
                        && ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() == 0,
                    "PvP-enabled non-allied impact changed the native two attack gates / one hurt path: "
                        + observation.state());
            }
            helper.assertTrue(
                MaidProjectileImpactProtection.available(e.f.maid)
                    && !((MaidArrowSourceState) e.arrow).tlmnh$sourceStopped(),
                "Normal PvP decision incorrectly tripped impact/source failure protection");
            e.items();
            System.out.println(
                "TLMNH_PROJECTILE_PVP_PASS trial=" + trial[0]
                    + " launch="
                    + atLaunch[trial[0]]
                    + " impact="
                    + atImpact[trial[0]]
                    + " attackGates="
                    + observation.attacks
                    + " hurtEvents="
                    + observation.hurts
                    + " ignored="
                    + ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts());
            e.removeArrowAndTarget();
            phase[0] = 0;
            return ++trial[0] == atLaunch.length;
        });
    }

    public static final class Impacts {

        final Environment environment;
        int attacks, hurts;
        boolean burningBeforeAllAttackGates, burningAtHurt, cancelled;

        Impacts(Environment environment) {
            this.environment = environment;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void attack(LivingAttackEvent event) {
            if (event.entityLiving != environment.ally || event.source.getSourceOfDamage() != environment.arrow) return;
            attacks++;
            burningBeforeAllAttackGates &= environment.ally.isBurning();
            cancelled |= event.isCanceled();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving != environment.ally || event.source.getSourceOfDamage() != environment.arrow) return;
            hurts++;
            burningAtHurt |= environment.ally.isBurning();
            cancelled |= event.isCanceled();
        }

        String state() {
            return "attacks=" + attacks
                + ", hurts="
                + hurts
                + ", burningBeforeAllAttackGates="
                + burningBeforeAllAttackGates
                + ", burningAtHurt="
                + burningAtHurt
                + ", cancelled="
                + cancelled
                + ", playerHealth="
                + environment.ally.getHealth()
                + ", playerBurning="
                + environment.ally.isBurning()
                + ", targetHealth="
                + environment.target.getHealth()
                + ", ignored="
                + ((MaidArrowImpactState) environment.arrow).tlmnh$ignoredImpacts();
        }
    }
}
