package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;

/** Independent startup-only canary on a fixed excluded input, with no owned actor scope. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "Botania", "mobsinfo", "hodgepodge" })
public final class BotaniaSheddingStartupTests {

    private BotaniaSheddingStartupTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "botania_startup_only")
    public static void excludedMobsInfoStartup(GameTestHelper helper) throws Exception {
        require(
            "excluded-mr21-mobsinfo-v3".equals(System.getProperty("tlmnh.qaBotaniaStartupCanary")),
            "Explicit independent startup canary required");
        require(
            !Boolean.getBoolean("tlmnh.qaEffectEmptyWorld") && System.getProperty("tlmnh.qaTacticsInput") == null
                && System.getProperty("tlmnh.qaTrialSeed") == null,
            "Startup-only input must have no combat fixture or trial");
        require(
            helper.getWorld()
                .getSeed() == 0x20901L,
            "Fixed excluded startup world seed required");
        require(
            Loader.instance()
                .hasReachedState(LoaderState.AVAILABLE),
            "Botania init must already have returned");
        BotaniaSheddingBootstrap.requireApplied();
        // This runs after FML initialization. The production observer never resolves this target early.
        Class<?> provider = Class
            .forName(BotaniaSheddingBootstrap.PROVIDER, false, BotaniaSheddingBootstrap.class.getClassLoader());
        BotaniaSheddingBootstrap.authenticate(provider);
        BotaniaSheddingBootstrap.requireApplied();
        JsonObject result = new JsonObject();
        result.addProperty("schema", "botania-excluded-startup-canary-v1");
        result.addProperty("purpose", "excluded-startup-only");
        result.addProperty("fixedExcludedWorldSeed", "0x20901");
        result.addProperty("fmlInitializationReturned", true);
        result.addProperty("tacticalWindows", 0);
        result.addProperty("ownedActorsCreated", 0);
        result.addProperty("rngCallsByCanary", 0);
        result.addProperty("formalSamples", 0);
        result.addProperty("naturalSheddingPositive", false);
        result.add("providerSelection", BotaniaSheddingBootstrap.selection());
        Files.write(
            Paths.get(System.getProperty("horizonqa.reportDir"), "botania-startup-canary.json"),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(result)
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        System.out.println("TLMNH_QA_BOTANIA_STARTUP_CANARY " + result);
        helper.succeed();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
