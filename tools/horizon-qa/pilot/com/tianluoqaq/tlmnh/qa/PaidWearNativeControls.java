package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

/** 只在独立方法preflight执行，使用已排除的固定方法输入，不产生统计区组 */
public final class PaidWearNativeControls {
    private PaidWearNativeControls() {}

    public static JsonObject run(World world) {
        try {
            JsonObject declaration = new JsonParser().parse(new String(Files.readAllBytes(
                Paths.get(System.getProperty("tlmnh.qaEffectEmptyWorldManifest"))), StandardCharsets.UTF_8)).getAsJsonObject();
            FormalMethodAuthority.verifyMethod(declaration);
            require(declaration.getAsJsonObject("formalMethod").get("purpose").getAsString().equals("consumer-preflight")
                && declaration.get("trialSeed").getAsString().equals(Long.toString(0x20901L)),
                "Native paid-wear controls require excluded non-statistical preflight");
            JsonObject cases = new JsonObject();
            for (String name : new String[] {
                "nativeWear", "nativeUnbreaking", "nativeBreakReset", "nativeRepair", "snapshotCopyExcluded" }) {
                List<ItemStack> physical = items();
                if (name.equals("nativeUnbreaking")) physical.get(1).addEnchantment(Enchantment.unbreaking, 3);
                EntityZombie actor = new EntityZombie(world);
                PaidWearLedger ledger = PaidWearLedger.begin(() -> 0, () -> physical,
                    FormalMethodAuthority.artifacts(), Collections.singletonMap("maid", physical));
                try {
                    if (name.equals("nativeWear")) physical.get(0).damageItem(1, actor);
                    else if (name.equals("nativeUnbreaking")) physical.get(1).attemptDamageItem(16, new Random(0x20901L));
                    else if (name.equals("nativeBreakReset")) {
                        physical.get(1).damageItem(physical.get(1).getMaxDamage() + 11, actor);
                        physical.get(2).damageItem(physical.get(2).getMaxDamage() + 11, actor);
                    } else if (name.equals("nativeRepair")) {
                        physical.get(0).damageItem(1, actor);
                        physical.get(0).setItemDamage(0);
                    } else {
                        ItemStack copy = physical.get(0).copy();
                        copy.setItemDamage(10);
                    }
                    ledger.checkpoint(0, Collections.singletonMap("maid", physical));
                    cases.add(name, ledger.finish(1));
                } finally {
                    ledger.abort();
                }
            }
            require(cases.getAsJsonObject("nativeWear").getAsJsonObject("totals").get("sword").getAsLong() == 1,
                "Native accepted sword wear was not captured");
            boolean negated = false;
            for (JsonElement entry : cases.getAsJsonObject("nativeUnbreaking").getAsJsonArray("calls")) {
                JsonObject call = entry.getAsJsonObject();
                if (!call.get("method").getAsString().equals("attemptDamageItem")) continue;
                int delta = call.getAsJsonObject("after").get("damage").getAsInt()
                    - call.getAsJsonObject("before").get("damage").getAsInt();
                negated |= delta >= 0 && delta < call.get("requested").getAsInt();
            }
            require(negated, "Actual Unbreaking negation was not captured");
            JsonObject broken = cases.getAsJsonObject("nativeBreakReset");
            for (String role : new String[] { "bow", "shield" }) require(
                broken.getAsJsonObject("totals").get(role).getAsLong()
                    == broken.getAsJsonObject("roles").getAsJsonObject(role).getAsJsonObject("initial")
                        .get("maxDamage").getAsLong() + 1,
                "Native break/reset capacity was not captured");
            JsonObject repair = cases.getAsJsonObject("nativeRepair");
            require(repair.getAsJsonObject("repairs").get("sword").getAsLong() == 1
                && repair.getAsJsonObject("totals").get("sword").getAsLong() == 1,
                "Native repair did not retain prior payment");
            require(cases.getAsJsonObject("snapshotCopyExcluded").getAsJsonObject("totals").get("sword").getAsLong() == 0,
                "Uncommitted read-only copy counted as physical payment");
            JsonObject result = new JsonObject();
            result.addProperty("schema", "tlmnh-paid-wear-native-controls-v2");
            result.addProperty("passed", true);
            result.addProperty("statisticalSample", false);
            result.addProperty("controlSeed", "0x20901");
            result.addProperty("actorSpawned", false);
            result.addProperty("nativeBreakCosmeticRandomConsumption", true);
            result.add("cases", cases);
            JsonObject faults = new JsonObject();
            for (String name : new String[] { "observerEntryFailure", "observerReturnFailure", "nativeException",
                "observerEntryAndNativeException" }) faults.add(name, faultControl(name));
            result.add("faultControls", faults);
            return result;
        } catch (Exception problem) {
            throw new IllegalStateException("Native paid-wear method controls failed", problem);
        }
    }

