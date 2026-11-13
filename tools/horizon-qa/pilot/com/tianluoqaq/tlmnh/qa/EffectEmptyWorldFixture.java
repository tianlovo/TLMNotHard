package com.tianluoqaq.tlmnh.qa;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.HodgePopulationProbe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 独立自有新进程的只读凭据；不拥有实体、RNG、区块标志或第三方队列 */
public final class EffectEmptyWorldFixture {

    static final String METHOD = "592e5482218fc3ce6b46c16cfff34ab6950827c04c92ee041f4f32c5cdec41ea";
    static final String WORLD_AUTH_REVISION = "spawn-anchored-preload-v1";
    static final String WORLD_AUTH_DESIGN = "6665281bfca2b20b63238f1ca00ec8aab7ac85b398adc4e2661128afab76b7b9";
    static final String WORLD_AUTH_ROOT = "e4a28d009e0b55149266493412f15d7fb6d6ad25cb2452cf3ef273ead596dda1";
    static final String WORLD_AUTH_OWNER_CLARIFICATION = "645b9c7ca1f27ca617693f0e223cfb25da99524c7ecb664cd6af6fe0511ffcf1";
    private static final String MINECRAFT_SHA = "c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc";
    static final String WORLD_SEED = "207710291837918000";
    private static final int EVENT_LIMIT = 24000, ENTITY_LIMIT = 1024, PREPARATION_LIMIT = 1200;
    private static EffectEmptyWorldFixture instance;

    private final JsonObject declaration;
    private final Path root, worldRoot, output;
    private final String mode;
    private final long registrationMillis;
    private long aboutToStartMillis;
    private final JsonArray history = new JsonArray(), entityHistory = new JsonArray(), dimensions = new JsonArray();
    private final Map<World, String> worlds = new IdentityHashMap<>();
    private final Map<World, EarlyWorld> pendingWorlds = new IdentityHashMap<>();
    private final Map<World, WorldCredential> credentials = new IdentityHashMap<>();
    private final Map<Integer, World> dimensionClaims = new HashMap<>();
    private final Map<Class<?>, SourceCredential> sourceCredentials = new IdentityHashMap<>();
    private final Map<World, Set<String>> diskWorldEntities = new IdentityHashMap<>();
    private ISaveHandler primarySaveHandler;
    private byte[] primarySessionLock;
    private static final int PREPARATION_BORDER_CHUNKS = 1, LOADING_FRONTIER_CHUNKS = 2;
    private int preparationLoadingRequestSeq = -1, preparationLoadingCompletedSeq = -1;
    private World closingWorld;
    private boolean authenticationInProgress, closureAtPreparing, closureAtSeal, worldSetExact;
    private int earlyObservationCount, closedEarlyObservationCount, diskEntityCount;
    private JsonObject originalPopulationSnapshot;
    private int originalPopulationSnapshotSeq = -1;
    private int originalOwnershipPredicateSeq = -1;
    private int worldSetClosureSeq = -1, closureAtPreparingSeq = -1, closureAtSealSeq = -1;
    private final Map<World, Set<String>> loaded = new IdentityHashMap<>();
    private final Map<Entity, Boolean> pendingJoins = new IdentityHashMap<>();
    private final Set<String> diskEntities = new HashSet<>();
    private final Map<String, Chunk> required = new HashMap<>();
    private final Map<String, Boolean> sealedPopulation = new HashMap<>();
    private final Set<String> sealedChunks = new HashSet<>();
    private final Map<String, Chunk> sealedChunkIdentities = new HashMap<>();
    private final long started = System.nanoTime();
    private Thread serverThread;
    private WorldServer world;
    private WorldProvider providerCredential;
    private JsonObject surfaceProviderAdmission;
    private JsonObject worldAuthenticationRejection;
    private Predicate<Entity> owned = entity -> false;
    private String phase = "DECLARED", failure;
    private String lastPreparation;
    private int preparationTicks, stableSamples, aboutToStartAttempts;
    private int readinessSamples, readinessLastSampleTick = -1, readinessFirstFlagsTick = -1,
        readinessFirstIdleTick = -1;
    private int readinessFlagsFalse, readinessIdleFalse, readinessUnavailable, readinessCompared, readinessMismatched;
    private int readinessStableBefore, readinessStableAfter, readinessMaxStable;
    private int readinessGenQueue, readinessPopQueue, readinessBacklog, readinessPopCount;
    private int readinessGenNonzero, readinessPopNonzero, readinessBacklogNonzero, readinessActivityNonzero;
    private boolean readinessFlags, readinessIdle, readinessAvailable, readinessSchedulerPresent;
    private boolean readinessSignatureCompared, readinessSignatureMatched;

    private final boolean quiescenceEnabled;
    private JsonObject inputDerivationProof;
    private OwnedSoulProtocol.Binding ownedSoulBinding;
    private DeathProductsIdentity deathProductsBinding;
    private JsonObject paidWearProviderSources;
    private HodgePopulationProbe.Observation quiescenceObservation;
    private Object[] quiescenceLoadedChunks;
    private JsonObject dim0QuiescenceProof;
    private int dim0QuiescenceProofSeq = -1;
    private long quiescenceSealChecks, quiescenceSealFailures;
    private JsonObject lastQuiescenceSeal;

    private long observedWallNanos, observerCalls;
    private boolean aboutToStart, creationObserved, loadedObserved, runFinished, finalWritten;

    static boolean enabled() {
        return Boolean.getBoolean("tlmnh.qaEffectEmptyWorld")
            && METHOD.equals(System.getProperty("tlmnh.qaEffectEmptyWorldMethodSha256", ""));
    }

    static void register() {
        if (!Boolean.getBoolean("tlmnh.qaEffectEmptyWorld")) return;
        require(enabled(), "Requested empty-world control has the wrong approved method SHA");
        require(instance == null, "Empty-world observer registered twice");
        try {
            instance = new EffectEmptyWorldFixture();
            MinecraftForge.EVENT_BUS.register(instance);
            FMLCommonHandler.instance()
                .bus()
                .register(instance);
            if (!instance.mode.equals("RUN")) EffectEmptyWorldCanary.register();
        } catch (Exception failure) {
            throw new IllegalStateException("Empty-world declaration refused", failure);
        }
    }

    private EffectEmptyWorldFixture() throws Exception {
        Path manifest = Paths.get(System.getProperty("tlmnh.qaEffectEmptyWorldManifest", ""))
            .toRealPath();
        require(
            sha(manifest).equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256", "")),
            "Empty-world launch manifest changed");
        declaration = new JsonParser().parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
            .getAsJsonObject();
        require(
            "effect-empty-world-launch-v1".equals(text(declaration, "schema"))
                && METHOD.equals(text(declaration, "methodSha256"))
                && WORLD_SEED.equals(text(declaration, "worldSeed")),
            "Wrong approved empty-world method input");
        root = checkedPath(Paths.get(text(declaration, "instanceRoot")));
        worldRoot = checkedPath(Paths.get(text(declaration, "worldRoot")));
        output = checkedPath(Paths.get(System.getProperty("horizonqa.reportDir", "")));
        require(
            root.equals(checkedPath(Paths.get("."))) && worldRoot.getParent()
                .equals(root),
            "Foreign instance/world path");
        require(
            text(declaration, "worldName").equals(
                worldRoot.getFileName()
                    .toString()),
            "World-name mismatch");
        require(
            "25".equals(System.getProperty("java.specification.version")) && System.getProperty("java.version")
                .equals(text(declaration, "javaVersion")),
            "Wrong Java runtime");
        require(
            declaration.get("freshWorldAbsent")
                .getAsBoolean(),
            "Launch did not certify an absent world");
        require(!Files.exists(worldRoot, LinkOption.NOFOLLOW_LINKS), "World existed at fixture registration");
        registrationMillis = System.currentTimeMillis();
        JsonObject registration = new JsonObject();
        registration.addProperty("worldAbsent", true);
        registration.addProperty("registrationMillis", registrationMillis);
        registration.addProperty(
            "jvmStartMillis",
            ManagementFactory.getRuntimeMXBean()
                .getStartTime());
        record("freshWorldRegistration", registration);
        quiescenceEnabled = declaration.has("quiescenceIdentity");
        require(
            quiescenceEnabled == PopulationQuiescenceContract.requested(),
            "Overlay declaration/JVM request differs");
        if (quiescenceEnabled) {
            PopulationQuiescenceContract.verifyDeclaration(declaration, output.getParent());
            PopulationQuiescenceContract.verifyProperties(declaration);
            require(PopulationQuiescenceContract.selected(), "Explicit quiescence hook selection unavailable");
        }
        mode = text(declaration, "mode");
        require(mode.equals("N") || mode.equals("P") || mode.equals("RUN"), "Unknown mechanism mode");
        require(mode.equals(System.getProperty("tlmnh.qaEffectEmptyWorldMode", "")), "Mode mismatch");
        require(
            Boolean.getBoolean("tlmnh.devCommands") && "1".equals(System.getProperty("horizonqa.turbo", "")),
            "Explicit development mode and turbo1 required");
        if (mode.equals("N")) require(
            !Boolean.getBoolean("tlmnh.qaCombatEffectPilot") && !Boolean.getBoolean("tlmnh.qaEffectArrowRandomInput"),
            "Negative mechanism must not enable V6 controls");
        else require(EffectArrowRandomInput.enabled(), "P/RUN require the original approved V6 gates");
        record("declaration", copy(declaration));
    }

    static EffectEmptyWorldFixture current() {
        require(enabled() && instance != null, "Explicit empty-world fixture required");
        return instance;
    }

