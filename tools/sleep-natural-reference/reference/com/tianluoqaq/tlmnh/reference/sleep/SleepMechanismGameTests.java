package com.tianluoqaq.tlmnh.reference.sleep;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 五机制探索，完整原版自然实体；受控占用者仅为场景输入 */
@GameTestHolder("tlmnh_sleep_paired")
@PrefixGameTestTemplate(false)
public final class SleepMechanismGameTests {
    private SleepMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 330, batch = "sleep_pair_normal")
    public static void collectNormal(GameTestHelper helper) {
        SleepNaturalGameTests.collectMechanism(helper, "normal");
    }

    @GameTest(template = "cell", timeoutTicks = 330, batch = "sleep_pair_occupied_release")
    public static void collectOccupiedRelease(GameTestHelper helper) {
        SleepNaturalGameTests.collectMechanism(helper, "occupied_release");
    }

    @GameTest(template = "cell", timeoutTicks = 330, batch = "sleep_pair_unreachable")
    public static void collectUnreachable(GameTestHelper helper) {
        SleepNaturalGameTests.collectMechanism(helper, "unreachable");
    }

    @GameTest(template = "cell", timeoutTicks = 330, batch = "sleep_pair_bed_removed")
    public static void collectBedRemoved(GameTestHelper helper) {
        SleepNaturalGameTests.collectMechanism(helper, "bed_removed");
    }

    @GameTest(template = "cell", timeoutTicks = 330, batch = "sleep_pair_hurt_sleeping")
    public static void collectHurtSleeping(GameTestHelper helper) {
        SleepNaturalGameTests.collectMechanism(helper, "hurt_sleeping");
    }

    static final class Session extends SleepNaturalGameTests.Session {
        final String mechanism;
        final net.minecraft.world.Difficulty oldDifficulty;
        final boolean oldRain, oldThunder;
        final int oldRainTime, oldThunderTime, oldClearTime;
        EntityMaid holder;
        boolean applied;
        int firstSleep = -1;

        Session(GameTestHelper helper, String mechanism) {
            super(helper, "/tlmnh-sleep/paired-contract.json", "sleep-paired-reference-mechanisms-v1", "sleep-paired-" + mechanism + "-");
            this.mechanism = mechanism;
            oldDifficulty = level.getDifficulty();
            var data = level.getServer().getWorldData().overworldData();
            oldRain = data.isRaining(); oldThunder = data.isThundering();
            oldRainTime = data.getRainTime(); oldThunderTime = data.getThunderTime(); oldClearTime = data.getClearWeatherTime();
            output.addProperty("mechanism", mechanism);
            output.addProperty("profile", "original");
        }

        @Override void setup() {
            helper.assertTrue(contract.get("contract").getAsString().equals("SLEEP_PAIRED_MECHANISMS_V1")
                && contract.get("repetitions").getAsInt() == 1, "Unsupported paired exploration contract");
            for (int a = 0; a < 21; a++) for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++)
                helper.setBlock(a, b, c, Blocks.AIR);
            super.setup();
            level.getServer().setDifficulty(net.minecraft.world.Difficulty.valueOf(contract.get("difficulty").getAsString()), true);
            level.setWeatherParameters(1000000, 1000000, false, false);
            if (mechanism.equals("unreachable")) {
                for (int a = 4; a <= 10; a++) for (int b = 1; b <= 4; b++) for (int c = 1; c <= 7; c++)
                    if (!(a == 7 && (c == 4 || c == 5))) helper.setBlock(a, b, c, Blocks.STONE);
            }
            if (mechanism.equals("occupied_release")) {
                holder = new EntityMaid(level); holder.setOwnerUUID(offlineOwner); holder.setTame(true); holder.setNoAi(true);
                holder.setPos(origin.getX() + 15.5, origin.getY() + 1, origin.getZ() + 7.5);
                helper.assertTrue(level.addFreshEntity(holder), "Controlled reservation holder registration failed");
                setOccupied(true);
            }
        }

        private void setOccupied(boolean occupied) {
            var state = level.getBlockState(head);
            helper.assertTrue(state.is(InitBlocks.MAID_BED.get()), "Controlled bed disappeared");
            if (state.getValue(BlockMaidBed.OCCUPIED) != occupied)
                level.setBlock(head, state.setValue(BlockMaidBed.OCCUPIED, occupied), 3);
        }

        @Override void beforeWorld() {
            if (holder != null && !applied) setOccupied(true);
        }

        // 原MaidPoiManager只注册未占用的床头，Occupancy.ANY不会包含已移除的记录
        @Override int expectedPoiCount() { return mechanism.equals("occupied_release") ? 0 : 1; }

        @Override protected void afterSpawn() {
            var initial = new JsonObject();
            initial.addProperty("width", maid.getBbWidth()); initial.addProperty("height", maid.getBbHeight());
            initial.addProperty("health", maid.getHealth()); initial.addProperty("maxHealth", maid.getMaxHealth());
            initial.addProperty("movementSpeed", maid.getAttributeValue(Attributes.MOVEMENT_SPEED));
            initial.addProperty("model", maid.getModelId()); initial.addProperty("task", maid.getTask().getUid().toString());
            initial.addProperty("hunger", maid.getHunger()); initial.addProperty("favorability", maid.getFavorability());
            initial.addProperty("pickup", maid.isPickup()); initial.addProperty("difficulty", level.getDifficulty().name());
            initial.addProperty("raining", level.isRaining()); initial.addProperty("thundering", level.isThundering());
            var raw = new CompoundTag(); maid.saveWithoutId(raw); initial.addProperty("rawInitialNBT", raw.toString());
            var blocks = new JsonArray();
            for (int a = 0; a < 17; a++) for (int b = 0; b < 8; b++) for (int c = 0; c < 9; c++) {
                var state = level.getBlockState(origin.offset(a, b, c));
                if (state.isAir()) continue;
                var value = new JsonObject(); value.addProperty("x", a); value.addProperty("y", b); value.addProperty("z", c);
                value.addProperty("registry", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                value.addProperty("rawState", state.toString());
                value.addProperty("semantic", state.is(Blocks.STONE) ? "stone" : state.is(InitBlocks.MAID_BED.get())
                    ? "maid_bed_" + state.getValue(BlockMaidBed.PART).getSerializedName() + "_" + state.getValue(BlockMaidBed.FACING).getSerializedName() : "unknown");
                blocks.add(value);
            }
            int nonEmpty = 0;
            for (var inv : java.util.List.of(maid.getMaidInv(), maid.getHideInv(), maid.getMaidBauble()))
                for (int slot = 0; slot < inv.getSlots(); slot++) if (!inv.getStackInSlot(slot).isEmpty()) nonEmpty++;
            if (!maid.getMainHandItem().isEmpty()) nonEmpty++;
            if (!maid.getOffhandItem().isEmpty()) nonEmpty++;
            for (var stack : maid.getArmorSlots()) if (!stack.isEmpty()) nonEmpty++;
            initial.addProperty("nonEmptyItemStackCount", nonEmpty);
            initial.addProperty("activeEffectCount", maid.getActiveEffects().size());
            helper.assertTrue(nonEmpty == 0 && maid.getActiveEffects().isEmpty(), "Fresh paired original actor has items/effects");
            initial.add("nonAirBlocks", blocks); row.add("actualInputs", initial); row.add("events", new JsonArray());
            row.add("initialPosition", framesVector(maid.getX() - origin.getX(), maid.getY() - origin.getY(), maid.getZ() - origin.getZ()));
            row.add("homeCenter", framesVector(home.getX() - origin.getX(), home.getY() - origin.getY(), home.getZ() - origin.getZ()));
        }

        private static JsonArray framesVector(double x, double y, double z) {
            var a = new JsonArray(); a.add(x); a.add(y); a.add(z); return a;
        }

        @Override protected void afterFrame(JsonObject frame) {
            frame.addProperty("width", maid.getBbWidth()); frame.addProperty("height", maid.getBbHeight());
            frame.addProperty("bedPresent", level.getBlockState(head).is(InitBlocks.MAID_BED.get()));
            int index = frame.get("frame").getAsInt();
            if (maid.isSleeping() && firstSleep < 0) firstSleep = index;
            boolean release = mechanism.equals("occupied_release") && index == contract.get("releaseFrame").getAsInt();
            boolean afterSleep = firstSleep >= 0 && index == firstSleep + contract.get("afterFirstSleepingFrames").getAsInt();
            if (applied || !(release || afterSleep && (mechanism.equals("bed_removed") || mechanism.equals("hurt_sleeping")))) return;
            applied = true;
            var event = new JsonObject(); event.addProperty("frame", index); event.addProperty("kind", mechanism);
            event.addProperty("preSleeping", maid.isSleeping()); event.addProperty("healthBefore", maid.getHealth());
            if (release) setOccupied(false);
            else if (mechanism.equals("bed_removed")) { level.setBlock(head, Blocks.AIR.defaultBlockState(), 2); level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2); }
            else event.addProperty("damageAccepted", maid.hurt(level.damageSources().generic(), contract.get("damage").getAsFloat()));
            event.addProperty("postSleeping", maid.isSleeping()); event.addProperty("healthAfter", maid.getHealth());
            event.addProperty("postBedOccupied", level.getBlockState(head).is(InitBlocks.MAID_BED.get()) && level.getBlockState(head).getValue(BlockMaidBed.OCCUPIED));
            row.getAsJsonArray("events").add(event);
        }

        @Override void restoreWorld() {
            if (holder != null) { holder.discard(); holder = null; }
            var data = level.getServer().getWorldData().overworldData();
            data.setRaining(oldRain); data.setThundering(oldThunder);
            data.setRainTime(oldRainTime); data.setThunderTime(oldThunderTime); data.setClearWeatherTime(oldClearTime);
            output.addProperty("controlledReservationAndWeatherRestored", true);
            level.getServer().setDifficulty(oldDifficulty, true);
            super.restoreWorld();
        }
    }
}