    private static final class CountingRandom extends Random {
        int calls;
        final RuntimeException failure;

        CountingRandom(RuntimeException failure) {
            super(0x20901L);
            this.failure = failure;
        }

        @Override
        public int nextInt(int bound) {
            calls++;
            if (failure != null) throw failure;
            return super.nextInt(bound);
        }
    }

    private static JsonObject faultControl(String name) {
        List<ItemStack> physical = items();
        ItemStack bow = physical.get(1);
        bow.addEnchantment(Enchantment.unbreaking, 3);
        ItemStack reference = bow.copy();
        CountingRandom referenceRandom = new CountingRandom(null);
        boolean expected = reference.attemptDamageItem(16, referenceRandom);
        require(referenceRandom.calls == 16, "Actual native Unbreaking source path changed");
        boolean nativeThrows = name.equals("nativeException") || name.equals("observerEntryAndNativeException");
        RuntimeException originalFailure = new IllegalArgumentException("paid-wear-native-exception-control");
        RuntimeException observerFailure = new IllegalStateException("paid-wear-observer-exception-control");
        CountingRandom random = new CountingRandom(nativeThrows ? originalFailure : null);
        PaidWearLedger ledger = PaidWearLedger.begin(() -> 0, () -> physical,
            FormalMethodAuthority.artifacts(), Collections.singletonMap("maid", physical));
        try {
            if (!name.equals("nativeException")) ledger.failNextObserverForControl(
                name.equals("observerReturnFailure") ? "return" : "entry", observerFailure);
            Throwable caught = null;
            Boolean actual = null;
            try {
                actual = bow.attemptDamageItem(16, random);
            } catch (Throwable problem) {
                caught = problem;
            }
            require(nativeThrows ? caught == originalFailure && random.calls == 1
                : caught == null && actual != null && actual == expected && random.calls == 16
                    && bow.getItemDamage() == reference.getItemDamage(),
                "Observer changed native invocation count, return, mutation or exception identity");
            boolean checkpointRejected = false, finishRejected = false;
            try {
                ledger.checkpoint(0, Collections.singletonMap("maid", physical));
            } catch (PaidWearLedger.ObservationRejected rejection) {
                checkpointRejected = true;
            }
            try {
                ledger.finish(1);
            } catch (PaidWearLedger.ObservationRejected rejection) {
                finishRejected = true;
            }
            require(checkpointRejected && finishRejected, "Invalid observer received boundary qualification");
            JsonObject proof = ledger.snapshot();
            require(!proof.get("closed").getAsBoolean() && !proof.get("failure").isJsonNull(),
                "First observer failure or incomplete evidence was lost");
            require(proof.getAsJsonObject("failure").get("message").getAsString().equals(
                name.equals("nativeException") ? originalFailure.getMessage() : observerFailure.getMessage()),
                "Sticky first observation failure changed");
            if (nativeThrows) require(!proof.get("nativeException").isJsonNull(), "Native exception evidence was lost");
            JsonObject result = new JsonObject();
            result.addProperty("nativeMethod", "ItemStack.attemptDamageItem(16, CountingRandom)");
            result.addProperty("nativeRandomCalls", random.calls);
            result.addProperty("referenceRandomCalls", referenceRandom.calls);
            result.addProperty("normalReturnPreserved", !nativeThrows && actual == expected);
            result.addProperty("nativeExceptionIdentityPreserved", nativeThrows && caught == originalFailure);
            result.addProperty("nativeMutationPreserved", nativeThrows ? bow.getItemDamage() == 0
                : bow.getItemDamage() == reference.getItemDamage());
            result.addProperty("checkpointRejected", checkpointRejected);
            result.addProperty("finishRejected", finishRejected);
            result.add("partialEvidence", proof);
            return result;
        } finally {
            ledger.abort();
        }
    }

    private static List<ItemStack> items() {
        List<ItemStack> result = new ArrayList<>();
        Object shield = Item.itemRegistry.getObject("battlegear2:shield.wood");
        require(shield instanceof Item, "Exact registered wood shield absent");
        Item[] items = { Items.iron_sword, Items.bow, (Item) shield };
        String[] roles = { "sword", "bow", "shield" };
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = new ItemStack(items[i]);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("QACombatEffectPayload", roles[i]);
            stack.setTagCompound(tag);
            result.add(stack);
        }
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