    static void aboutToStart() {
        if (!enabled()) return;
        EffectEmptyWorldFixture f = current();
        f.serverThread = Thread.currentThread();
        HodgePopulationProbe.expectThread(f.serverThread);
        f.aboutToStartMillis = System.currentTimeMillis();
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("attempt", ++f.aboutToStartAttempts);
            entry.addProperty("aboutToStartMillis", f.aboutToStartMillis);
            entry.addProperty("worldRootExists", Files.exists(f.worldRoot, LinkOption.NOFOLLOW_LINKS));
            entry.addProperty("aboutToStartCompleted", f.aboutToStart);
            entry.addProperty("creationObserved", f.creationObserved);
            entry.addProperty("worldLoadObserved", f.loadedObserved);
            entry.addProperty("loadedWorldCount", DimensionManager.getWorlds().length);
            f.record("aboutToStartEntry", entry);
            require(f.aboutToStartAttempts == 1 && !f.aboutToStart, "Repeated AboutToStart callback");
            require(
                !f.creationObserved && !f.loadedObserved && DimensionManager.getWorlds().length == 0,
                "World object existed before bootstrap authentication");
            f.verifyFreshBootstrapStorage();
            f.verifyInputs();
            f.meteredClosure(f::verifyWorldAuthenticationSupplement);
            f.meteredClosure(f::verifyPreparationLoadingSupplement);
            if (f.quiescenceEnabled) f.meteredClosure(
                () -> PopulationQuiescenceContract.verifyDeclaration(f.declaration, f.output.getParent()));
            f.meteredClosure(f::verifyPriorCanaries);
            try (Stream<Path> paths = Files.walk(f.root)) {
                require(
                    !paths.anyMatch(
                        p -> p.getFileName()
                            .toString()
                            .equals("level.dat")
                            || p.getFileName()
                                .toString()
                                .equals("playerdata")
                            || p.toString()
                                .endsWith(".mca")),
                    "Existing world data in fresh instance");
            }
            f.certifySource(PilotMod.class, "qa");
            f.certifySource(EnhancedCombatController.class, "main");
            f.certifySource(GameTestHelper.class, "hqa");
            Class<?> tweaks = Class.forName("com.mitchej123.hodgepodge.config.TweaksConfig");
            f.certifySource(tweaks, "hodgepodge");
            JsonObject parsed = new JsonObject();
            for (String field : new String[] { "disableModdedChunkPopulation", "disableChunkTerrainGeneration",
                "disableWorldTypeChunkPopulation" }) {
                boolean value = tweaks.getField(field)
                    .getBoolean(null);
                require(
                    value == (field.equals("disableModdedChunkPopulation") && !f.mode.equals("N")),
                    "Unexpected effective Hodgepodge switch: " + field);
                parsed.addProperty(field, value);
            }
            parsed.addProperty("scope", "All new chunks in this dedicated JVM, until process exit");
            parsed.addProperty("transformedDefinitionBytesVerified", false);
            f.record("effectiveHodgepodgeConfig", parsed);
            f.aboutToStart = true;
            f.transition("BOOTSTRAP");
        } catch (Exception problem) {
            f.reject(problem.toString());
            throw new IllegalStateException("Fresh instance authentication failed", problem);
        }
    }

    /** 只认证本次 JVM 在首次 About 回调前建立的已核存储骨架，不创建或清理任何路径 */
    private void verifyFreshBootstrapStorage() throws Exception {
        JsonObject proof = new JsonObject();
        JsonArray entries = new JsonArray();
        proof.add("entries", entries);
        proof.addProperty("registrationMillis", registrationMillis);
        proof.addProperty(
            "jvmStartMillis",
            ManagementFactory.getRuntimeMXBean()
                .getStartTime());
        proof.addProperty("worldRootExists", Files.exists(worldRoot, LinkOption.NOFOLLOW_LINKS));
        proof.addProperty("authenticated", false);
        record("preLoadBootstrapStorage", proof);
        if (!Files.exists(worldRoot, LinkOption.NOFOLLOW_LINKS)) {
            proof.addProperty("authenticated", true);
            return;
        }
        checkedPath(worldRoot);
        require(Files.isDirectory(worldRoot, LinkOption.NOFOLLOW_LINKS), "Bootstrap world root is not a directory");
        long now = System.currentTimeMillis();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(worldRoot)) {
            paths = walk.skip(1)
                .limit(13)
                .collect(Collectors.toList());
        }
        require(paths.size() <= 12, "Unknown extra bootstrap storage entries");
        paths.sort(
            (a, b) -> worldRoot.relativize(a)
                .toString()
                .compareTo(
                    worldRoot.relativize(b)
                        .toString()));
        boolean nativeLock = false;
        for (Path path : paths) {
            checkedPath(path);
            String relative = worldRoot.relativize(path)
                .toString()
                .replace('\\', '/');
            var attributes = Files
                .readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            JsonObject row = new JsonObject();
            row.addProperty("path", relative);
            row.addProperty("directory", attributes.isDirectory());
            row.addProperty("size", attributes.size());
            row.addProperty(
                "createdMillis",
                attributes.creationTime()
                    .toMillis());
            row.addProperty(
                "modifiedMillis",
                attributes.lastModifiedTime()
                    .toMillis());
            entries.add(row);
            require(
                attributes.creationTime()
                    .toMillis() >= registrationMillis
                    && attributes.creationTime()
                        .toMillis() <= now,
                "Bootstrap entry predates this fixture: " + relative);
            if (attributes.isDirectory()) {
                require(
                    relative.equals("data") || relative.equals("AE2")
                        || relative.equals("AE2/compass")
                        || relative.equals("AE2/spawndata")
                        || relative.equals("buildcraft")
                        || relative.equals("buildcraft/blueprints")
                        || relative.equals("cofh")
                        || relative.equals("EnderStorage"),
                    "Unknown bootstrap directory: " + relative);
            } else {
                require(attributes.isRegularFile(), "Non-regular bootstrap entry: " + relative);
                if (relative.equals("session.lock")) {
                    require(attributes.size() == 8, "Malformed native bootstrap session lock");
                    long lockMillis;
                    try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                        lockMillis = input.readLong();
                    }
                    row.addProperty("nativeInitializationMillis", lockMillis);
                    require(
                        lockMillis >= registrationMillis && lockMillis <= now,
                        "Native session lock is not from this fixture JVM");
                    nativeLock = true;
                } else if (relative.equals("AE2/settings.cfg")) {
                    require(attributes.size() == 251, "Unexpected initial AE2 settings size");
                    row.addProperty("sha256", sha(path));
                    require(
                        "383d3f3714032f8df72c4c5cebbbaafa768d2db698966fdc7f9f7ad7fff37842".equals(text(row, "sha256")),
                        "Unexpected initial AE2 settings content");
                } else {
                    require(
                        (relative.equals("cofh/EnderDestinations.cfg") || relative.equals("cofh/EnderFrequencies.cfg"))
                            && attributes.size() == 0,
                        "Unknown bootstrap file: " + relative);
                }
            }
        }
        require(nativeLock, "Bootstrap world directory lacks this JVM's native session lock");
        proof.addProperty("authenticated", true);
    }

    private void verifyInputs() throws Exception {
        if (FormalMethodAuthority.requested()) {
            meteredClosure(() -> paidWearProviderSources = PaidWearProviderIdentity.preworld(this));
            record("paidWearProviderSources", paidWearProviderSources);
        }
        if (declaration.has("deathProducts") || System.getProperty(DeathProductsBootstrap.PROPERTY) != null) {
            meteredClosure(() -> deathProductsBinding = DeathProductsIdentity.verify(
                root, output, declaration, new InputDerivationProtocol.Access() {
                    public JsonObject certify(Class<?> type) throws Exception {
                        return certifySource(type, null);
                    }

                    public Path checked(Path path) throws Exception {
                        return checkedPath(path);
                    }

                    public void observed(JsonObject value) {
                        record("deathProductsObservation", value);
                    }
                }));
            record("deathProductsBinding", deathProductsBinding.proof);
        }
        if (declaration.has("ownedSoulProtocol") || System.getProperty(OwnedSoulProtocol.PROPERTY) != null) {
            meteredClosure(() -> ownedSoulBinding = OwnedSoulProtocol.verify(
                root, output, declaration, new InputDerivationProtocol.Access() {
                    public JsonObject certify(Class<?> type) throws Exception {
                        return certifySource(type, null);
                    }

                    public Path checked(Path path) throws Exception {
                        return checkedPath(path);
                    }

                    public void observed(JsonObject value) {
                        record("ownedSoulProtocolObservation", value);
                    }
                }));
            record("ownedSoulProtocolBinding", ownedSoulBinding.proof);
        }
        if (declaration.has("inputDerivation") || System.getProperty(InputDerivationProtocol.PROPERTY) != null) {
            meteredClosure(() -> inputDerivationProof = InputDerivationProtocol.verify(
                root, output, declaration, new InputDerivationProtocol.Access() {
                    public JsonObject certify(Class<?> type) throws Exception {
                        return certifySource(type, null);
                    }

                    public Path checked(Path path) throws Exception {
                        return checkedPath(path);
                    }

                    public void observed(JsonObject value) {
                        record("inputDerivationObservation", value);
                    }
                }));
            record("inputDerivationProof", inputDerivationProof);
        }
        JsonObject files = declaration.getAsJsonObject("files");
        for (Map.Entry<String, com.google.gson.JsonElement> input : files.entrySet()) {
            Path path = checkedPath(root.resolve(input.getKey()));
            require(path.startsWith(root) && Files.isRegularFile(path), "Frozen input absent: " + input.getKey());
            String expected = input.getValue()
                .getAsString();
            String actual = sha(path);
            if (inputDerivationProof != null
                && input.getKey().equals("config/AppliedEnergistics2/CustomRecipes.cfg"))
                expected = text(inputDerivationProof, "aeAfterSha256");
            if (input.getKey()
                .equals("server.properties")) {
                record(
                    "nativeServerPropertiesInput",
                    NativeServerPropertiesInput.verify(
                        root,
                        output,
                        path,
                        declaration,
                        expected,
                        actual,
                        registrationMillis,
                        aboutToStartMillis));
            } else if (!actual.equals(expected)) verifyBootstrapConfigOutput(input.getKey(), path, expected, actual);
        }
        for (String directory : new String[] { "mods", "config" }) {
            try (Stream<Path> paths = Files.walk(root.resolve(directory))) {
                List<String> names = paths.filter(Files::isRegularFile)
                    .map(
                        p -> root.relativize(p)
                            .toString()
                            .replace('\\', '/'))
                    .collect(Collectors.toList());
                for (String name : names) require(files.has(name), "Undeclared mod/config input: " + name);
            }
        }
    }

    private void verifyBootstrapConfigOutput(String relative, Path path, String beforeSha, String afterSha)
        throws Exception {
        String owner, artifact;
        switch (relative) {
            case "config/DreamCoreMod.properties":
                owner = "com.dreammaster.coremod.DreamCoreMod";
                artifact = "mods/GTNewHorizonsCoreMod-2.9.99.jar";
                break;
            case "config/hodgepodgeEarly.properties":
                owner = "com.mitchej123.hodgepodge.core.shared.EarlyConfig";
                artifact = "mods/hodgepodge-2.7.221.jar";
                break;
            case "config/IC2.ini":
                owner = "ic2.core.util.Config";
                artifact = "mods/industrialcraft-2-2.2.828-experimental.jar";
                break;
            case "config/IguanaTinkerTweaks/restrictions.cfg":
                owner = "iguanaman.iguanatweakstconstruct.restriction.RestrictionConfig";
                artifact = "mods/IguanaTweaksTConstruct-2.7.15.jar";
                break;
            case "config/jarjar.properties":
                owner = "com.mitchej123.jarjar.Config";
                artifact = "mods/jarjar-0.3.7-beta.jar";
                break;
            case "config/ysmu/cache/server/model_index.json":
                owner = "com.fox.ysmu.model.format.ModelIndexCache";
                artifact = "mods/ysmu-1.9-git+9f76452dc6-dirty.jar";
                break;
            default:
                throw new IllegalStateException("Frozen input changed: " + relative);
        }
        JsonObject input = declaration.getAsJsonObject("bootstrapConfigInputs")
            .getAsJsonObject(relative);
        require(
            owner.equals(text(input, "sourceClass")) && artifact.equals(text(input, "sourceArtifact")),
            "Wrong declared bootstrap metadata provider: " + relative);
        JsonObject source = certifySource(Class.forName(owner, false, getClass().getClassLoader()), null);
        require(
            root.resolve(artifact)
                .equals(Paths.get(text(source, "codeSource"))),
            "Wrong actual bootstrap metadata provider: " + relative);
        byte[] before = Base64.getDecoder()
            .decode(text(input, "beforeBase64"));
        require(
            beforeSha.equals(shaBytes(before)),
            "Launch metadata snapshot does not match original bytes: " + relative);
        JsonObject row = new JsonObject();
        row.addProperty("path", relative);
        row.addProperty("beforeSha256", beforeSha);
        row.addProperty("afterSha256", afterSha);
        row.addProperty(
            "beforeBase64",
            Base64.getEncoder()
                .encodeToString(before));
        row.addProperty("authenticated", false);
        row.add("actualProvider", source);
        record("bootstrapConfigOutput", row);
        byte[] after = Files.readAllBytes(path);
        row.addProperty(
            "afterBase64",
            Base64.getEncoder()
                .encodeToString(after));
        require(afterSha.equals(shaBytes(after)), "Bootstrap config changed during authentication: " + relative);
        if (relative.equals("config/ysmu/cache/server/model_index.json")) {
            verifyRestoredModelIndex(before, after, row);
        } else {
            String a = new String(before, StandardCharsets.UTF_8), b = new String(after, StandardCharsets.UTF_8);
            require(
                Arrays.equals(before, a.getBytes(StandardCharsets.UTF_8))
                    && Arrays.equals(after, b.getBytes(StandardCharsets.UTF_8)),
                "Non-UTF8 bootstrap config: " + relative);
            require(
                normalizeGeneratedComments(relative, a).equals(normalizeGeneratedComments(relative, b)),
                "Bootstrap config changed beyond its exact generated comments: " + relative);
            row.addProperty(
                "rule",
                relative.endsWith("restrictions.cfg")
                    ? "Only the two generated name-list comments may permute; duplicate names retained"
                    : "Only the provider's fixed-position generated date comment may change");
        }
        row.addProperty("authenticated", true);
    }

    private static String normalizeGeneratedComments(String relative, String text) {
        String[] lines = text.split("\n", -1);
        if (relative.endsWith("restrictions.cfg")) {
            for (String prefix : new String[] { "    # materialnames are: ", "    # partnames are: " }) {
                int count = 0;
                for (int i = 0; i < lines.length; i++) if (lines[i].startsWith(prefix)) {
                    String suffix = lines[i].endsWith("\r") ? "\r" : "";
                    String list = lines[i].substring(prefix.length(), lines[i].length() - suffix.length());
                    String[] names = list.split(", ", -1);
                    Arrays.sort(names);
                    lines[i] = prefix + String.join(", ", names) + suffix;
                    count++;
                }
                require(count == 1, "Missing or duplicate generated restriction comment");
            }
        } else {
            int index = relative.endsWith("jarjar.properties") ? 2 : 1;
            require(lines.length > index, "Missing fixed generated date comment");
            String suffix = lines[index].endsWith("\r") ? "\r" : "";
            String dateLine = lines[index].substring(0, lines[index].length() - suffix.length());
            String prefix = relative.endsWith("IC2.ini") ? "; created " : "#";
            require(dateLine.startsWith(prefix) && dateLine.length() <= 100, "Wrong generated date comment");
            java.text.DateFormat format = relative.endsWith("IC2.ini") ? java.text.DateFormat.getDateTimeInstance()
                : new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.ENGLISH);
            format.setLenient(false);
            java.text.ParsePosition position = new java.text.ParsePosition(0);
            String date = dateLine.substring(prefix.length());
            require(
                format.parse(date, position) != null && position.getIndex() == date.length(),
                "Malformed provider-generated date comment");
            lines[index] = prefix + "<provider-generated-date>" + suffix;
        }
        return String.join("\n", lines);
    }

    private void verifyRestoredModelIndex(byte[] before, byte[] after, JsonObject row) throws Exception {
        JsonObject original = new JsonParser().parse(new String(before, StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject actual = new JsonParser().parse(new String(after, StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonArray entries = original.getAsJsonArray("entries");
        require(
            entries.size() == 29 && actual.getAsJsonArray("entries")
                .size() == 0,
            "Unexpected YSM model-index bootstrap transition");
        original.remove("entries");
        actual.remove("entries");
        require(
            original.equals(actual) && original.toString()
                .equals(actual.toString()),
            "YSM model-index header or typed fields changed");
        Class<?> manager = Class.forName("com.fox.ysmu.model.ServerModelManager", false, getClass().getClassLoader());
        JsonObject source = certifySource(manager, null);
        require(
            root.resolve("mods/ysmu-1.9-git+9f76452dc6-dirty.jar")
                .equals(Paths.get(text(source, "codeSource"))),
            "Wrong actual YSM registry provider");
        var field = manager.getField("CACHE_NAME_INFO");
        require(
            field.getDeclaringClass() == manager && field.getType() == Map.class
                && field.getModifiers() == (java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC
                    | java.lang.reflect.Modifier.FINAL),
            "YSM public registration API changed");
        Map<?, ?> registered = (Map<?, ?>) field.get(null);
        Set<String> ids = new HashSet<>();
        JsonArray witnesses = declaration.getAsJsonArray("modelSourceInputs");
        require(witnesses.size() == entries.size(), "YSM model source/input witness count changed");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i)
                .getAsJsonObject(),
                witness = witnesses.get(i)
                    .getAsJsonObject();
            require(
                text(entry, "modelId").equals(text(witness, "modelId"))
                    && text(entry, "source").equals(text(witness, "source"))
                    && text(entry, "namespace").equals(text(witness, "namespace")),
                "YSM source witness mismatch");
            require(
                ids.add(text(entry, "modelId")) && registered.get(text(entry, "modelId")) != null,
                "YSM restored runtime registration missing or duplicated");
            JsonObject files = witness.getAsJsonObject("files");
            require(
                !files.entrySet()
                    .isEmpty(),
                "Empty YSM model asset witness");
            Path modelRoot = checkedPath(
                root.resolve("config/ysmu")
                    .resolve(text(entry, "namespace"))
                    .resolve(text(entry, "source")));
            for (Map.Entry<String, com.google.gson.JsonElement> asset : files.entrySet()) {
                Path path = checkedPath(root.resolve(asset.getKey()));
                String expected = asset.getValue()
                    .getAsString();
                require(
                    (path.equals(modelRoot) || path.startsWith(modelRoot))
                        && expected.equals(text(declaration.getAsJsonObject("files"), asset.getKey()))
                        && path.startsWith(root.resolve("config/ysmu"))
                        && Files.isRegularFile(path)
                        && expected.equals(sha(path)),
                    "YSM copied model asset changed: " + asset.getKey());
            }
        }
        require(
            registered.keySet()
                .equals(ids),
            "YSM actual model registry differs from frozen model IDs");
        row.addProperty("rule", "Known provider restore-hit registration without re-recording empties derived index");
        row.addProperty("frozenModelSources", entries.size());
        row.addProperty("actualRegisteredModels", registered.size());
        row.addProperty("allCopiedModelAssetsIntact", true);
        row.add("modelIds", new com.google.gson.Gson().toJsonTree(new java.util.TreeSet<>(ids)));
        row.add("actualRegistryProvider", source);
    }

    private static String shaBytes(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private void verifyPriorCanaries() throws Exception {
        if (FormalMethodAuthority.requested()) record("formalAuthorityVerified",
            FormalMethodAuthority.proof(declaration, output.getParent()));
        JsonArray priors = declaration.getAsJsonArray("priorCanaries");
        require(priors.size() == (mode.equals("N") ? 0 : mode.equals("P") ? 1 : 2), "Missing cold-start prerequisites");
        for (int i = 0; i < priors.size(); i++) {
            JsonObject prior = priors.get(i)
                .getAsJsonObject();
            String expected = i == 0 ? "N" : "P";
            require(expected.equals(text(prior, "mode")), "Cold-start prerequisite order changed");
            Path receipt = checkedPath(Paths.get(text(prior, "receipt")));
            require(
                sha(receipt).equals(System.getProperty("tlmnh.qaEffectEmptyWorldPrior" + expected + "Sha256", "")),
                "Prior canary receipt drift");
            JsonObject result = new JsonParser().parse(new String(Files.readAllBytes(receipt), StandardCharsets.UTF_8))
                .getAsJsonObject();
            require(
                result.get("passed")
                    .getAsBoolean() && expected.equals(text(result, "mode"))
                    && METHOD.equals(text(result, "methodSha256"))
                    && text(result, "manifestSha256").equals(text(prior, "manifestSha256")),
                "Prior canary not accepted");
            JsonObject artifacts = declaration.getAsJsonObject("artifacts"),
                files = declaration.getAsJsonObject("files");
            require(
                text(result, "mainSha256").equals(text(files, text(artifacts, "main")))
                    && text(result, "qaSha256").equals(text(files, text(artifacts, "qa"))),
                "Cross-JAR canary reuse refused");
            verifyPriorWorldAuthentication(result, receipt);
            verifyPriorPreparationLoading(result, receipt);
            if (quiescenceEnabled) verifyPriorQuiescence(result, receipt);
            record("priorColdStartCanary", result);
        }
    }

    private JsonObject certifySource(Class<?> type, String artifact) throws Exception {
        require(
            type.getProtectionDomain() != null && type.getProtectionDomain()
                .getCodeSource() != null,
            "Missing actual CodeSource: " + type.getName());
        var location = type.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        Path path = checkedPath(PackagedClassResourceResolver.artifactOf(type));
        require(path.startsWith(root) && Files.isRegularFile(path), "Unowned CodeSource: " + type.getName());
        String relative = root.relativize(path)
            .toString()
            .replace('\\', '/');
        require(
            declaration.getAsJsonObject("files")
                .has(relative) && sha(path).equals(text(declaration.getAsJsonObject("files"), relative)),
            "Unfrozen class source");
        if (artifact != null) require(
            relative.equals(text(declaration.getAsJsonObject("artifacts"), artifact)),
            "Actual artifact source mismatch: " + artifact);
        JsonObject proof = new JsonObject();
        proof.addProperty("class", type.getName());
        proof.addProperty("codeSource", path.toString());
        proof.addProperty("actualCodeSourceUrl", location.toExternalForm());
        proof.addProperty("actualCodeSourceProtocol", location.getProtocol());
        proof.addProperty("sourceSha256", sha(path));
        proof.addProperty("transformedDefinitionBytesVerified", false);
        record("classSource", proof);
        return proof;
    }

    private void verifyWorldAuthenticationSupplement() throws Exception {
        require(
            WORLD_AUTH_REVISION.equals(text(declaration, "worldAuthRevision"))
                && WORLD_AUTH_DESIGN.equals(text(declaration, "worldAuthDesignSha256"))
                && WORLD_AUTH_ROOT.equals(text(declaration, "worldAuthRootAcceptanceSha256"))
                && WORLD_AUTH_OWNER_CLARIFICATION.equals(text(declaration, "worldAuthOwnerClarificationSha256"))
                && METHOD.equals(text(declaration, "baseMethodSha256")),
            "Wrong world authentication supplement");
        require(
            WORLD_AUTH_REVISION.equals(System.getProperty("tlmnh.qaWorldAuthRevision", ""))
                && WORLD_AUTH_DESIGN.equals(System.getProperty("tlmnh.qaWorldAuthDesignSha256", ""))
                && WORLD_AUTH_ROOT.equals(System.getProperty("tlmnh.qaWorldAuthRootAcceptanceSha256", "")),
            "World authentication command binding differs");
        require(
            WORLD_AUTH_OWNER_CLARIFICATION.equals(System.getProperty("tlmnh.qaWorldAuthOwnerClarificationSha256", "")),
            "Original owner correction command binding differs");
        JsonObject inputs = declaration.getAsJsonObject("worldAuthInputs");
        String[][] expected = { { "report.md", "84a1d020bdf92260bf962e3082dd0ad7e0b28f3b82c61193c96055306c32aac7" },
            { "design.md", WORLD_AUTH_DESIGN }, { "root-review.json", WORLD_AUTH_ROOT },
            { "root-owner-clarification.json", WORLD_AUTH_OWNER_CLARIFICATION },
            { "source-bindings.json", "f8510b6a9ce9c7fa02dd3aac8413ee9c5ea02fda437c68797dc1570b0e866911" } };
        require(
            inputs.entrySet()
                .size() == expected.length,
            "Supplement input set changed");
        for (String[] pair : expected) {
            JsonObject input = inputs.getAsJsonObject(pair[0]);
            Path path = checkedPath(Paths.get(text(input, "path")));
            require(
                path.equals(
                    checkedPath(
                        output.getParent()
                            .resolve("empty-world-world-auth-inputs")
                            .resolve(pair[0])))
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && pair[1].equals(text(input, "sha256"))
                    && pair[1].equals(sha(path)),
                "Actual supplement file binding failed: " + pair[0]);
            if (pair[0].equals("root-review.json")) {
                JsonObject review = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject();
                require(
                    "ACCEPTED_FOR_PRIVATE_IMPLEMENTATION_INDEPENDENT_PEER_REQUIRED_BEFORE_PROMOTION"
                        .equals(text(review, "status")) && WORLD_AUTH_DESIGN.equals(text(review, "designSha256")),
                    "Root acceptance missing");
            }
            if (pair[0].equals("root-owner-clarification.json")) {
                JsonObject correction = new JsonParser()
                    .parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject();
                require(
                    "ACCEPTED_SOURCE_CORRECTION_PRESERVE_ORIGINAL_ACTIVE_OWNERSHIP".equals(text(correction, "status"))
                        && WORLD_AUTH_DESIGN.equals(text(correction, "designSha256"))
                        && WORLD_AUTH_ROOT.equals(text(correction, "rootReviewSha256")),
                    "Original owner correction missing");
            }
        }
        Path driver = checkedPath(Paths.get(text(declaration, "worldAuthDriverPath")));
        require(
            driver.equals(
                checkedPath(
                    output.getParent()
                        .resolve("tested-empty-world-driver.py")))
                && Files.isRegularFile(driver, LinkOption.NOFOLLOW_LINKS)
                && sha(driver).equals(text(declaration, "driverSha256"))
                && text(declaration, "driverSha256").equals(System.getProperty("tlmnh.qaWorldAuthDriverSha256", "")),
            "Actual supplement driver binding failed");
        record("worldAuthenticationSupplement", copy(inputs));
    }

    private void observeReadiness(boolean flags, JsonObject population, boolean idle, boolean compared, boolean matched,
        int stableBefore, int stableAfter) {
        long before = System.nanoTime();
        observerCalls++;
        HodgePopulationProbe.enterPaidScope();
        try {
            readinessSamples++;
            readinessLastSampleTick = preparationTicks;
            readinessFlags = flags;
            readinessIdle = idle;
            if (!flags) readinessFlagsFalse++;
            else if (readinessFirstFlagsTick < 0) readinessFirstFlagsTick = preparationTicks;
            if (!idle) readinessIdleFalse++;
            else if (readinessFirstIdleTick < 0) readinessFirstIdleTick = preparationTicks;
            readinessAvailable = population.has("available") && population.get("available")
                .getAsBoolean();
            readinessSchedulerPresent = population.has("schedulerPresent") && population.get("schedulerPresent")
                .getAsBoolean();
            if (readinessAvailable) {
                readinessGenQueue = population.get("genQueue")
                    .getAsInt();
                readinessPopQueue = population.get("popQueue")
                    .getAsInt();
                readinessBacklog = population.get("pipelineBacklog")
                    .getAsInt();
                readinessPopCount = population.get("popCount")
                    .getAsInt();
                if (readinessGenQueue != 0) readinessGenNonzero++;
                if (readinessPopQueue != 0) readinessPopNonzero++;
                if (readinessBacklog != 0) readinessBacklogNonzero++;
                if (readinessPopCount != 0) readinessActivityNonzero++;
            } else readinessUnavailable++;
            readinessSignatureCompared = compared;
            readinessSignatureMatched = compared && matched;
            if (compared) {
                readinessCompared++;
                if (!matched) readinessMismatched++;
            }
            readinessStableBefore = stableBefore;
            readinessStableAfter = stableAfter;
            readinessMaxStable = Math.max(readinessMaxStable, stableAfter);
        } finally {
            HodgePopulationProbe.exitPaidScope();
            observedWallNanos += System.nanoTime() - before;
        }
    }

    private JsonObject readinessObservation() {
        JsonObject row = new JsonObject();
        row.addProperty("schema", "effect-readiness-observation-v1");
        row.addProperty("sampleCount", readinessSamples);
        row.addProperty("lastSampleTick", readinessLastSampleTick);
        row.addProperty("currentPreparationTicks", preparationTicks);
        row.addProperty("currentTickEvaluated", readinessSamples > 0 && readinessLastSampleTick == preparationTicks);
        row.addProperty("flags", readinessFlags);
        row.addProperty("idle", readinessIdle);
        row.addProperty("flagsFalseCount", readinessFlagsFalse);
        row.addProperty("idleFalseCount", readinessIdleFalse);
        row.addProperty("firstFlagsTrueTick", readinessFirstFlagsTick);
        row.addProperty("firstIdleTrueTick", readinessFirstIdleTick);
        row.addProperty("populationUnavailableCount", readinessUnavailable);
        JsonObject population = new JsonObject();
        population.addProperty("available", readinessAvailable);
        population.addProperty("schedulerPresent", readinessSchedulerPresent);
        population.addProperty("genQueue", readinessAvailable ? readinessGenQueue : null);
        population.addProperty("popQueue", readinessAvailable ? readinessPopQueue : null);
        population.addProperty("pipelineBacklog", readinessAvailable ? readinessBacklog : null);
        population.addProperty("popCount", readinessAvailable ? readinessPopCount : null);
        row.add("lastPopulation", population);
        row.addProperty("genQueueNonzeroCount", readinessGenNonzero);
        row.addProperty("popQueueNonzeroCount", readinessPopNonzero);
        row.addProperty("pipelineBacklogNonzeroCount", readinessBacklogNonzero);
        row.addProperty("popCountNonzeroCount", readinessActivityNonzero);
        row.addProperty("signatureCompared", readinessSignatureCompared);
        row.addProperty("signatureMatched", readinessSignatureMatched);
        row.addProperty("signatureComparedCount", readinessCompared);
        row.addProperty("signatureMismatchCount", readinessMismatched);
        row.addProperty("stableBefore", readinessStableBefore);
        row.addProperty("stableAfter", readinessStableAfter);
        row.addProperty("maxStableSamples", readinessMaxStable);
        return row;
    }

    private void verifyPreparationLoadingSupplement() throws Exception {
        PreparationLoadingContract.verifyIdentity(declaration);
        String[] properties = { "Revision", "DesignSha256", "ContractSha256", "RootAcceptanceSha256",
            "SourceLedgerSha256" };
        int index = 0;
        for (String value : PreparationLoadingContract.identity()
            .values())
            require(
                value.equals(System.getProperty("tlmnh.qaPreparationLoading" + properties[index++], "")),
                "Loading property differs");
        JsonObject contract = PreparationLoadingContract.verifyFiles(
            output.getParent(),
            declaration.getAsJsonObject("loadingInputs"),
            EffectEmptyWorldFixture::checkedPath);
        require(
            contract.get("preparationBorderChunks")
                .getAsInt() == PREPARATION_BORDER_CHUNKS
                && contract.get("loadingFrontierChunks")
                    .getAsInt() == LOADING_FRONTIER_CHUNKS
                && declaration.get("preparationBorderChunks")
                    .getAsInt() == PREPARATION_BORDER_CHUNKS
                && declaration.get("loadingFrontierChunks")
                    .getAsInt() == LOADING_FRONTIER_CHUNKS
                && PreparationLoadingContract.plan(mode)
                    .equals(declaration.get("preparationLoadingPlan")),
            "Loading contract mode/frontier differs");
        JsonObject nativeArtifacts = contract.getAsJsonObject("nativeArtifacts");
        for (Map.Entry<String, com.google.gson.JsonElement> entry : nativeArtifacts.entrySet()) require(
            entry.getValue()
                .equals(
                    declaration.getAsJsonObject("files")
                        .get(entry.getKey())),
            "Loading native source differs");
        record("preparationLoadingSupplement", copy(declaration.getAsJsonObject("loadingInputs")));
    }

    private JsonObject preparationLoadingSnapshot() {
        JsonObject row = new JsonObject();
        row.addProperty("schema", "effect-preparation-loading-observation-v1");
        PreparationLoadingContract.putIdentity(row);
        row.addProperty("mode", mode);
        row.add("plan", PreparationLoadingContract.plan(mode));
        row.addProperty("requestSeq", preparationLoadingRequestSeq);
        row.addProperty("completedSeq", preparationLoadingCompletedSeq);
        return row;
    }

    private void verifyPriorPreparationLoading(JsonObject accepted, Path acceptedPath) throws Exception {
        PreparationLoadingContract.verifyIdentity(accepted);
        String priorMode = text(accepted, "mode");
        JsonObject plan = PreparationLoadingContract.plan(priorMode);
        require(plan.equals(accepted.get("preparationLoadingPlan")), "Prior loading mode plan differs");
        Path receiptPath = checkedPath(
            acceptedPath.getParent()
                .resolve("preparation-loading-receipt.json"));
        byte[] receiptBytes = Files.readAllBytes(receiptPath);
        require(
            PreparationLoadingContract.digest(receiptBytes)
                .equals(text(accepted, "preparationLoadingReceiptSha256")),
            "Prior actual loading receipt drift");
        JsonObject receipt = PreparationLoadingContract.parse(receiptBytes);
        PreparationLoadingContract.verifyIdentity(receipt);
        require(
            "effect-preparation-loading-driver-receipt-v1".equals(text(receipt, "schema")) && receipt.get("passed")
                .getAsBoolean() && priorMode.equals(text(receipt, "mode")) && plan.equals(receipt.get("plan")),
            "Prior loading receipt mode/status differs");
        for (String field : new String[] { "driverSha256", "mainSha256", "qaSha256", "sourceBaselineReceiptSha256",
            "sourceInventorySha256", "finalObservationSha256" })
            require(
                text(accepted, field).equals(text(receipt, field)),
                "Prior loading receipt chain differs: " + field);
        Path finalPath = checkedPath(
            acceptedPath.getParent()
                .resolve("empty-world-final.json"));
        byte[] finalBytes = Files.readAllBytes(finalPath);
        require(
            PreparationLoadingContract.digest(finalBytes)
                .equals(text(receipt, "finalObservationSha256")),
            "Prior loading final bytes drift");
        JsonObject priorFinal = PreparationLoadingContract.parse(finalBytes);
        JsonObject observation = priorFinal.getAsJsonObject("preparationLoading");
        PreparationLoadingContract.verifyIdentity(observation);
        require(
            observation.equals(receipt.get("observation")) && plan.equals(observation.get("plan"))
                && priorMode.equals(text(observation, "mode")),
            "Prior actual loading observation differs");
    }

    private void verifyPriorWorldAuthentication(JsonObject result, Path receipt) throws Exception {
        require(
            receipt.equals(
                checkedPath(
                    FormalMethodAuthority.priorSuite(declaration, output.getParent())
                        .resolve(text(result, "mode"))
                        .resolve("accepted.json"))),
            "Prior canary is outside this fresh N/P/R chain");
        require(
            METHOD.equals(text(result, "baseMethodSha256"))
                && WORLD_AUTH_REVISION.equals(text(result, "worldAuthRevision"))
                && WORLD_AUTH_DESIGN.equals(text(result, "worldAuthDesignSha256"))
                && WORLD_AUTH_ROOT.equals(text(result, "worldAuthRootAcceptanceSha256"))
                && WORLD_AUTH_OWNER_CLARIFICATION.equals(text(result, "worldAuthOwnerClarificationSha256"))
                && text(declaration, "driverSha256").equals(text(result, "driverSha256"))
                && text(declaration, "sourceBaselineReceiptSha256").equals(text(result, "sourceBaselineReceiptSha256"))
                && text(declaration, "sourceInventorySha256").equals(text(result, "sourceInventorySha256")),
            "Prior canary supplement/source identity differs");
        Path finalPath = checkedPath(
            receipt.getParent()
                .resolve("empty-world-final.json"));
        Path closurePath = checkedPath(
            receipt.getParent()
                .resolve("world-auth-closure-receipt.json"));
        require(
            sha(finalPath).equals(text(result, "finalObservationSha256"))
                && sha(closurePath).equals(text(result, "worldAuthenticationClosureReceiptSha256")),
            "Prior actual final/closure receipt drift");
        JsonObject prior = new JsonParser().parse(new String(Files.readAllBytes(finalPath), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject closure = new JsonParser().parse(new String(Files.readAllBytes(closurePath), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject auth = prior.getAsJsonObject("worldAuthentication");
        require(
            "CLOSED".equals(text(prior, "phase")) && prior.get("runFinished")
                .getAsBoolean()
                && prior.get("failure")
                    .isJsonNull()
                && WORLD_AUTH_REVISION.equals(text(auth, "revision"))
                && WORLD_AUTH_DESIGN.equals(text(auth, "designSha256"))
                && WORLD_AUTH_ROOT.equals(text(auth, "rootAcceptanceSha256"))
                && WORLD_AUTH_OWNER_CLARIFICATION.equals(text(auth, "ownerClarificationSha256"))
                && auth.get("pendingWorldCount")
                    .getAsInt() == 0
                && auth.get("unclosedObservationCount")
                    .getAsInt() == 0
                && auth.get("earlyObservationCount")
                    .getAsInt()
                    == auth.get("closedEarlyObservationCount")
                        .getAsInt()
                && auth.get("worldSetExact")
                    .getAsBoolean()
                && auth.get("closureAtPreparing")
                    .getAsBoolean()
                && auth.get("closureAtSeal")
                    .getAsBoolean(),
            "Prior native world closure absent");
        require(
            "effect-world-auth-driver-closure-v1".equals(text(closure, "schema")) && closure.get("passed")
                .getAsBoolean()
                && WORLD_AUTH_REVISION.equals(text(closure, "worldAuthRevision"))
                && WORLD_AUTH_DESIGN.equals(text(closure, "worldAuthDesignSha256"))
                && WORLD_AUTH_ROOT.equals(text(closure, "worldAuthRootAcceptanceSha256"))
                && WORLD_AUTH_OWNER_CLARIFICATION.equals(text(closure, "worldAuthOwnerClarificationSha256"))
                && text(declaration, "driverSha256").equals(text(closure, "driverSha256"))
                && text(result, "mainSha256").equals(text(closure, "mainSha256"))
                && text(result, "qaSha256").equals(text(closure, "qaSha256"))
                && text(declaration, "sourceBaselineReceiptSha256").equals(text(closure, "sourceBaselineReceiptSha256"))
                && text(declaration, "sourceInventorySha256").equals(text(closure, "sourceInventorySha256")),
            "Prior driver closure/source receipt differs");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void creating(WorldEvent.CreateSpawnPosition event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            thread();
            require(!authenticationInProgress && closingWorld == null, "Reentrant world authentication");
            require(aboutToStart && phase.equals("BOOTSTRAP"), "Creation without early fresh-dir credential");
            require(
                event.getClass() == WorldEvent.CreateSpawnPosition.class && !event.isCanceled(),
                "Native spawn-position creation was canceled or replaced");
            require(event.world.provider != null, "Creation lacks provider");
            int dimension = event.world.provider.dimensionId;
            require(
                !dimensionClaims.containsKey(dimension) && !pendingWorlds.containsKey(event.world)
                    && !credentials.containsKey(event.world),
                "Repeated or replaced dimension creation");
            require(DimensionManager.getWorld(dimension) == null, "Late creation after dimension registration");
            if (dimension == 0) require(world == null && !creationObserved, "Repeated dim0 creation");
            else require(
                creationObserved && world != null && primarySaveHandler != null,
                "Multi creation preceded dim0 Create");
            authenticationInProgress = true;
            try {
                EarlyWorld early = authenticateRoot(event.world, event);
                JsonObject proof = early.proof;
                proof.addProperty("eventCanceledObserved", event.isCanceled());
                proof.addProperty("credentialScope", "OBSERVATION_ONLY_PENDING_CREATE");
                require(
                    !event.isCanceled() && failure == null,
                    "Create canceled or authentication rejected during source check");
                int sequence = record("CreateSpawnPosition", proof);
                early.createSeq = sequence;
                proof.addProperty("createSeq", sequence);
                dimensionClaims.put(dimension, event.world);
                pendingWorlds.put(event.world, early);
                if (dimension == 0) {
                    world = (WorldServer) event.world;
                    providerCredential = event.world.provider;
                    primarySaveHandler = early.handler;
                    primarySessionLock = early.lockBytes;
                    creationObserved = true;
                }
            } finally {
                authenticationInProgress = false;
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void worldLoaded(WorldEvent.Load event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            thread();
            require(!authenticationInProgress && closingWorld == null, "Reentrant world authentication");
            require(
                event.getClass() == WorldEvent.Load.class && aboutToStart
                    && creationObserved
                    && phase.equals("BOOTSTRAP"),
                "World Load outside authenticated bootstrap");
            require(!credentials.containsKey(event.world), "Duplicate certified World.Load");
            EarlyWorld early = pendingWorlds.get(event.world);
            boolean pending = early != null;
            authenticationInProgress = true;
            closingWorld = event.world;
            try {
                if (pending) {
                    verifyIdentity(early, false);
                    early.ledger.beginLoad();
                } else {
                    require(
                        event.world.provider != null && !dimensionClaims.containsKey(event.world.provider.dimensionId),
                        "Direct Load has incoherent or replaced dimension claim");
                    early = authenticateRoot(event.world, null);
                    early.ledger.beginLoad();
                }
                require(
                    DimensionManager.getWorld(early.dimension) == event.world,
                    "DimensionManager instance mismatch at Load");
                recheckSources();
                verifyRootAndLock(early);
                JsonObject loadProof = completeIdentity(early);
                Map<String, Object> nativeChunks = currentChunkObjects(event.world);
                early.ledger.verifyChunks(nativeChunks);
                loadProof.addProperty("loadedChunkCountAtLoad", nativeChunks.size());
                loadProof.addProperty("loadedChunkIdentitySha256AtLoad", chunkIdentitySignature(nativeChunks));
                require(
                    failure == null && (early.create == null || !early.create.isCanceled()),
                    "World Load closure already rejected");
                validateEarlyRecords(early);
                JsonObject spawn = null;
                if (early.dimension == 0) {
                    spawn = worldIdentity(early.actual);
                    spawn.addProperty(
                        "spawnX",
                        early.actual.getWorldInfo()
                            .getSpawnX());
                    spawn.addProperty(
                        "spawnY",
                        early.actual.getWorldInfo()
                            .getSpawnY());
                    spawn.addProperty(
                        "spawnZ",
                        early.actual.getWorldInfo()
                            .getSpawnZ());
                }
                require(
                    history.size() + (spawn == null ? 1 : 2) <= EVENT_LIMIT,
                    "World closure event history overflow");
                verifyIdentity(early, true);
                verifyRootAndLock(early);
                require(
                    failure == null && (early.create == null || !early.create.isCanceled()),
                    "World closure canceled before commit");
                int loadSeq = record("World.Load", loadProof);
                loadProof.addProperty("loadSeq", loadSeq);
                if (spawn != null) record("nativeSpawn", spawn);
                WorldCredential credential = new WorldCredential(early, loadSeq, loadProof);
                // No observable callback or world accessor is called during this commit
                early.ledger.commit(loadSeq);
                credentials.put(event.world, credential);
                worlds.put(event.world, worldRoot.toString());
                dimensionClaims.put(early.dimension, event.world);
                loaded.put(event.world, new HashSet<>(early.ledger.observedChunkKeys));
                dimensions.add(loadProof);
                if (pending) {
                    closeEarlyRecords(credential);
                    pendingWorlds.remove(event.world);
                }
                if (early.dimension == 0) {
                    loadedObserved = true;
                }
            } finally {
                closingWorld = null;
                authenticationInProgress = false;
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void worldUnloaded(WorldEvent.Unload event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            EarlyWorld early = resolveObservationWorld(event.world, "worldUnload");
            recordObservation(early, "worldUnload", identityRow(early), -1);
            require(false, "Observed world unloaded before method completion");
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void chunkUnloaded(ChunkEvent.Unload event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            EarlyWorld early = resolveObservationWorld(event.world, "chunkUnload");
            require(
                event.getClass() == ChunkEvent.Unload.class && event.getChunk().worldObj == event.world,
                "Chunk Unload world/event identity mismatch");
            early.ledger.chunk(false, key(event.getChunk()), event.getChunk());
            JsonObject row = chunkRow(event.getChunk());
            row.addProperty("chunkIdentity", System.identityHashCode(event.getChunk()));
            recordObservation(early, "chunkUnload", row, -1, event.getChunk());
            require(!sealed() || !required.containsKey(key(event.getChunk())), "Required chunk unloaded after seal");
        });
    }

    private EarlyWorld authenticateRoot(World actual, WorldEvent.CreateSpawnPosition create) throws Exception {
        require(actual.provider != null, "World lacks provider");
        int dimension = actual.provider.dimensionId;
        require(
            actual.getClass() == (dimension == 0 ? WorldServer.class : WorldServerMulti.class),
            "Unreviewed native world class");
        require(dimension == 0 ? create != null : creationObserved, "Missing primary Create credential");
        for (Class<?> type : worldSourceTypes(actual)) certifyWorldSource(type);
        require(
            nativeAccessorOwner(
                actual.getClass(),
                World.class,
                ISaveHandler.class,
                "getSaveHandler",
                "func_72860_G",
                "M") == World.class,
            "Save-handler accessor is not the reviewed World implementation");
        ISaveHandler handler = actual.getSaveHandler();
        require(
            handler != null
                && (handler.getClass() == SaveHandler.class || handler.getClass() == AnvilSaveHandler.class),
            "Unreviewed save handler class");
        require(
            nativeAccessorOwner(
                handler.getClass(),
                SaveHandler.class,
                java.io.File.class,
                "getWorldDirectory",
                "func_75765_b",
                "b") == SaveHandler.class,
            "World-directory accessor is not the reviewed SaveHandler implementation");
        certifyWorldSource(handler.getClass());
        certifyWorldSource(actual.provider.getClass());
        require(actual.provider.worldObj == actual, "Provider world pointer mismatch");
        if (dimension != 0) require(handler == primarySaveHandler, "Multi world has another save-handler object");
        Path save = checkedPath(
            handler.getWorldDirectory()
                .toPath());
        requireSaveRoot(save, worldRoot, root);
        byte[] lock = readSessionLock();
        if (primarySessionLock != null) require(Arrays.equals(lock, primarySessionLock), "Native session lock changed");
        if (dimension == 0) validateSurface(actual);
        JsonObject proof = new JsonObject();
        proof.addProperty("dimension", dimension);
        proof.addProperty("worldIdentity", System.identityHashCode(actual));
        proof.addProperty(
            "worldClass",
            actual.getClass()
                .getName());
        proof.addProperty("providerIdentity", System.identityHashCode(actual.provider));
        proof.addProperty(
            "providerClass",
            actual.provider.getClass()
                .getName());
        proof.addProperty("saveHandlerIdentity", System.identityHashCode(handler));
        proof.addProperty("saveRoot", save.toString());
        proof.addProperty("actualRootAuthenticated", true);
        proof.addProperty(
            "sessionLockBase64",
            Base64.getEncoder()
                .encodeToString(lock));
        proof.addProperty("sessionLockSha256", shaBytes(lock));
        proof.addProperty(
            "nativeSessionInitializationMillis",
            java.nio.ByteBuffer.wrap(lock)
                .getLong());
        proof.addProperty("rootAuthenticationMillis", System.currentTimeMillis());
        proof.addProperty("registrationMillis", registrationMillis);
        proof.addProperty("scope", create == null ? "NO_EARLY_EVENT_OBSERVED" : "ACTUAL_NATIVE_CREATE");
        proof.addProperty("transformedDefinitionBytesVerified", false);
        JsonArray sources = new JsonArray();
        for (Class<?> type : worldSourceTypes(actual)) sources.add(sourceReference(type));
        proof.add("sources", sources);
        return new EarlyWorld(actual, handler, create, lock, proof);
    }

    private void validateSurface(World actual) throws Exception {
        require(actual.provider.dimensionId == 0, "Surface validation on nonprimary world");
        surfaceProviderAdmission = KnownSurfaceProviderIdentity
            .capture(actual.provider, root, declaration.getAsJsonObject("files"));
        require(
            Long.toString(actual.getSeed())
                .equals(WORLD_SEED)
                && "gtnhvvoid".equals(
                    actual.getWorldInfo()
                        .getTerrainType()
                        .getWorldTypeName())
                && "2;7,2x3,2;1;".equals(
                    actual.getWorldInfo()
                        .getGeneratorOptions()),
            "Actual world settings mismatch");
    }

    private byte[] readSessionLock() throws Exception {
        Path path = checkedPath(worldRoot.resolve("session.lock"));
        require(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == 8,
            "Malformed native world session lock");
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length == 8, "Native session lock changed during read");
        long millis;
        try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            millis = input.readLong();
        }
        require(
            millis >= registrationMillis && millis <= System.currentTimeMillis(),
            "Session lock is not from this fixture JVM");
        return bytes;
    }

    private void verifyRootAndLock(EarlyWorld early) throws Exception {
        Path path = checkedPath(
            early.handler.getWorldDirectory()
                .toPath());
        requireSaveRoot(path, worldRoot, root);
        require(
            primarySessionLock != null && Arrays.equals(primarySessionLock, readSessionLock())
                && Arrays.equals(primarySessionLock, early.lockBytes),
            "Native session lock drift");
    }

    private JsonObject completeIdentity(EarlyWorld early) throws Exception {
        verifyIdentity(early, true);
        String folder = early.provider.getSaveFolder();
        Path save = folder == null ? worldRoot : checkedPath(worldRoot.resolve(folder));
        require(save.startsWith(worldRoot) && (folder == null || !save.equals(worldRoot)), "Foreign dimension folder");
        boolean exists = Files.exists(save, LinkOption.NOFOLLOW_LINKS);
        if (exists) require(Files.isDirectory(save, LinkOption.NOFOLLOW_LINKS), "Dimension folder is not a directory");
        if (!early.ledger.observedChunkKeys.isEmpty() || early.diskObserved)
            require(exists, "Observed chunk/disk world lacks actual dimension storage");
        if (early.dimension == 0) {
            require(
                early.actual == world && early.provider == providerCredential,
                "Primary native world/provider changed");
            validateSurface(early.actual);
        }
        JsonObject proof = worldIdentity(early.actual);
        proof.add("rootProof", copy(early.proof));
        proof.addProperty("createSeq", early.createSeq);
        proof.addProperty("sameRegisteredWorld", true);
        proof.addProperty("samePrimarySaveHandler", early.handler == primarySaveHandler);
        proof.addProperty("actualDimensionPath", save.toString());
        proof.addProperty("dimensionPathExists", exists);
        proof.addProperty("scope", early.create == null ? "NO_EARLY_EVENT_OBSERVED" : "ACTUAL_CREATE_CLOSED_BY_LOAD");
        return proof;
    }

    private EarlyWorld resolveObservationWorld(World actual, String kind) throws Exception {
        thread();
        require(
            !terminal() && aboutToStart && !authenticationInProgress && closingWorld == null,
            "Observation during terminal or reentrant world authentication: " + kind);
        WorldCredential credential = credentials.get(actual);
        EarlyWorld early = credential == null ? pendingWorlds.get(actual) : credential.identity;
        if (early == null && worldAuthenticationRejection == null) {
            JsonObject witness = new JsonObject();
            witness.addProperty("schema", "effect-empty-world-auth-rejection-v1");
            witness.addProperty("eventKind", kind);
            witness.addProperty("phase", phase);
            witness.addProperty("worldIdentity", System.identityHashCode(actual));
            witness.addProperty(
                "worldClass",
                actual.getClass()
                    .getName());
            witness.addProperty("providerPresent", actual.provider != null);
            if (actual.provider != null) witness.addProperty("dimension", actual.provider.dimensionId);
            witness.addProperty("diagnosticOnly", true);
            worldAuthenticationRejection = witness;
        }
        require(early != null && creationObserved, "Event before world authentication: " + kind);
        if (credential == null) require(
            phase.equals("BOOTSTRAP") && !early.ledger.loadStarted,
            "Pending observation outside BOOTSTRAP: " + kind);
        verifyIdentity(early, credential != null);
        return early;
    }

    private void verifyIdentity(EarlyWorld early, boolean registered) {
        require(early.actual.provider != null, "Observed provider disappeared");
        early.ledger.identity(
            early.actual,
            early.actual.provider,
            early.actual.getSaveHandler(),
            early.actual.provider.dimensionId);
        World claimed = dimensionClaims.get(early.dimension);
        require(
            early.provider.worldObj == early.actual && (claimed == early.actual
                || (claimed == null && early.create == null && closingWorld == early.actual)),
            "World/provider/dimension claim changed");
        require(early.create == null || !early.create.isCanceled(), "Native Create canceled after observation");
        World actualRegistered = DimensionManager.getWorld(early.dimension);
        require(
            registered ? actualRegistered == early.actual
                : actualRegistered == null || actualRegistered == early.actual,
            "Dimension registration replaced observed world");
        require(early.handler == primarySaveHandler, "Observed save-handler object changed");
    }

    private int recordObservation(EarlyWorld early, String kind, JsonObject row, int entityIndex) {
        return recordObservation(early, kind, row, entityIndex, null);
    }

    private int recordObservation(EarlyWorld early, String kind, JsonObject row, int entityIndex, Object reference) {
        boolean pending = pendingWorlds.get(early.actual) == early;
        row.addProperty("worldIdentity", System.identityHashCode(early.actual));
        row.addProperty("dimension", early.dimension);
        row.addProperty("worldAuthentication", pending ? "EARLY_RECORDED" : "CERTIFIED");
        row.addProperty("createSeq", early.createSeq);
        if (!pending) row.addProperty("credentialLoadSeq", credentials.get(early.actual).loadSeq);
        int index = record(kind, row);
        if (pending) {
            early.ledger.recordIndex(index, entityIndex, reference);
            earlyObservationCount++;
        }
        return index;
    }

    private void validateEarlyRecords(EarlyWorld early) {
        require(
            early.ledger.indices.size() == early.ledger.entityIndices.size()
                && early.ledger.indices.size() == early.ledger.references.size(),
            "Early observation reference set differs");
        for (int i = 0; i < early.ledger.indices.size(); i++) {
            int index = early.ledger.indices.get(i), entityIndex = early.ledger.entityIndices.get(i);
            require(index >= 0 && index < history.size(), "Early history index absent");
            JsonObject details = history.get(index)
                .getAsJsonObject()
                .getAsJsonObject("details");
            require(
                "EARLY_RECORDED".equals(text(details, "worldAuthentication")) && details.get("createSeq")
                    .getAsInt() == early.createSeq,
                "Early observation already closed or misattributed");
            if (entityIndex >= 0) require(
                entityIndex < entityHistory.size() && entityHistory.get(entityIndex)
                    .getAsJsonObject()
                    .get("historySequence")
                    .getAsInt() == index,
                "Early entity history index differs");
            Object reference = early.ledger.references.get(i);
            if (reference instanceof Chunk)
                require(((Chunk) reference).worldObj == early.actual, "Early chunk reference changed world");
            if (reference instanceof EntityJoinWorldEvent) {
                EntityJoinWorldEvent join = (EntityJoinWorldEvent) reference;
                require(
                    join.world == early.actual && join.entity.worldObj == early.actual,
                    "Early entity reference changed world");
            }
        }
    }

    private void closeEarlyRecords(WorldCredential credential) {
        EarlyWorld early = credential.identity;
        for (int i = 0; i < early.ledger.indices.size(); i++) {
            int index = early.ledger.indices.get(i), entityIndex = early.ledger.entityIndices.get(i);
            JsonObject details = history.get(index)
                .getAsJsonObject()
                .getAsJsonObject("details");
            details.addProperty("worldAuthentication", "CLOSED_BY_LOAD");
            details.addProperty("credentialLoadSeq", credential.loadSeq);
            if (entityIndex >= 0) {
                JsonObject entity = entityHistory.get(entityIndex)
                    .getAsJsonObject();
                entity.addProperty("worldAuthentication", "CLOSED_BY_LOAD");
                entity.addProperty("credentialLoadSeq", credential.loadSeq);
            }
            closedEarlyObservationCount++;
        }
    }

    /** 只留首个拒绝回调的原始身份，不授予world凭据，不触发区块、provider或存储查询 */
    private void captureRejectedChunkWorld(ChunkEvent.Load event) {
        if (worldAuthenticationRejection != null) return;
        JsonObject row = new JsonObject();
        worldAuthenticationRejection = row;
        row.addProperty("schema", "effect-empty-world-auth-rejection-v1");
        row.addProperty("phase", phase);
        row.addProperty("aboutToStart", aboutToStart);
        row.addProperty("creationObserved", creationObserved);
        row.addProperty("dim0WorldLoadObserved", loadedObserved);
        row.addProperty("sameServerThread", Thread.currentThread() == serverThread);
        row.addProperty("declaredInstanceRoot", root.toString());
        row.addProperty("declaredWorldRoot", worldRoot.toString());
        row.addProperty("knownWorldCount", worlds.size());
        row.addProperty("diagnosticOnly", true);
        row.addProperty("actualTransformedDefinitionBytesVerified", false);
        try {
            World actual = event.world;
            row.add("eventClass", diagnosticClass(event.getClass()));
            row.add("worldClass", diagnosticClass(actual.getClass()));
            row.addProperty("worldIdentity", System.identityHashCode(actual));
            row.addProperty("samePrimaryWorld", actual == world);
            row.addProperty("knownWorldObject", worlds.containsKey(actual));
            WorldProvider actualProvider = actual.provider;
            row.addProperty("providerPresent", actualProvider != null);
            if (actualProvider != null) {
                int dimension = actualProvider.dimensionId;
                row.addProperty("dimension", dimension);
                row.add("providerClass", diagnosticClass(actualProvider.getClass()));
                row.addProperty("providerIdentity", System.identityHashCode(actualProvider));
                WorldServer registered = DimensionManager.getWorld(dimension);
                row.addProperty("registeredWorldPresent", registered != null);
                row.addProperty("registeredSameObject", registered == actual);
                if (registered != null) {
                    row.addProperty("registeredWorldIdentity", System.identityHashCode(registered));
                    row.add("registeredWorldClass", diagnosticClass(registered.getClass()));
                }
            }
            if (event.getClass() == ChunkEvent.Load.class) {
                Chunk chunk = event.getChunk();
                row.addProperty("chunkPresent", chunk != null);
                if (chunk != null) {
                    row.addProperty("chunkIdentity", System.identityHashCode(chunk));
                    row.addProperty("chunkWorldSameObject", chunk.worldObj == actual);
                    row.addProperty("chunkX", chunk.xPosition);
                    row.addProperty("chunkZ", chunk.zPosition);
                }
            } else row.addProperty("chunkAccessor", "UNREVIEWED_EVENT_SUBCLASS_NOT_CALLED");
            if (actual.getClass() == WorldServer.class
                || actual.getClass() == net.minecraft.world.WorldServerMulti.class) {
                var save = actual.getSaveHandler();
                row.addProperty("saveHandlerPresent", save != null);
                if (save != null) {
                    row.add("saveHandlerClass", diagnosticClass(save.getClass()));
                    if (save.getClass() == net.minecraft.world.storage.SaveHandler.class
                        || save.getClass() == net.minecraft.world.chunk.storage.AnvilSaveHandler.class) {
                        java.io.File directory = save.getWorldDirectory();
                        row.addProperty("saveDirectoryPresent", directory != null);
                        if (directory != null) {
                            Path lexical = directory.toPath()
                                .toAbsolutePath()
                                .normalize();
                            row.addProperty("actualSaveDirectory", lexical.toString());
                            row.addProperty("saveLexicallyEqualsDeclaredRoot", lexical.equals(worldRoot));
                            row.addProperty("savePathNewlyAuthenticated", false);
                        }
                    } else row.addProperty("saveDirectoryAccessor", "UNREVIEWED_HANDLER_NOT_CALLED");
                }
            } else row.addProperty("saveHandlerAccessor", "UNREVIEWED_WORLD_SUBCLASS_NOT_CALLED");
        } catch (Exception | LinkageError problem) {
            IntegrationGuard.throwIfFatal(problem);
            row.addProperty("captureFailure", problem.toString());
        }
        StackTraceElement[] stack = Thread.currentThread()
            .getStackTrace();
        JsonArray frames = new JsonArray();
        for (int i = 0; i < Math.min(stack.length, 64); i++) {
            JsonObject frame = new JsonObject();
            frame.addProperty("class", stack[i].getClassName());
            frame.addProperty("method", stack[i].getMethodName());
            frame.addProperty("file", stack[i].getFileName());
            frame.addProperty("line", stack[i].getLineNumber());
            frames.add(frame);
        }
        row.add("callbackStack", frames);
        row.addProperty("callbackStackTruncated", stack.length > 64);
    }

    private static JsonObject diagnosticClass(Class<?> type) {
        JsonObject row = new JsonObject();
        row.addProperty("name", type.getName());
        var domain = type.getProtectionDomain();
        if (domain != null && domain.getCodeSource() != null) row.addProperty(
            "codeSource",
            domain.getCodeSource()
                .getLocation()
                .toExternalForm());
        row.addProperty("sourceAuthenticated", false);
        return row;
    }

    private JsonObject worldIdentity(World actual) {
        JsonObject row = new JsonObject();
        row.addProperty("dimension", actual.provider.dimensionId);
        row.addProperty(
            "worldClass",
            actual.getClass()
                .getName());
        row.addProperty(
            "worldName",
            actual.getWorldInfo()
                .getWorldName());
        row.addProperty("worldIdentity", System.identityHashCode(actual));
        row.addProperty("providerIdentity", System.identityHashCode(actual.provider));
        if (actual.provider.dimensionId == 0) row.add("surfaceProviderAdmission", surfaceProviderAdmission);
        row.addProperty(
            "providerClass",
            actual.provider.getClass()
                .getName());
        row.addProperty(
            "saveRoot",
            actual.getSaveHandler()
                .getWorldDirectory()
                .getAbsolutePath());
        row.addProperty("saveFolder", actual.provider.getSaveFolder());
        row.addProperty("worldSeed", Long.toString(actual.getSeed()));
        row.addProperty(
            "terrainType",
            actual.getWorldInfo()
                .getTerrainType()
                .getWorldTypeName());
        row.addProperty(
            "generatorOptions",
            actual.getWorldInfo()
                .getGeneratorOptions());
        row.addProperty(
            "mapFeaturesEnabled",
            actual.getWorldInfo()
                .isMapFeaturesEnabled());
        if (loadedObserved || actual != world) {
            row.addProperty(
                "spawnX",
                actual.getWorldInfo()
                    .getSpawnX());
            row.addProperty(
                "spawnY",
                actual.getWorldInfo()
                    .getSpawnY());
            row.addProperty(
                "spawnZ",
                actual.getWorldInfo()
                    .getSpawnZ());
        }
        return row;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void chunkLoaded(ChunkEvent.Load event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            thread();
            require(!authenticationInProgress && closingWorld == null, "Chunk observation during authentication");
            if (!credentials.containsKey(event.world) && !pendingWorlds.containsKey(event.world))
                captureRejectedChunkWorld(event);
            EarlyWorld early = resolveObservationWorld(event.world, "chunkLoad");
            require(
                event.getClass() == ChunkEvent.Load.class && event.getChunk() != null
                    && event.getChunk().worldObj == event.world,
                "Chunk Load world/event identity mismatch");
            require(history.size() < EVENT_LIMIT, "Read-only event history overflow");
            String chunkKey = key(event.getChunk());
            requireUnsealedObservation("Chunk load/reload after seal: " + chunkKey);
            early.ledger.chunk(true, chunkKey, event.getChunk());
            JsonObject row = chunkRow(event.getChunk());
            row.addProperty("chunkIdentity", System.identityHashCode(event.getChunk()));
            recordObservation(early, "chunkLoad", row, -1, event.getChunk());
            if (credentials.containsKey(event.world)) loaded.get(event.world)
                .add(chunkKey);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void diskLoaded(ChunkDataEvent.Load event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            EarlyWorld early = resolveObservationWorld(event.world, "chunkDataLoad");
            require(
                event.getClass() == ChunkDataEvent.Load.class && event.getChunk() != null
                    && event.getChunk().worldObj == event.world,
                "Disk Load world/event identity mismatch");
            NBTTagList entries = event.getData()
                .getCompoundTag("Level")
                .getTagList("Entities", 10);
            require(
                history.size() < EVENT_LIMIT && diskEntityCount + entries.tagCount() <= ENTITY_LIMIT,
                "Disk entity witness overflow");
            JsonObject row = chunkRow(event.getChunk());
            row.addProperty("chunkIdentity", System.identityHashCode(event.getChunk()));
            row.addProperty("diskEntityCount", entries.tagCount());
            JsonArray facts = new JsonArray();
            Set<String> uuids = diskWorldEntities.get(event.world);
            if (uuids == null) {
                uuids = new HashSet<>();
                diskWorldEntities.put(event.world, uuids);
            }
            for (int i = 0; i < entries.tagCount(); i++) {
                var tag = entries.getCompoundTagAt(i);
                JsonObject fact = new JsonObject();
                fact.addProperty("entry", i);
                fact.addProperty("occurrence", diskEntityCount++);
                fact.addProperty("id", tag.getString("id"));
                boolean hasUuid = tag.hasKey("UUIDMost", 4) && tag.hasKey("UUIDLeast", 4);
                fact.addProperty("uuidAvailable", hasUuid);
                if (hasUuid) {
                    String uuid = new java.util.UUID(tag.getLong("UUIDMost"), tag.getLong("UUIDLeast")).toString();
                    fact.addProperty("uuid", uuid);
                    uuids.add(uuid);
                    diskEntities.add(uuid); // Diagnostic union only, never used for provenance or capacity
                }
                facts.add(fact);
            }
            row.add("diskEntities", facts);
            early.diskObserved = true;
            recordObservation(early, "chunkDataLoad", row, -1, event.getChunk());
            requireDiskPopulation(mode, entries.tagCount());
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void joining(EntityJoinWorldEvent event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            EarlyWorld early = resolveObservationWorld(event.world, "entityJoin");
            require(
                event.getClass() == EntityJoinWorldEvent.class && event.entity != null
                    && event.entity.worldObj == event.world,
                "Entity Join world/event identity mismatch");
            require(
                history.size() < EVENT_LIMIT && entityHistory.size() < ENTITY_LIMIT
                    && pendingJoins.size() < ENTITY_LIMIT,
                "Entity witness overflow");
            JsonObject row = new JsonObject();
            row.addProperty("entityIdentity", System.identityHashCode(event.entity));
            row.addProperty("worldIdentity", System.identityHashCode(event.world));
            row.addProperty(
                "class",
                event.entity.getClass()
                    .getName());
            row.addProperty("registry", EntityList.getEntityString(event.entity));
            String uuid = null;
            if (nativeAccessorOwner(
                event.entity.getClass(),
                Entity.class,
                java.util.UUID.class,
                "getUniqueID",
                "func_110124_au",
                "aB") == Entity.class) {
                uuid = event.entity.getUniqueID()
                    .toString();
                row.addProperty("uuid", uuid);
                row.addProperty("uuidRead", "REVIEWED_NATIVE_ENTITY_GETTER");
            } else row.addProperty("uuidRead", "UNREVIEWED_OVERRIDE");
            row.addProperty("x", event.entity.posX);
            row.addProperty("y", event.entity.posY);
            row.addProperty("z", event.entity.posZ);
            row.addProperty("dimension", early.dimension);
            row.addProperty("phase", phase);
            row.addProperty("canceledObserved", event.isCanceled());
            row.addProperty("playerObserved", event.entity instanceof EntityPlayer);
            boolean diskWitness = uuid != null && diskWorldEntities.containsKey(event.world)
                && diskWorldEntities.get(event.world)
                    .contains(uuid);
            row.addProperty("diskUuidWitness", diskWitness);
            row.addProperty("diskOrigin", diskWitness ? "SAME_WORLD_WITNESSED" : "UNPROVEN");
            var domain = event.entity.getClass()
                .getProtectionDomain();
            if (domain != null && domain.getCodeSource() != null) row.addProperty(
                "codeSource",
                domain.getCodeSource()
                    .getLocation()
                    .toExternalForm());
            int entityIndex = entityHistory.size();
            boolean player = event.entity instanceof EntityPlayer;
            boolean predicateConsulted = mode.equals("RUN") && !pendingWorlds.containsKey(event.world)
                && (phase.equals("ACTIVE") || phase.equals("DRAIN"))
                && event.world == world;
            boolean known = predicateConsulted && owned.test(event.entity);
            boolean originalOwnedPlayer = player && known;
            row.addProperty("originalOwnedTrialPlayer", originalOwnedPlayer);
            row.addProperty("samePrimaryWorld", event.world == world);
            row.addProperty("ownedPredicateConsulted", predicateConsulted);
            row.addProperty("ownedPredicateResult", known);
            row.addProperty("ownershipPredicateBindingSeq", originalOwnershipPredicateSeq);
            JsonObject index = new JsonObject();
            index.addProperty("entityWitnessIndex", entityIndex);
            index.addProperty("entityIdentity", System.identityHashCode(event.entity));
            index.addProperty("playerObserved", player);
            index.addProperty("originalOwnedTrialPlayer", originalOwnedPlayer);
            index.addProperty("samePrimaryWorld", event.world == world);
            index.addProperty("ownedPredicateConsulted", predicateConsulted);
            index.addProperty("ownedPredicateResult", known);
            index.addProperty("ownershipPredicateBindingSeq", originalOwnershipPredicateSeq);
            int sequence = recordObservation(early, "entityJoin", index, entityIndex, event);
            row.addProperty("historySequence", sequence);
            row.addProperty("worldAuthentication", text(index, "worldAuthentication"));
            if (index.has("credentialLoadSeq")) row.addProperty(
                "credentialLoadSeq",
                index.get("credentialLoadSeq")
                    .getAsInt());
            entityHistory.add(row);
            requirePlayerAdmission(
                mode,
                phase,
                pendingWorlds.containsKey(event.world),
                player,
                event.world == world,
                known);
            if (!mode.equals("N")) {
                // Early/preparing entities never reach own predicates or deferred ownership
                WorldLedger.requireJoinPhase(mode, pendingWorlds.containsKey(event.world), phase);
                pendingJoins.put(event.entity, known);
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void populated(PopulateChunkEvent.Post event) {
        observe(() -> {
            if (event.world.isRemote || terminal()) return;
            EarlyWorld early = resolveObservationWorld(event.world, "populationPost");
            require(event.getClass() == PopulateChunkEvent.Post.class, "Unreviewed population event subclass");
            JsonObject row = new JsonObject();
            row.addProperty("x", event.chunkX);
            row.addProperty("z", event.chunkZ);
            recordObservation(early, "populationPost", row, -1);
            requireUnsealedObservation("Native population event after seal");
        });
    }

    int sentinel(World actual, int x, int z) {
        if (terminal()) return -1;
        int[] sequence = { -1 };
        observe(() -> {
            EarlyWorld early = resolveObservationWorld(actual, "sentinel");
            require(!mode.equals("RUN"), "Sentinel callback in normal V6 JVM");
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("z", z);
            sequence[0] = recordObservation(early, "sentinel", row, -1);
        });
        check();
        return sequence[0];
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        observe(() -> {
            if (terminal() || !aboutToStart) return;
            thread();
            validateJoins();
            if (sealed()) validateSeal();
        });
    }

    void preparing(WorldServer actual, Predicate<Entity> trialOwned) {
        meteredClosure(() -> {
            check();
            assertBootstrapClosed();
            closureAtPreparing = true;
            closureAtPreparingSeq = worldSetClosureSeq;
        });
        require(
            phase.equals("BOOTSTRAP") && creationObserved
                && loadedObserved
                && actual == world
                && DimensionManager.getWorld(0) == world,
            "HQA helper world is not certified fresh dim0");
        JsonObject binding = new JsonObject();
        binding.addProperty("mode", mode);
        binding.addProperty("worldIdentity", System.identityHashCode(world));
        binding.addProperty("predicateIdentity", System.identityHashCode(trialOwned));
        binding.addProperty("source", "ORIGINAL_PREPARING_ARGUMENT");
        originalOwnershipPredicateSeq = record("originalOwnershipPredicate", binding);
        owned = trialOwned;
        transition("PREPARING");
    }

    void prepareArea(GameTestChunkLoader loader, int minX, int minZ, int maxX, int maxZ) throws Exception {
        check();
        require(phase.equals("PREPARING"), "Preparation outside PREPARING");
        long before = System.nanoTime();
        observerCalls++;
        HodgePopulationProbe.enterPaidScope();
        try {
            require(preparationLoadingRequestSeq == -1, "Repeated fixed preparation loading request");
            PreparationLoadingContract.verifyIdentity(declaration);
            JsonObject plan = PreparationLoadingContract
                .checkedPlan(mode, minX, minZ, maxX, maxZ, PREPARATION_BORDER_CHUNKS, LOADING_FRONTIER_CHUNKS);
            require(plan.equals(declaration.get("preparationLoadingPlan")), "Declared loading plan differs");
            WorldCredential credential = credentials.get(world);
            require(credential != null && world.provider.dimensionId == 0, "Loading lacks certified primary world");
            verifyIdentity(credential.identity, true);
            JsonObject request = new JsonObject();
            PreparationLoadingContract.putIdentity(request);
            request.addProperty("mode", mode);
            request.addProperty("worldIdentity", System.identityHashCode(world));
            request.addProperty("credentialLoadSeq", credential.loadSeq);
            request.addProperty("loaderIdentity", System.identityHashCode(loader));
            request.add("plan", plan);
            preparationLoadingRequestSeq = record("preparationLoadingRequest", request);
            invokeStrictPreparationLoading(loader, plan);
            Map<String, Object> nativeChunks = currentChunkObjects(world);
            JsonArray rows = new JsonArray();
            JsonObject forced = plan.getAsJsonObject("requestedForcedChunks");
            for (int x = forced.get("minX")
                .getAsInt(); x <= forced.get("maxX")
                    .getAsInt(); x++)
                for (int z = forced.get("minZ")
                    .getAsInt(); z <= forced.get("maxZ")
                        .getAsInt(); z++) {
                            String key = "0:" + x + ":" + z;
                            Object raw = nativeChunks.get(key);
                            require(
                                raw instanceof Chunk && credential.identity.ledger.activeChunks.get(key) == raw,
                                "Requested chunk missing or not the observed native object: " + key);
                            Chunk chunk = (Chunk) raw;
                            require(
                                chunk.worldObj == world && chunk.xPosition == x && chunk.zPosition == z,
                                "Requested chunk world/coordinate differs");
                            JsonObject row = chunkRow(chunk);
                            row.addProperty("chunkIdentity", System.identityHashCode(chunk));
                            row.addProperty("worldIdentity", System.identityHashCode(world));
                            row.addProperty("credentialLoadSeq", credential.loadSeq);
                            rows.add(row);
                        }
            require(
                rows.size() == forced.get("count")
                    .getAsInt() && rows.size() <= 306,
                "Fixed loading completion count differs");
            verifyIdentity(credential.identity, true);
            JsonObject completed = new JsonObject();
            PreparationLoadingContract.putIdentity(completed);
            completed.addProperty("mode", mode);
            completed.addProperty("worldIdentity", System.identityHashCode(world));
            completed.addProperty("credentialLoadSeq", credential.loadSeq);
            completed.addProperty("requestSeq", preparationLoadingRequestSeq);
            completed.addProperty("forcedCount", rows.size());
            completed.addProperty(
                "requiredCount",
                plan.getAsJsonObject("requiredChunks")
                    .get("count")
                    .getAsInt());
            completed.add("forcedChunkRows", rows);
            preparationLoadingCompletedSeq = record("preparationLoadingCompleted", completed);
            int x1 = (minX >> 4) - 1, z1 = (minZ >> 4) - 1, x2 = (maxX >> 4) + 1, z2 = (maxZ >> 4) + 1;
            ChunkProviderServer provider = provider(world);
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                require(provider.chunkExists(x, z), "Required preparation chunk absent");
                Chunk chunk = existing(world, x, z);
                required.put(key(chunk), chunk);
            }
            record("fixedPreparationRegion", requiredRows());
        } finally {
            HodgePopulationProbe.exitPaidScope();
            observedWallNanos += System.nanoTime() - before;
        }
    }

    private void invokeStrictPreparationLoading(GameTestChunkLoader loader, JsonObject plan) throws Exception {
        JsonObject strict = plan.getAsJsonObject("strictBlockBounds");
        loader.forceChunksStrict(
            world,
            strict.get("minX")
                .getAsInt(),
            strict.get("minY")
                .getAsInt(),
            strict.get("minZ")
                .getAsInt(),
            strict.get("maxX")
                .getAsInt(),
            strict.get("maxY")
                .getAsInt(),
            strict.get("maxZ")
                .getAsInt());
    }

    boolean ready() {
        try {
            check();
            require(phase.equals("PREPARING"), "Readiness outside PREPARING");
            require(++preparationTicks <= PREPARATION_LIMIT, "Read-only preparation deadline exceeded");
            boolean flags = !required.isEmpty();
            for (Chunk c : required.values()) {
                require(
                    provider(c.worldObj).chunkExists(c.xPosition, c.zPosition)
                        && existing(c.worldObj, c.xPosition, c.zPosition) == c,
                    "Required identity lost during preparation");
                flags &= c.isTerrainPopulated && c.isLightPopulated;
            }
            JsonObject population = populationSnapshot();
            boolean idle = quiescenceEnabled ? quiescenceObservation != null && quiescenceObservation.accepted()
                : populationIdle(population);
            String snapshot = loadedSignature();
            int stableBefore = stableSamples;
            boolean stable = flags && idle && snapshot.equals(lastPreparation);
            stableSamples = stable ? stableSamples + 1 : 0;
            lastPreparation = snapshot;
            if (failure == null)
                observeReadiness(flags, population, idle, flags && idle, stable, stableBefore, stableSamples);
            if (stableSamples < 2) return false;
            meteredClosure(() -> {
                assertBootstrapClosed();
                closureAtSeal = true;
                closureAtSealSeq = worldSetClosureSeq;
            });
            originalPopulationSnapshotSeq = record("preparationPopulationSnapshot", population);
            originalPopulationSnapshot = population;
            if (quiescenceEnabled) {
                long proofStarted = System.nanoTime();
                observerCalls++;
                HodgePopulationProbe.enterPaidScope();
                try {
                    dim0QuiescenceProof = quiescenceObservation.proof();
                    dim0QuiescenceProof.addProperty("mode", mode);
                    dim0QuiescenceProof.addProperty("rawPopulationSnapshotSeq", originalPopulationSnapshotSeq);
                    dim0QuiescenceProof.addProperty("worldIdentity", System.identityHashCode(world));
                    dim0QuiescenceProof.addProperty("credentialLoadSeq", credentials.get(world).loadSeq);
                    dim0QuiescenceProof.addProperty("preparationTicks", preparationTicks);
                    dim0QuiescenceProof.addProperty("stableSamples", stableSamples);
                    dim0QuiescenceProofSeq = record("preparationQuiescenceProof", dim0QuiescenceProof);
                } finally {
                    HodgePopulationProbe.exitPaidScope();
                    observedWallNanos += System.nanoTime() - proofStarted;
                }
            }
            record("preparedRequiredChunks", requiredRows());
            if (!mode.equals("N")) {
                JsonObject boundary = EffectArrowRandomInput.emptyBoundary(world, true, false);
                record("wholeWorldAdmissionReadOnly", boundary);
                require(
                    boundary.get("allowed")
                        .getAsBoolean()
                        && !boundary.get("apiCalled")
                            .getAsBoolean(),
                    "Original whole-world empty gate refused seal; API not called");
            }
            for (World actual : worlds.keySet()) for (Object raw : provider(actual).loadedChunks.toArray()) {
                Chunk c = (Chunk) raw;
                sealedChunks.add(key(c));
                sealedChunkIdentities.put(key(c), c);
                sealedPopulation.put(key(c), c.isTerrainPopulated);
            }
            if (!mode.equals("N")) transition("SEALED");
            return true;
        } catch (RuntimeException | LinkageError problem) {
            IntegrationGuard.throwIfFatal(problem);
            try {
                reject(problem.toString());
            } catch (Throwable diagnosis) {
                IntegrationGuard.throwIfFatal(diagnosis);
                if (diagnosis != problem) problem.addSuppressed(diagnosis);
            }
            throw problem;
        }
    }

    void activate() {
        meteredClosure(this::assertBootstrapClosed);
        check();
        require(mode.equals("RUN") && phase.equals("SEALED"), "Only original V6 may activate a sealed fixture");
        validateSeal();
        transition("ACTIVE");
    }

    void drain() {
        check();
        require(phase.equals("ACTIVE"), "Drain outside active trial");
        validateJoins();
        transition("DRAIN");
    }

    void emptyAgain() {
        meteredClosure(this::assertBootstrapClosed);
        check();
        require(phase.equals("DRAIN"), "Empty boundary outside drain");
        validateJoins();
        validateSeal();
        transition("SEALED");
    }

    /** 只为原RUN保存窗口绑定既存Class和固定物理条目；不加载Mixin保留包 */
    JsonObject saveWindowSources() {
        JsonObject result = new JsonObject();
        meteredClosure(() -> {
            check();
            require(mode.equals("RUN"), "Save-window source audit outside RUN");
            Class<?> serverType = net.minecraft.server.MinecraftServer.class;
            require(
                nativeAccessorOwner(
                    net.minecraft.server.MinecraftServer.getServer()
                        .getClass(),
                    serverType,
                    int.class,
                    "getTickCounter",
                    "func_71259_af") == serverType,
                "Public server tick counter owner/descriptor changed");
            Class<?>[] types = { serverType, WorldServer.class, FMLCommonHandler.class,
                com.gtnewhorizons.horizonqa.internal.GameTestRunner.class,
                com.gtnewhorizons.horizonqa.internal.GameTestInstance.class };
            String[] jars = { MINECRAFT_SHA, MINECRAFT_SHA,
                "00d1ca02192c7efb87da95552ebf247021f1e1d642f86d0a03076314def11529",
                "f66955170c383b1ea1dd58207aaa29feeb2576159b2c10a10e483fde2e07a62d",
                "f66955170c383b1ea1dd58207aaa29feeb2576159b2c10a10e483fde2e07a62d" };
            String[] entries = { "2711cd9403d731bfad3b657dedb3c6809b9d2d4d8992505af7347ab598a1c574",
                "817a3073983bf89ffeb80380ea22af76110a28c519e77b216fd3751c861b548d",
                "0224d91d9e646e21d65caaa17ecdbeb7038ca355aae6f3777617877a47962f68",
                "c2fc975b0933c5f053e78436c8e0ae427aefd574dbe33533e7c4b9af2edb8a37",
                "0dc1d10ad8310222409a9f071d79f3b06201452566a712cb6c231ecc26c4dc0c" };
            JsonArray classes = new JsonArray();
            for (int i = 0; i < types.length; i++) {
                require(
                    types[i].getClassLoader() == WorldServer.class.getClassLoader(),
                    "Save/end source Class loader differs from authenticated native WorldServer");
                JsonObject proof = certifyWorldSource(types[i]);
                require(
                    jars[i].equals(text(proof, "sourceSha256"))
                        && entries[i].equals(text(proof, "physicalClassSha256")),
                    "Fixed save/end source changed");
                classes.add(proof);
            }
            result.add("classes", classes);
            Path hqa = checkedPath(
                PackagedClassResourceResolver.artifactOf(com.gtnewhorizons.horizonqa.internal.GameTestRunner.class));
            JsonArray mixins = new JsonArray();
            String[][] pins = {
                { "com/gtnewhorizons/horizonqa/mixin/MixinMinecraftServer.class",
                    "01496465892c8db27b922c8056bd3e75c3e5d0541338c1bd4b27c4741f961791" },
                { "com/gtnewhorizons/horizonqa/mixin/MixinFMLCommonHandler.class",
                    "f025419d79a0fb70c5848e4eaefd2b3bce22015a148d3e363c9bee0d99ad4316" } };
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(hqa.toFile())) {
                for (String[] pin : pins) {
                    var entry = jar.getEntry(pin[0]);
                    require(entry != null && !entry.isDirectory(), "Fixed HQA save/end mixin entry missing");
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (InputStream in = jar.getInputStream(entry)) {
                        byte[] buffer = new byte[8192];
                        for (int n; (n = in.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                    }
                    require(pin[1].equals(shaBytes(bytes.toByteArray())), "Fixed HQA save/end mixin entry changed");
                    JsonObject row = new JsonObject();
                    row.addProperty("entry", pin[0]);
                    row.addProperty("sha256", pin[1]);
                    row.addProperty("physicalEntryReadOnlyNotClassLoaded", true);
                    mixins.add(row);
                }
            }
            result.add("hqaMixinEntries", mixins);
            result.addProperty("tickCounter", "public MinecraftServer.getTickCounter/func_71259_af()I");
            result.addProperty("transformedDefinitionBytesVerified", false);
        });
        return result;
    }

    /** 仅已认证世界的公开只读生命周期诊断，不作为新的准入条件 */
    JsonArray saveWindowMetadata() {
        thread();
        JsonArray rows = new JsonArray();
        for (World actual : worlds.keySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", actual.provider.dimensionId);
            row.addProperty("worldIdentity", System.identityHashCode(actual));
            row.addProperty("authenticatedWorld", credentials.containsKey(actual));
            try {
                row.addProperty("shouldLoadSpawn", DimensionManager.shouldLoadSpawn(actual.provider.dimensionId));
                var tickets = net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(actual);
                Set<Object> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                unique.addAll(tickets.values());
                row.addProperty("persistentTicketCount", unique.size());
                row.addProperty("persistentTicketChunkPairs", tickets.size());
                row.addProperty(
                    "persistentChunkCount",
                    tickets.keySet()
                        .size());
                row.addProperty("loadedChunkCount", provider(actual).loadedChunks.size());
                row.addProperty("levelSaving", ((WorldServer) actual).levelSaving);
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.throwIfFatal(failure);
                row.addProperty("metadataStatus", "UNKNOWN");
                row.addProperty(
                    "metadataFailureClass",
                    failure.getClass()
                        .getName());
            }
            rows.add(row);
        }
        return rows;
    }

    void checkpoint() {
        long before = System.nanoTime();
        observerCalls++;
        HodgePopulationProbe.enterPaidScope();
        try {
            check();
            validateJoins();
            if (sealed()) validateSeal();
        } finally {
            HodgePopulationProbe.exitPaidScope();
            observedWallNanos += System.nanoTime() - before;
        }
    }

    private void validateJoins() {
        if (mode.equals("N")) {
            for (World actual : worlds.keySet())
                require(actual.playerEntities.isEmpty(), "Player in natural mechanism world");
            return;
        }
        for (Map.Entry<Entity, Boolean> entry : pendingJoins.entrySet()) require(
            entry.getValue() || owned.test(entry.getKey()),
            "Unknown joined entity; preserved without cleanup authority");
        pendingJoins.clear();
        for (World actual : worlds.keySet()) {
            for (Object raw : actual.loadedEntityList.toArray())
                require(actual == world && owned.test((Entity) raw), "Unknown loaded entity; preserved");
            for (Object raw : actual.playerEntities.toArray())
                require(actual == world && owned.test((Entity) raw), "Unknown player; preserved");
            require(actual.weatherEffects.isEmpty(), "Unknown weather entity; preserved");
        }
    }

    private void validateSeal() {
        assertWorldSetAndIdentity(false);
        require(
            world != null && DimensionManager.getWorld(0) == world && world.provider == providerCredential,
            "Sealed world/provider registration changed");
        for (World actual : worlds.keySet()) for (Object raw : provider(actual).loadedChunks.toArray()) {
            Chunk c = (Chunk) raw;
            require(
                sealedChunks.contains(key(c)) && sealedChunkIdentities.get(key(c)) == c
                    && sealedPopulation.get(key(c)) == c.isTerrainPopulated,
                "New chunk/population transition after seal: " + key(c));
        }
        for (Chunk c : required.values()) require(
            c.isTerrainPopulated && c.isLightPopulated
                && provider(c.worldObj).chunkExists(c.xPosition, c.zPosition)
                && existing(c.worldObj, c.xPosition, c.zPosition) == c,
            "Required chunk identity/readiness lost after seal");
        JsonObject stats = populationSnapshot();
        if (!quiescenceEnabled) {
            require(populationIdle(stats), "Observed native population queue/backlog/activity after seal");
            return;
        }
        quiescenceSealChecks++;
        boolean quiescent = quiescenceObservation != null && quiescenceObservation.accepted();
        lastQuiescenceSeal = quiescenceObservation == null ? null : quiescenceObservation.summary();
        if (!quiescent) quiescenceSealFailures++;
        require(quiescent, "Observed executable native population/activity or stale publication after seal");
    }

    private static boolean populationIdle(JsonObject row) {
        return row.has("available") && row.get("available")
            .getAsBoolean()
            && row.get("genQueue")
                .getAsInt() == 0
            && row.get("popQueue")
                .getAsInt() == 0
            && row.get("pipelineBacklog")
                .getAsInt() == 0
            && row.get("popCount")
                .getAsInt() == 0;
    }

    private String loadedSignature() {
        List<String> rows = new ArrayList<>();
        for (World actual : worlds.keySet())
            for (Object raw : actual == world && quiescenceLoadedChunks != null ? quiescenceLoadedChunks
                : provider(actual).loadedChunks.toArray()) {
                    Chunk c = (Chunk) raw;
                    rows.add(key(c) + ":" + c.isTerrainPopulated + ":" + c.isLightPopulated);
                }
        java.util.Collections.sort(rows);
        return rows.toString();
    }

    JsonObject populationSnapshot() {
        boolean ownScope = !HodgePopulationProbe.paidScopeActive();
        long before = ownScope ? System.nanoTime() : 0;
        if (ownScope) {
            observerCalls++;
            HodgePopulationProbe.enterPaidScope();
        }
        try {
            if (!quiescenceEnabled) return HodgePopulationProbe.legacySnapshot();
            PopulationQuiescenceContract.verifyProperties(declaration);
            quiescenceLoadedChunks = provider(world).loadedChunks.toArray();
            quiescenceObservation = HodgePopulationProbe.inspect(world, serverThread, quiescenceLoadedChunks);
            return quiescenceObservation.raw();
        } finally {
            if (ownScope) {
                HodgePopulationProbe.exitPaidScope();
                observedWallNanos += System.nanoTime() - before;
            }
        }
    }

    private JsonObject quiescenceSnapshot(JsonObject hook) {
        JsonObject row = new JsonObject();
        row.addProperty("schema", "effect-native-population-quiescence-observation-v1");
        row.add("quiescenceIdentity", PopulationQuiescenceContract.identity());
        row.addProperty("actualMode", System.getProperty("tlmnh.qaEffectEmptyWorldMode", ""));
        row.add("actualProperties", PopulationQuiescenceContract.actualProperties());
        row.add("hook", hook);
        row.addProperty("sealChecks", quiescenceSealChecks);
        row.addProperty("sealFailures", quiescenceSealFailures);
        row.add("lastSeal", lastQuiescenceSeal);
        row.add("lastObservation", quiescenceObservation == null ? null : quiescenceObservation.summary());
        row.addProperty("proofSeq", dim0QuiescenceProofSeq);
        return row;
    }

    private void verifyPriorQuiescence(JsonObject accepted, Path acceptedPath) throws Exception {
        require(
            PopulationQuiescenceContract.identity()
                .equals(accepted.get("quiescenceIdentity")),
            "Prior quiescence identity differs");
        Path directory = acceptedPath.getParent();
        Path manifest = checkedPath(directory.resolve("launch-manifest.json"));
        Path finalPath = checkedPath(directory.resolve("empty-world-final.json"));
        Path receiptPath = checkedPath(directory.resolve("quiescence-receipt.json"));
        require(
            sha(manifest).equals(text(accepted, "manifestSha256"))
                && sha(finalPath).equals(text(accepted, "finalObservationSha256"))
                && sha(receiptPath).equals(text(accepted, "quiescenceReceiptSha256")),
            "Prior quiescence actual files drift");
        JsonObject priorDeclaration = new JsonParser()
            .parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
            .getAsJsonObject();
        PopulationQuiescenceContract.verifyDeclaration(priorDeclaration, output.getParent());
        JsonObject prior = new JsonParser().parse(new String(Files.readAllBytes(finalPath), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject receipt = new JsonParser().parse(new String(Files.readAllBytes(receiptPath), StandardCharsets.UTF_8))
            .getAsJsonObject();
        require(
            priorDeclaration.equals(prior.get("declaration")) && text(accepted, "mode").equals(text(prior, "mode"))
                && text(priorDeclaration, "mode").equals(text(prior, "mode")),
            "Prior quiescence actual mode/declaration differs");
        JsonObject observation = prior.getAsJsonObject("quiescence");
        require(
            PopulationQuiescenceContract.identity()
                .equals(prior.get("quiescenceIdentity"))
                && PopulationQuiescenceContract.identity()
                    .equals(observation.get("quiescenceIdentity"))
                && PopulationQuiescenceContract.identity()
                    .equals(receipt.get("quiescenceIdentity"))
                && receipt.get("passed")
                    .getAsBoolean()
                && text(receipt, "finalObservationSha256").equals(sha(finalPath)),
            "Prior quiescence receipt identity/final differs");
        for (String field : new String[] { "driverSha256", "mainSha256", "qaSha256", "sourceBaselineReceiptSha256",
            "sourceInventorySha256" })
            require(text(receipt, field).equals(text(accepted, field)), "Prior quiescence receipt source differs");
        Path mixinPath = checkedPath(directory.resolve("mixin-observation.json"));
        JsonObject mixin = new JsonParser().parse(new String(Files.readAllBytes(mixinPath), StandardCharsets.UTF_8))
            .getAsJsonObject();
        require(
            sha(mixinPath).equals(text(accepted, "mixinObservationSha256")) && PopulationQuiescenceContract.identity()
                .equals(mixin.get("quiescenceIdentity"))
                && mixin.getAsJsonArray("populationQuiescenceApplicationMarkers")
                    .size() == 1,
            "Prior observer application evidence differs");
        JsonObject auth = prior.getAsJsonObject("worldAuthentication");
        int seq = auth.get("dim0QuiescenceProofSeq")
            .getAsInt();
        JsonObject event = prior.getAsJsonArray("events")
            .get(seq)
            .getAsJsonObject();
        JsonObject proof = auth.getAsJsonObject("dim0QuiescenceProof");
        require(
            "preparationQuiescenceProof".equals(text(event, "kind")) && event.get("details")
                .equals(proof)
                && PopulationQuiescenceContract.identity()
                    .equals(proof.get("quiescenceIdentity"))
                && HodgePopulationProbe.verifyProofGeometry(proof),
            "Prior actual topology proof absent");
        JsonObject raw = auth.getAsJsonObject("originalDim0PopulationSnapshot");
        for (String field : new String[] { "genQueue", "popQueue", "pipelineBacklog", "popCount" }) require(
            raw.get(field)
                .equals(
                    proof.getAsJsonObject("publicValues")
                        .get(field)),
            "Prior raw population/proof differs");
        require(
            receipt.equals(PopulationQuiescenceContract.receipt(prior, priorDeclaration, sha(finalPath))),
            "Prior recomputed quiescence receipt differs");
    }

    JsonArray requiredRows() {
        JsonArray rows = new JsonArray();
        required.keySet()
            .stream()
            .sorted()
            .forEach(name -> rows.add(chunkRow(required.get(name))));
        return rows;
    }

    String mode() {
        return mode;
    }

    WorldServer world() {
        check();
        return world;
    }

    void completeCanary(JsonObject result) {
        meteredClosure(this::assertBootstrapClosed);
        check();
        require(!mode.equals("RUN"), "Canary completion in normal run");
        result.add("fixture", snapshot());
        write("empty-world-canary.json", result);
        runFinished = true;
        transition("CLOSED");
        retainFinal();
    }

    void closeRun(boolean success) {
        if (!mode.equals("RUN") || terminal()) return;
        try {
            checkpoint();
            meteredClosure(this::assertBootstrapClosed);
            require(success && phase.equals("SEALED"), "Original V6 did not complete all twelve exact gates");
            JsonObject empty = EffectArrowRandomInput.emptyBoundary(world, true, false);
            record("finalWholeWorldReadOnly", empty);
            require(
                empty.get("allowed")
                    .getAsBoolean()
                    && !empty.get("apiCalled")
                        .getAsBoolean(),
                "Final original empty gate failed");
            runFinished = true;
            transition("CLOSED");
            retainFinal();
        } catch (RuntimeException problem) {
            reject(problem.toString());
        }
    }

    static void stopped() {
        if (!enabled() || instance == null) {
            HodgePopulationProbe.retainDefaultOffControl();
            return;
        }
        EffectEmptyWorldFixture f = instance;
        if (!f.runFinished || !f.phase.equals("CLOSED")) f.reject("JVM stopped before complete method result");
        f.retainFinal();
    }

    private void retainFinal() {
        if (finalWritten) return;
        write("empty-world-final.json", snapshot());
        finalWritten = true;
    }

    JsonObject snapshot() {
        JsonObject row = new JsonObject();
        JsonObject hookCost = HodgePopulationProbe.status();
        row.addProperty("schema", "effect-empty-world-observation-v1");
        row.addProperty("methodSha256", METHOD);
        row.addProperty("mode", mode);
        row.add("quiescenceIdentity", quiescenceEnabled ? PopulationQuiescenceContract.identity() : null);
        row.add("quiescence", quiescenceEnabled ? quiescenceSnapshot(hookCost) : null);
        if (inputDerivationProof != null) row.add("inputDerivation", copy(inputDerivationProof));
        if (ownedSoulBinding != null) row.add("ownedSoulProtocol", copy(ownedSoulBinding.proof));
        if (deathProductsBinding != null) row.add("deathProducts", copy(deathProductsBinding.proof));
        if (paidWearProviderSources != null) row.add("paidWearProviderSources", copy(paidWearProviderSources));
        row.addProperty("phase", phase);
        row.addProperty("failure", failure);
        row.addProperty("runFinished", runFinished);
        row.addProperty("methodClosureIsNativeStopOrSaveProof", false);
        row.addProperty("creationObserved", creationObserved);
        row.addProperty("worldLoadObserved", loadedObserved);
        row.addProperty("serverThread", serverThread == null ? null : serverThread.getName());
        row.addProperty("worldSeed", WORLD_SEED);
        row.addProperty("preparationTicks", preparationTicks);
        row.add("readinessObservation", readinessObservation());
        row.addProperty("sentinelRegistered", !mode.equals("RUN"));
        row.addProperty(
            "observerCalls",
            observerCalls + hookCost.get("unpaidHookCalls")
                .getAsLong());
        row.addProperty(
            "observerWallNanos",
            observedWallNanos + hookCost.get("unpaidHookWallNanos")
                .getAsLong());
        row.addProperty("fixtureObserverCalls", observerCalls);
        row.addProperty("fixtureObserverWallNanos", observedWallNanos);
        row.addProperty(
            "observerCostScope",
            "Actual callbacks, active checkpoints and new authentication/closure work; rejection snapshot precedes failing callback finally cost settlement");
        row.addProperty("fixtureLifetimeElapsedNanos", System.nanoTime() - started);
        row.addProperty(
            "wholeProcessCostScope",
            "Private driver records full JVM elapsed time including startup before observer registration");
        row.addProperty("transformedDefinitionBytesVerified", false);
        row.add("worldAuthentication", worldAuthenticationSnapshot());
        row.add("preparationLoading", preparationLoadingSnapshot());
        row.add("declaration", copy(declaration));
        row.add("dimensions", copy(dimensions));
        row.add(
            "worldAuthenticationRejection",
            worldAuthenticationRejection == null ? null : copy(worldAuthenticationRejection));
        row.add("events", copy(history));
        row.add("entityWitnesses", copy(entityHistory));
        row.add("requiredChunks", requiredRows());
        return row;
    }

    private void observe(Observed action) {
        long before = System.nanoTime();
        observerCalls++;
        HodgePopulationProbe.enterPaidScope();
        try {
            action.run();
        } catch (Exception | LinkageError problem) {
            IntegrationGuard.throwIfFatal(problem);
            reject(problem.toString());
        } finally {
            HodgePopulationProbe.exitPaidScope();
            observedWallNanos += System.nanoTime() - before;
        }
    }

    private void reject(String reason) {
        if (failure == null) {
            failure = reason;
            phase = "REJECTED";
            write("empty-world-rejection.json", snapshot());
            retainFinal();
        }
    }

    private void check() {
        thread();
        require(failure == null && !terminal(), "Empty-world fixture rejected/closed: " + failure);
    }

    private void thread() {
        require(serverThread != null && Thread.currentThread() == serverThread, "Foreign server thread");
    }

    private boolean sealed() {
        return phase.equals("SEALED") || phase.equals("ACTIVE") || phase.equals("DRAIN");
    }

    private boolean terminal() {
        return phase.equals("CLOSED") || phase.equals("REJECTED");
    }

    private void transition(String next) {
        JsonObject row = new JsonObject();
        row.addProperty("from", phase);
        row.addProperty("to", next);
        record("phase", row);
        phase = next;
    }

    private int record(String kind, com.google.gson.JsonElement details) {
        require(history.size() < EVENT_LIMIT, "Read-only event history overflow");
        JsonObject row = new JsonObject();
        row.addProperty("sequence", history.size());
        row.addProperty("kind", kind);
        row.addProperty("phase", phase);
        row.addProperty(
            "thread",
            Thread.currentThread()
                .getName());
        row.add("details", details);
        history.add(row);
        return history.size() - 1;
    }

    private void write(String name, JsonObject value) {
        try {
            Files.write(
                output.resolve(name),
                new GsonBuilder().serializeNulls()
                    .setPrettyPrinting()
                    .create()
                    .toJson(value)
                    .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        } catch (Exception problem) {
            throw new IllegalStateException("Cannot preserve empty-world evidence: " + name, problem);
        }
    }

    private static ChunkProviderServer provider(World actual) {
        require(actual.getChunkProvider() instanceof ChunkProviderServer, "Non-native server chunk provider");
        return (ChunkProviderServer) actual.getChunkProvider();
    }

    static Chunk existing(World actual, int x, int z) {
        for (Object raw : provider(actual).loadedChunks.toArray()) {
            Chunk chunk = (Chunk) raw;
            if (chunk.xPosition == x && chunk.zPosition == z) return chunk;
        }
        throw new IllegalStateException("Expected already-loaded chunk absent: " + x + "," + z);
    }

    private static String key(Chunk chunk) {
        return chunk.worldObj.provider.dimensionId + ":" + chunk.xPosition + ":" + chunk.zPosition;
    }

    private static JsonObject chunkRow(Chunk chunk) {
        JsonObject row = new JsonObject();
        row.addProperty("dimension", chunk.worldObj.provider.dimensionId);
        row.addProperty("x", chunk.xPosition);
        row.addProperty("z", chunk.zPosition);
        row.addProperty("terrainPopulated", chunk.isTerrainPopulated);
        row.addProperty("lightPopulated", chunk.isLightPopulated);
        return row;
    }

    private static com.google.gson.JsonElement copy(com.google.gson.JsonElement value) {
        return new JsonParser().parse(value.toString());
    }

    private static String text(JsonObject value, String name) {
        return value.get(name)
            .getAsString();
    }

    private static Path checkedPath(Path path) throws Exception {
        Path absolute = path.toAbsolutePath()
            .normalize();
        for (Path p = absolute; p != null; p = p.getParent()) {
            require(!Files.isSymbolicLink(p), "Symbolic link ancestor: " + p);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files
                    .readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!attributes.isOther(), "Reparse/other ancestor: " + p);
                require(
                    p.toRealPath()
                        .equals(
                            p.toAbsolutePath()
                                .normalize()),
                    "Resolved path differs: " + p);
            }
        }
        return absolute;
    }

    private static String sha(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536];
            for (int length; (length = stream.read(bytes)) != -1;) hash.update(bytes, 0, length);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : hash.digest()) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new IllegalStateException(message);
    }

    private static Class<?> nativeAccessorOwner(Class<?> actual, Class<?> nativeBase, Class<?> result, String... names)
        throws Exception {
        java.lang.reflect.Method selected = null;
        for (var method : nativeBase.getDeclaredMethods())
            if (method.getParameterTypes().length == 0 && method.getReturnType() == result
                && java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && Arrays.asList(names)
                    .contains(method.getName())) {
                        require(selected == null, "Ambiguous reviewed native accessor");
                        selected = method;
                    }
        require(selected != null, "Reviewed native accessor absent");
        return actual.getMethod(selected.getName())
            .getDeclaringClass();
    }

    private static void requireDiskPopulation(String mode, int count) {
        require(mode.equals("N") || count == 0, "Disk entity in exclusively fresh world");
    }

    private void requireUnsealedObservation(String reason) {
        require(!sealed(), reason);
    }

    private static void requirePlayerAdmission(String mode, String phase, boolean pending, boolean player,
        boolean samePrimaryWorld, boolean existingOwned) {
        require(
            !player || (mode.equals("RUN") && !pending
                && (phase.equals("ACTIVE") || phase.equals("DRAIN"))
                && samePrimaryWorld
                && existingOwned),
            "Unowned or early player joined independent empty-world process");
    }

    private static Class<?>[] worldSourceTypes(World actual) {
        return new Class<?>[] { World.class, WorldServer.class, WorldServerMulti.class, WorldProvider.class,
            SaveHandler.class, AnvilSaveHandler.class, net.minecraft.world.WorldProviderSurface.class,
            net.minecraft.world.WorldProviderHell.class, net.minecraft.world.WorldProviderEnd.class,
            actual.provider.getClass(), WorldEvent.class, WorldEvent.CreateSpawnPosition.class, WorldEvent.Load.class,
            WorldEvent.Unload.class, ChunkEvent.Load.class, ChunkEvent.Unload.class, ChunkDataEvent.Load.class,
            EntityJoinWorldEvent.class, PopulateChunkEvent.Post.class, cpw.mods.fml.common.eventhandler.Event.class,
            DimensionManager.class };
    }

    private JsonObject certifyWorldSource(Class<?> type) throws Exception {
        SourceCredential existing = sourceCredentials.get(type);
        if (existing != null) return existing.proof;
        Path path = checkedPath(PackagedClassResourceResolver.artifactOf(type));
        String digest = sha(path);
        String relative = requireFrozenSource(path, root, declaration.getAsJsonObject("files"), digest);
        JsonObject proof = new JsonObject();
        proof.addProperty("class", type.getName());
        proof.addProperty("codeSource", path.toString());
        proof.addProperty("relativeArtifact", relative);
        proof.addProperty("sourceSha256", digest);
        String[] nativeSpec = nativePhysicalEntry(type);
        if (nativeSpec != null) {
            require(
                MINECRAFT_SHA.equals(digest) && relative.equals("minecraft_server.1.7.10.jar"),
                "Native world class JAR changed");
            try (var jar = new java.util.zip.ZipFile(path.toFile())) {
                var entry = jar.getEntry(nativeSpec[0]);
                require(entry != null && !entry.isDirectory(), "Native physical entry absent");
                byte[] bytes;
                try (InputStream input = jar.getInputStream(entry);
                    var bytesOut = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = input.read(buffer)) != -1;) bytesOut.write(buffer, 0, n);
                    bytes = bytesOut.toByteArray();
                }
                require(nativeSpec[1].equals(shaBytes(bytes)), "Native physical class entry changed");
            }
            proof.addProperty("physicalEntry", nativeSpec[0]);
            proof.addProperty("physicalClassSha256", nativeSpec[1]);
            proof.addProperty("binding", "REVIEWED_NOTCH_MAPPED_PHYSICAL_ENTRY");
        } else {
            var binding = PackagedClassResourceResolver.resolve(type, path);
            proof.addProperty("physicalEntry", binding.physicalEntry);
            proof.addProperty("physicalClassSha256", binding.sha256);
            proof.addProperty("binding", "ACTUAL_LOGICAL_RESOURCE_TO_PHYSICAL_ENTRY");
        }
        proof.addProperty("transformedDefinitionBytesVerified", false);
        int sequence = record("worldClassSource", proof);
        proof.addProperty("sourceSeq", sequence);
        sourceCredentials.put(type, new SourceCredential(path, digest, sequence, proof));
        return proof;
    }

    private static void requireSaveRoot(Path actual, Path declared, Path ownedRoot) {
        require(
            actual.equals(declared) && actual.startsWith(ownedRoot)
                && Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS),
            "Foreign or absent native save-handler root");
    }

    private static String requireFrozenSource(Path path, Path ownedRoot, JsonObject files, String digest) {
        require(
            path.startsWith(ownedRoot) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            "Unowned world class source");
        String relative = ownedRoot.relativize(path)
            .toString()
            .replace('\\', '/');
        require(files.has(relative) && digest.equals(text(files, relative)), "Unfrozen world class source");
        return relative;
    }

    private static String[] nativePhysicalEntry(Class<?> type) {
        if (type == net.minecraft.server.MinecraftServer.class)
            return new String[] { "net/minecraft/server/MinecraftServer.class",
                "2711cd9403d731bfad3b657dedb3c6809b9d2d4d8992505af7347ab598a1c574" };
        if (type == World.class)
            return new String[] { "ahb.class", "40ff95ac0a5a4846b9f496cd7736aab5eeef131e7e2e88612e6e76349b1afc8a" };
        if (type == WorldServer.class)
            return new String[] { "mt.class", "817a3073983bf89ffeb80380ea22af76110a28c519e77b216fd3751c861b548d" };
        if (type == WorldServerMulti.class)
            return new String[] { "mm.class", "b4765a0fe3e0daf40b7e2ec188274e3a3d118031c96bc49322e00f51a8194068" };
        if (type == WorldProvider.class)
            return new String[] { "aqo.class", "176295decfddf9c738a0707313a0caa28caefec1b6d6aaecfede39b78411d717" };
        if (type == SaveHandler.class)
            return new String[] { "ayq.class", "87eda0300c29ee15fb0b9dfeaeb9c790f9a973c623972df6209b068c8c21c927" };
        if (type == AnvilSaveHandler.class)
            return new String[] { "aym.class", "da7cdcbeec60702c8aa088240567b3c6b58da07971c50dcde8cf24d9efe184bd" };
        if (type == net.minecraft.world.WorldProviderSurface.class)
            return new String[] { "aqq.class", "23ba96d75de4c107718d93d6e5ab71fe01c258fb331e21d31246201e4c510ab7" };
        if (type == net.minecraft.world.WorldProviderHell.class)
            return new String[] { "aqp.class", "0a785acd22cadfe8cb27d2fe3b015f01ccf0ef39d97ee5eb712d6d597d051c54" };
        if (type == net.minecraft.world.WorldProviderEnd.class)
            return new String[] { "aqr.class", "6337eccf48406bd6ef9d73c2a7b38782ba8b4fe114c278000b894b2f7bf0b73a" };
        return null;
    }

    private JsonObject sourceReference(Class<?> type) {
        SourceCredential source = sourceCredentials.get(type);
        JsonObject row = new JsonObject();
        row.addProperty("class", type.getName());
        row.addProperty("sourceSeq", source.sequence);
        return row;
    }

    private void recheckSources() throws Exception {
        Set<Path> checked = new HashSet<>();
        for (Map.Entry<Class<?>, SourceCredential> entry : sourceCredentials.entrySet()) {
            SourceCredential source = entry.getValue();
            require(
                checkedPath(PackagedClassResourceResolver.artifactOf(entry.getKey())).equals(source.path),
                "Cached class source moved");
            if (checked.add(source.path)) require(
                source.sha.equals(sha(checkedPath(source.path))) && source.sha.equals(
                    text(
                        declaration.getAsJsonObject("files"),
                        root.relativize(source.path)
                            .toString()
                            .replace('\\', '/'))),
                "Cached world source changed");
        }
    }

    private JsonObject identityRow(EarlyWorld early) {
        JsonObject row = new JsonObject();
        row.addProperty("dimension", early.dimension);
        row.addProperty("worldIdentity", System.identityHashCode(early.actual));
        return row;
    }

    private Map<String, Object> currentChunkObjects(World actual) {
        Map<String, Object> current = new HashMap<>();
        require(provider(actual).loadedChunks.size() <= EVENT_LIMIT, "Native loaded chunk witness overflow");
        for (Object raw : provider(actual).loadedChunks.toArray()) {
            require(raw instanceof Chunk && ((Chunk) raw).worldObj == actual, "Native loaded chunk has foreign world");
            Chunk chunk = (Chunk) raw;
            require(current.put(key(chunk), chunk) == null, "Duplicate native loaded chunk coordinate");
        }
        return current;
    }

    private static String chunkIdentitySignature(Map<String, Object> chunks) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : chunks.entrySet())
            lines.add(entry.getKey() + "=" + System.identityHashCode(entry.getValue()));
        java.util.Collections.sort(lines);
        return shaBytes(
            String.join("\n", lines)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void assertBootstrapClosed() throws Exception {
        check();
        require(
            pendingWorlds.isEmpty() && closingWorld == null && !authenticationInProgress,
            "Pending world lacks actual native Load before preparation/closure");
        require(
            creationObserved && loadedObserved
                && world != null
                && credentials.containsKey(world)
                && earlyObservationCount == closedEarlyObservationCount,
            "Unclosed early world observations");
        assertWorldSetAndIdentity(true);
        validateJoins();
    }

    private void assertWorldSetAndIdentity(boolean sources) {
        try {
            require(
                pendingWorlds.isEmpty() && closingWorld == null && !authenticationInProgress,
                "Unclosed world at identity barrier");
            WorldServer[] registered = DimensionManager.getWorlds();
            require(
                registered.length == credentials.size() && worlds.size() == credentials.size()
                    && dimensionClaims.size() == credentials.size(),
                "Registered/certified world set differs");
            Set<World> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<World, Boolean>());
            for (World actual : registered) require(
                seen.add(actual) && credentials.containsKey(actual),
                "Unobserved or duplicate registered world");
            if (sources) recheckSources();
            authenticationInProgress = true;
            try {
                for (WorldCredential credential : credentials.values()) {
                    EarlyWorld early = credential.identity;
                    verifyIdentity(early, true);
                    if (sources) {
                        verifyRootAndLock(early);
                        String folder = early.provider.getSaveFolder();
                        Path path = folder == null ? worldRoot : checkedPath(worldRoot.resolve(folder));
                        require(
                            path.toString()
                                .equals(text(credential.proof, "actualDimensionPath")),
                            "Dimension save folder changed");
                        if (!early.ledger.observedChunkKeys.isEmpty() || early.diskObserved) require(
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
                            "Observed dimension storage disappeared");
                        if (early.dimension == 0) validateSurface(early.actual);
                    }
                    early.ledger.verifyChunks(currentChunkObjects(early.actual));
                    require(
                        early.ledger.certified && early.ledger.loadSeq == credential.loadSeq,
                        "World ledger not closed once");
                    for (int i = 0; i < early.ledger.indices.size(); i++) {
                        JsonObject details = history.get(early.ledger.indices.get(i))
                            .getAsJsonObject()
                            .getAsJsonObject("details");
                        require(
                            "CLOSED_BY_LOAD".equals(text(details, "worldAuthentication"))
                                && credential.loadSeq == details.get("credentialLoadSeq")
                                    .getAsInt(),
                            "Early record closure drift");
                    }
                }
                WorldServer[] afterAccessors = DimensionManager.getWorlds();
                require(afterAccessors.length == seen.size(), "Registry set changed during world closure accessors");
                Set<World> after = java.util.Collections.newSetFromMap(new IdentityHashMap<World, Boolean>());
                for (World actual : afterAccessors) require(
                    after.add(actual) && seen.contains(actual),
                    "Registered world changed during closure accessors");
                for (WorldCredential credential : credentials.values()) verifyIdentity(credential.identity, true);
                require(failure == null, "Nested world identity observation rejected");
                if (sources) {
                    JsonObject registry = new JsonObject();
                    registry.addProperty("registeredWorldCount", afterAccessors.length);
                    registry.addProperty("pendingWorldCount", pendingWorlds.size());
                    registry.addProperty("earlyObservationCount", earlyObservationCount);
                    registry.addProperty("closedEarlyObservationCount", closedEarlyObservationCount);
                    registry.addProperty("sourceAndRootRechecked", true);
                    JsonArray claims = new JsonArray();
                    for (World actual : afterAccessors) {
                        WorldCredential credential = credentials.get(actual);
                        JsonObject claim = identityRow(credential.identity);
                        claim.addProperty("loadSeq", credential.loadSeq);
                        claim.addProperty("providerIdentity", System.identityHashCode(credential.identity.provider));
                        claim.addProperty("saveHandlerIdentity", System.identityHashCode(credential.identity.handler));
                        Map<String, Object> nativeChunks = currentChunkObjects(actual);
                        credential.identity.ledger.verifyChunks(nativeChunks);
                        claim.addProperty("activeChunkCount", nativeChunks.size());
                        claim.addProperty("activeChunkIdentitySha256", chunkIdentitySignature(nativeChunks));
                        claims.add(claim);
                    }
                    registry.add("registeredWorlds", claims);
                    for (WorldCredential credential : credentials.values()) {
                        verifyIdentity(credential.identity, true);
                        verifyRootAndLock(credential.identity);
                    }
                    require(failure == null, "Registry proof observation rejected during final native reads");
                    worldSetClosureSeq = record("worldSetClosure", registry);
                }
                worldSetExact = true;
            } finally {
                authenticationInProgress = false;
            }
        } catch (Exception problem) {
            worldSetExact = false;
            throw new IllegalStateException("World identity closure refused", problem);
        }
    }

    DeathProductsIdentity deathProductsBinding() {
        return deathProductsBinding;
    }

    OwnedSoulProtocol.Binding ownedSoulBinding() {
        return ownedSoulBinding;
    }

    JsonObject certifyPaidWearProvider(Class<?> type) throws Exception {
        JsonObject actual = certifySource(type, null);
        Path path = checkedPath(Paths.get(text(actual, "codeSource")));
        JsonObject result = new JsonObject();
        result.add("actualSource", actual);
        result.addProperty("relativeArtifact", root.relativize(path).toString().replace('\\', '/'));
        return result;
    }

    void meterObservedProtocol(Runnable action) {
        meteredClosure(action::run);
    }

    private void meteredClosure(Observed action) {
        long before = System.nanoTime();
        observerCalls++;
        HodgePopulationProbe.enterPaidScope();
        try {
            action.run();
        } catch (Exception | LinkageError problem) {
            IntegrationGuard.throwIfFatal(problem);
            reject(problem.toString());
            throw new IllegalStateException("World identity closure failed", problem);
        } finally {
            HodgePopulationProbe.exitPaidScope();
            observedWallNanos += System.nanoTime() - before;
        }
    }

    private JsonObject worldAuthenticationSnapshot() {
        JsonObject row = new JsonObject();
        row.addProperty("schema", "effect-world-auth-closure-v1");
        row.addProperty("revision", WORLD_AUTH_REVISION);
        row.addProperty("designSha256", WORLD_AUTH_DESIGN);
        row.addProperty("rootAcceptanceSha256", WORLD_AUTH_ROOT);
        row.addProperty("ownerClarificationSha256", WORLD_AUTH_OWNER_CLARIFICATION);
        row.addProperty("baseMethodSha256", METHOD);
        row.addProperty("pendingWorldCount", pendingWorlds.size());
        row.addProperty("certifiedWorldCount", credentials.size());
        row.addProperty("earlyObservationCount", earlyObservationCount);
        row.addProperty("closedEarlyObservationCount", closedEarlyObservationCount);
        row.addProperty("unclosedObservationCount", earlyObservationCount - closedEarlyObservationCount);
        row.addProperty("worldSetExact", worldSetExact);
        row.addProperty("closureAtPreparing", closureAtPreparing);
        row.addProperty("closureAtSeal", closureAtSeal);
        row.addProperty("registryClosureSeq", worldSetClosureSeq);
        row.addProperty("closureAtPreparingSeq", closureAtPreparingSeq);
        row.addProperty("closureAtSealSeq", closureAtSealSeq);
        row.addProperty("diskEntityOccurrenceCount", diskEntityCount);
        row.add(
            "originalDim0PopulationSnapshot",
            originalPopulationSnapshot == null ? null : copy(originalPopulationSnapshot));
        row.addProperty("originalDim0PopulationSnapshotSeq", originalPopulationSnapshotSeq);
        row.addProperty("dim0QuiescenceProofSeq", dim0QuiescenceProofSeq);
        row.add("dim0QuiescenceProof", dim0QuiescenceProof == null ? null : copy(dim0QuiescenceProof));
        row.addProperty("allDimensionPopulationAndAdded", false);
        row.addProperty("temporalDirectoryInodeContinuityVerified", false);
        row.addProperty("transformedDefinitionBytesVerified", false);
        JsonArray proofs = new JsonArray();
        for (WorldCredential credential : credentials.values()) {
            JsonObject proof = new JsonObject();
            proof.addProperty("worldIdentity", System.identityHashCode(credential.identity.actual));
            proof.addProperty("dimension", credential.identity.dimension);
            proof.addProperty("createSeq", credential.identity.createSeq);
            proof.addProperty("loadSeq", credential.loadSeq);
            proof.add("earlyHistoryIndices", new com.google.gson.Gson().toJsonTree(credential.identity.ledger.indices));
            proof.add(
                "earlyEntityIndices",
                new com.google.gson.Gson().toJsonTree(credential.identity.ledger.entityIndices));
            proof.add("loadProof", copy(credential.proof));
            proofs.add(proof);
        }
        row.add("worldProofs", proofs);
        JsonArray pending = new JsonArray();
        for (EarlyWorld early : pendingWorlds.values()) {
            JsonObject proof = identityRow(early);
            proof.addProperty("createSeq", early.createSeq);
            proof.addProperty("closingLoad", early.ledger.loadStarted);
            proof.add("earlyHistoryIndices", new com.google.gson.Gson().toJsonTree(early.ledger.indices));
            pending.add(proof);
        }
        row.add("pendingProofs", pending);
        return row;
    }

    private static final class SourceCredential {

        final Path path;
        final String sha;
        final int sequence;
        final JsonObject proof;

        SourceCredential(Path path, String sha, int sequence, JsonObject proof) {
            this.path = path;
            this.sha = sha;
            this.sequence = sequence;
            this.proof = proof;
        }
    }

    private static final class EarlyWorld {

        final World actual;
        final WorldProvider provider;
        final ISaveHandler handler;
        final WorldEvent.CreateSpawnPosition create;
        final int dimension;
        final byte[] lockBytes;
        final JsonObject proof;
        final WorldLedger ledger;
        int createSeq = -1;
        boolean diskObserved;

        EarlyWorld(World actual, ISaveHandler handler, WorldEvent.CreateSpawnPosition create, byte[] lockBytes,
            JsonObject proof) {
            this.actual = actual;
            this.provider = actual.provider;
            this.handler = handler;
            this.create = create;
            this.dimension = actual.provider.dimensionId;
            this.lockBytes = lockBytes;
            this.proof = proof;
            this.ledger = new WorldLedger(actual, provider, handler, dimension);
        }
    }

    private static final class WorldCredential {

        final EarlyWorld identity;
        final int loadSeq;
        final JsonObject proof;

        WorldCredential(EarlyWorld identity, int loadSeq, JsonObject proof) {
            this.identity = identity;
            this.loadSeq = loadSeq;
            this.proof = proof;
        }
    }

    /** 仅保存已计入history的索引与强对象，不调用世界、事件总线或实体行为 */
    private static final class WorldLedger {

        final Object world, provider, handler;
        final int dimension;
        final List<Integer> indices = new ArrayList<>(), entityIndices = new ArrayList<>();
        final List<Object> references = new ArrayList<>();
        final Map<String, Object> activeChunks = new HashMap<>();
        final Set<String> observedChunkKeys = new HashSet<>();
        boolean loadStarted, certified;
        int loadSeq = -1;

        WorldLedger(Object world, Object provider, Object handler, int dimension) {
            this.world = world;
            this.provider = provider;
            this.handler = handler;
            this.dimension = dimension;
        }

        static void requireJoinPhase(String mode, boolean pending, String phase) {
            require(
                mode.equals("N") || (!pending && (phase.equals("ACTIVE") || phase.equals("DRAIN"))),
                "Entity joined before active fixture; preserved");
        }

        void identity(Object world, Object provider, Object handler, int dimension) {
            require(
                this.world == world && this.provider == provider
                    && this.handler == handler
                    && this.dimension == dimension,
                "Observed world/provider/handler/dimension identity changed");
        }

        void recordIndex(int index, int entityIndex, Object reference) {
            require(
                !loadStarted && !certified && indices.size() < EVENT_LIMIT,
                "Early history outside pending credential");
            require(indices.isEmpty() || indices.get(indices.size() - 1) < index, "Early history sequence repeated");
            indices.add(index);
            entityIndices.add(entityIndex);
            references.add(reference);
        }

        void chunk(boolean load, String key, Object chunk) {
            if (load) {
                require(!activeChunks.containsKey(key), "Duplicate/replaced chunk without matching Unload");
                require(activeChunks.size() < EVENT_LIMIT, "Active chunk witness overflow");
                activeChunks.put(key, chunk);
                observedChunkKeys.add(key);
            } else {
                require(activeChunks.get(key) == chunk, "Chunk Unload lacks same-object Load");
                activeChunks.remove(key);
            }
        }

        void beginLoad() {
            require(!loadStarted && !certified, "Repeated native Load closure");
            loadStarted = true;
        }

        void verifyChunks(Map<String, Object> current) {
            require(
                current.size() == activeChunks.size(),
                "Native loaded chunks differ from observed Load/Unload history");
            for (Map.Entry<String, Object> entry : activeChunks.entrySet()) require(
                current.get(entry.getKey()) == entry.getValue(),
                "Native loaded chunk identity differs from observed history");
        }

        void commit(int sequence) {
            require(loadStarted && !certified && sequence >= 0, "World Load committed twice or without begin");
            loadSeq = sequence;
            certified = true;
        }
    }

    private interface Observed {

        void run() throws Exception;
    }
}
