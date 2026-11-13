package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.development.telemetry.NativeCombatLedgerReader;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.EventBus;

/** Exact native owner-death side effects. All callbacks are metered by the fixture. */
final class OwnedSoulProtocol {

    static final String REVISION = "native-owned-player-soul-v1";
    static final String CONTRACT = "48e91f6da6cd17ef7043a508c2b9589602be7a2d696e653f41f33416cde23711";
    static final String DECISION = "0cfd87d7bbfab0147edda10a9c6dee6b541ccd018f6d91213240752cca29f2c7";
    static final String PROPERTY = "tlmnh.qaOwnedSoulProtocol";
    private static final String SOUL = "lumien.randomthings.Entity.EntitySoul";
    private static final String HANDLER = "lumien.randomthings.Handler.RTEventHandler";
    private static final String DEATH = "(Lnet/minecraft/util/DamageSource;)V";
    private static final String CALLBACK = "(Lnet/minecraftforge/event/entity/living/LivingDeathEvent;)V";

    interface Host {
        EntityPlayerMP owner();

        WorldServer world();

        MinecraftServer server();

        Thread thread();

        boolean active();

        boolean owned(Entity entity);

        void adopt(Entity entity);

        void harvestOwner();

        int frame();
    }

    static final class Binding {
        final Class<?> handler, soul;
        final Field playerName, type;
        final JsonObject proof;

        Binding(Class<?> handler, Class<?> soul, Field playerName, Field type, JsonObject proof) {
            this.handler = handler;
            this.soul = soul;
            this.playerName = playerName;
            this.type = type;
            this.proof = proof;
        }
    }

    static Binding verify(Path root, Path output, JsonObject declaration, InputDerivationProtocol.Access access)
        throws Exception {
        boolean declared = declaration.has("ownedSoulProtocol");
        String request = System.getProperty(PROPERTY);
        require(declared == (request != null), "Soul protocol declaration/request differs");
        if (!declared) return null;
        require(REVISION.equals(request), "Unknown Soul protocol request");
        JsonObject binding = declaration.getAsJsonObject("ownedSoulProtocol");
        require(REVISION.equals(text(binding, "revision")) && CONTRACT.equals(text(binding, "contractSha256"))
            && DECISION.equals(text(binding, "decisionSha256")), "Soul protocol identity differs");
        Path folder = access.checked(output.getParent().resolve("owned-soul-inputs"));
        Path contractPath = access.checked(Paths.get(text(binding, "contractPath")));
        Path decisionPath = access.checked(Paths.get(text(binding, "decisionPath")));
        require(contractPath.equals(folder.resolve("contract.json"))
            && decisionPath.equals(folder.resolve("decision.json"))
            && CONTRACT.equals(hash(Files.readAllBytes(contractPath)))
            && DECISION.equals(hash(Files.readAllBytes(decisionPath))), "Soul protocol documents changed");
        JsonObject contract = read(contractPath), decision = read(decisionPath);
        require(decision.get("approved").getAsBoolean()
            && decision.getAsJsonObject("explicit_recovery_authorization").get("new_attempts").getAsInt() == 1
            && contract.getAsJsonObject("inputDerivation").get("contractSha256").getAsString()
                .equals(InputDerivationProtocol.CONTRACT), "Soul protocol approval/input relation differs");
        JsonObject provider = contract.getAsJsonObject("provider"), sources = new JsonObject();
        Path expectedJar = access.checked(root.resolve(text(provider, "jar")));
        require(hash(Files.readAllBytes(expectedJar)).equals(text(provider, "jarSha256"))
            && declaration.getAsJsonObject("files").get(text(provider, "jar")).getAsString()
                .equals(text(provider, "jarSha256")), "Soul provider physical JAR differs");
        ModContainer mod = Loader.instance().getIndexedModList().get("RandomThings");
        require(mod != null && "RandomThings".equals(mod.getModId()) && "2.7.13".equals(mod.getVersion())
            && access.checked(mod.getSource().toPath()).equals(expectedJar), "Soul provider registration differs");
        ClassLoader loader = OwnedSoulProtocol.class.getClassLoader();
        for (Map.Entry<String, JsonElement> entry : provider.getAsJsonObject("classes").entrySet()) {
            Class<?> type = Class.forName(entry.getKey(), false, loader);
            JsonObject source = copy(access.certify(type));
            require(access.checked(Paths.get(text(source, "codeSource"))).equals(expectedJar)
                && text(source, "sourceSha256").equals(text(provider, "jarSha256")),
                "Soul class source differs: " + entry.getKey());
            source.addProperty("classIdentity", System.identityHashCode(type));
            source.addProperty("entrySha256", physical(expectedJar, entry.getKey()));
            require(text(source, "entrySha256").equals(entry.getValue().getAsString()),
                "Soul packaged class differs: " + entry.getKey());
            sources.add(entry.getKey(), source);
        }
        for (Map.Entry<String, JsonElement> entry : provider.getAsJsonObject("additionalPhysicalClasses").entrySet())
            require(physical(expectedJar, entry.getKey()).equals(entry.getValue().getAsString()),
                "Soul projection source differs: " + entry.getKey());
        Class<?> handler = Class.forName(HANDLER, false, loader), soul = Class.forName(SOUL, false, loader);
        Method callback = handler.getDeclaredMethod("entityDeath", LivingDeathEvent.class);
        Constructor<?> constructor = soul.getDeclaredConstructor(World.class, double.class, double.class,
            double.class, String.class);
        require(callback.getDeclaringClass() == handler && callback.getReturnType() == void.class
            && Modifier.isPublic(callback.getModifiers()) && !Modifier.isStatic(callback.getModifiers())
            && constructor.getDeclaringClass() == soul && Modifier.isPublic(constructor.getModifiers())
            && Entity.class.isAssignableFrom(soul)
            && EntityList.stringToClassMapping.get("RandomThings.playerSoul") == soul,
            "Soul exact callback/constructor/registry differs");
        Field playerName = soul.getDeclaredField("playerName"), type = soul.getDeclaredField("type");
        require(playerName.getType() == String.class && type.getType() == int.class
            && !Modifier.isStatic(playerName.getModifiers()) && !Modifier.isStatic(type.getModifiers()),
            "Soul native fields differ");
        playerName.setAccessible(true);
        type.setAccessible(true);
        JsonObject proof = new JsonObject();
        proof.addProperty("revision", REVISION);
        proof.addProperty("contractSha256", CONTRACT);
        proof.addProperty("decisionSha256", DECISION);
        proof.addProperty("preWorld", true);
        proof.addProperty("handlerClassIdentity", System.identityHashCode(handler));
        proof.addProperty("constructorDeclaringClassIdentity", System.identityHashCode(constructor.getDeclaringClass()));
        proof.addProperty("constructor", text(provider, "constructor"));
        proof.addProperty("callback", text(provider, "callback"));
        proof.add("sources", sources);
        JsonObject nativeSources = new JsonObject();
        for (Class<?> nativeType : new Class<?>[] { ForgeHooks.class, EventBus.class, FMLCommonHandler.class,
            EntityPlayerMP.class, EntityPlayer.class, EntityLivingBase.class }) {
            JsonObject source = copy(access.certify(nativeType));
            source.addProperty("classIdentity", System.identityHashCode(nativeType));
            nativeSources.add(nativeType.getName(), source);
        }
        proof.add("nativeBoundarySources", nativeSources);
        proof.addProperty("existingComparableUnchanged", true);
        proof.addProperty("rngChanged", false);
        return new Binding(handler, soul, playerName, type, proof);
    }

    private final Binding binding;
    private final Host host;
    private final EffectArrowRandomInput.StackBridge stack = new EffectArrowRandomInput.StackBridge();
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
    private final List<Scope> pending = new ArrayList<>();
    private final IdentityHashMap<Entity, Boolean> seen = new IdentityHashMap<>();
    private final List<Entity> adopted = new ArrayList<>();
    private final JsonArray observations = new JsonArray();
    private int deaths;
    private volatile boolean foreignThreadObserved;

    OwnedSoulProtocol(Binding binding, Host host) {
        this.binding = binding;
        this.host = host;
    }

    void begin(LivingDeathEvent event) {
        if (scopes.isEmpty() && (!host.active() || event.entityLiving.worldObj != host.world())) return;
        thread();
        require(scopes.size() < 1024, "Soul death scope capacity exceeded");
        Scope scope = new Scope(event);
        scopes.push(scope);
        scope.row.addProperty("kind", "death-scope-begin");
        scope.row.addProperty("eligibleExactOwner", scope.eligible);
        scope.row.addProperty("canceledAtHighest", event.isCanceled());
        observations.add(scope.row);
    }

    void join(EntityJoinWorldEvent event) {
        if (event.entity.getClass() != binding.soul) return;
        thread();
        JsonObject raw = observation("soul-join");
        raw.add("state", state(event.entity));
        raw.addProperty("joinEventIdentity", System.identityHashCode(event));
        raw.addProperty("cancelled", event.isCanceled());
        observations.add(raw);
        require(!scopes.isEmpty(), "Soul join has no exact native death scope");
        Scope scope = scopes.peek();
        require(scope.eligible && host.active() && scope.owner == host.owner() && !scope.closed
            && event.world == host.world() && event.entity.worldObj == host.world()
            && scope.serverTick == host.server().getTickCounter()
            && scope.worldTick == host.world().getTotalWorldTime() && !event.isCanceled(),
            "Soul join is outside its exact controlled owner death");
        require(scope.candidate == null && adopted.isEmpty() && seen.put(event.entity, Boolean.TRUE) == null
            && !host.owned(event.entity) && !host.world().loadedEntityList.contains(event.entity)
            && host.world().getEntityByID(event.entity.getEntityId()) != event.entity,
            "Soul candidate is duplicate or lacks fresh exact-instance provenance");
        require(equalBits(event.entity.posX, scope.x) && equalBits(event.entity.posY, scope.y)
            && equalBits(event.entity.posZ, scope.z) && equalBits(scope.owner.posX, scope.x)
            && equalBits(scope.owner.posY, scope.y) && equalBits(scope.owner.posZ, scope.z)
            && scope.playerName.equals(playerName(event.entity)), "Soul constructor inputs differ from exact owner");
        List<EffectArrowRandomInput.Frame> captured = stack.capture();
        int callback = index(captured, binding.handler, "entityDeath", CALLBACK);
        int dispatch = indexAfter(captured, callback + 1, EventBus.class, "post",
            "(Lcpw/mods/fml/common/eventhandler/Event;)Z");
        int forge = index(captured, ForgeHooks.class, "onLivingDeath",
            "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;)Z");
        int nativeDeath = deathIndex(captured);
        require(callback >= 0 && dispatch > callback && forge > dispatch && nativeDeath > forge,
            "Soul typed native callback/death ancestry differs");
        scope.candidate = event.entity;
        scope.join = event;
        scope.uuid = event.entity.getUniqueID();
        scope.row.addProperty("alias", "owner-soul-0");
        scope.row.addProperty("candidateIdentity", System.identityHashCode(event.entity));
        scope.row.addProperty("candidateUuid", scope.uuid.toString());
        scope.row.addProperty("freshExactInstanceBeforeJoin", true);
        raw.addProperty("scope", scope.sequence);
        raw.add("typedStack", frames(captured));
        pending.add(scope);
    }

    void ledger(NativeCombatLedgerReader.Harvest harvest, JsonObject row, boolean death) {
        if (!death || scopes.isEmpty()) return;
        Scope scope = scopes.peek();
        if (!scope.eligible) return;
        scope.deathLedger = copy(row);
        scope.sourceWitness = false;
        for (NativeCombatLedgerReader.Entry entry : harvest.entries)
            if (entry.source == scope.event.source && entry.healthBefore > 0 && entry.nativeRecordedDamage > 0
                && entry.victimTick == scope.owner.ticksExisted) scope.sourceWitness = true;
    }

    void end(LivingDeathEvent event) {
        if (scopes.isEmpty()) return;
        thread();
        Scope scope = scopes.pop();
        require(scope.event == event, "Soul native death scope order changed");
        scope.closed = true;
        scope.row.addProperty("lowestObserved", true);
        scope.row.addProperty("canceledAtLowest", event.isCanceled());
        if (scope.candidate != null) {
            require(scope.deathLedger != null && scope.sourceWitness, "Soul has no complete native lethal source witness");
            scope.row.add("deathLedger", scope.deathLedger);
            scope.row.addProperty("lethalSourceExactIdentity", scope.sourceWitness);
        }
    }

    void settle() {
        thread();
        if (scopes.isEmpty() && pending.isEmpty()) return;
        require(scopes.isEmpty(), "Soul death scope did not close before post-native-update");
        stack.requireServerThread();
        List<EffectArrowRandomInput.Frame> captured = stack.capture();
        require(index(captured, FMLCommonHandler.class, "onPostServerTick", "()V") >= 0,
            "Soul settlement lacks actual FML post-server-tick boundary");
        for (EffectArrowRandomInput.Frame frame : captured) {
            require(!(frame.type == binding.handler && frame.method.equals("entityDeath"))
                && !(frame.type == ForgeHooks.class && frame.method.equals("onLivingDeath"))
                && !isNativeDeath(frame) && !isNativeDamage(frame),
                "Native owner death is still on stack at Soul settlement");
        }
        for (Scope scope : pending) {
            JsonObject raw = observation("post-native-update");
            raw.addProperty("scope", scope.sequence);
            raw.add("typedStack", frames(captured));
            raw.addProperty("finalDeathCancelled", scope.event.isCanceled());
            raw.addProperty("finalJoinCancelled", scope.join.isCanceled());
            raw.addProperty("ownerUuid", scope.owner.getUniqueID().toString());
            raw.addProperty("ownerHealthBits", Float.floatToIntBits(scope.owner.getHealth()));
            raw.addProperty("ownerAlive", scope.owner.isEntityAlive());
            raw.addProperty("ownerVictimTick", scope.owner.ticksExisted);
            raw.add("state", state(scope.candidate));
            observations.add(raw);
            require(scope.closed && scope.owner == host.owner() && host.active()
                && scope.serverTick == host.server().getTickCounter()
                && scope.worldTick == host.world().getTotalWorldTime()
                && !scope.event.isCanceled() && !scope.join.isCanceled()
                && scope.owner.worldObj == host.world() && scope.owner.getHealth() <= 0
                && !scope.owner.isEntityAlive(), "Soul death did not finish uncanceled on its exact native boundary");
            require(complete(scope.deathLedger) && scope.sourceWitness, "Soul original death ledger is incomplete");
            finalLedger = null;
            settling = true;
            try {
                host.harvestOwner();
            } finally {
                settling = false;
            }
            raw.add("postNativeLedger", finalLedger);
            require(finalLedger != null && complete(finalLedger), "Soul post-native-update ledger is incomplete");
            require(scope.candidate.getClass() == binding.soul && scope.candidate.worldObj == host.world()
                && scope.uuid.equals(scope.candidate.getUniqueID()) && !host.owned(scope.candidate)
                && host.world().loadedEntityList.contains(scope.candidate)
                && host.world().getEntityByID(scope.candidate.getEntityId()) == scope.candidate
                && scope.playerName.equals(playerName(scope.candidate)), "Soul exact joined instance did not close");
            host.adopt(scope.candidate);
            adopted.add(scope.candidate);
            raw.addProperty("exactInstanceAdopted", true);
            raw.addProperty("alias", "owner-soul-0");
        }
        pending.clear();
    }

    private boolean settling;
    private JsonObject finalLedger;

    boolean awaitingPostLedger() {
        return settling;
    }

    void postLedger(JsonObject row) {
        if (settling) finalLedger = copy(row);
    }

    void observe() {
        thread();
        for (Entity entity : adopted) {
            JsonObject raw = observation("native-soul-state");
            raw.addProperty("alias", "owner-soul-0");
            raw.addProperty("loaded", host.world().loadedEntityList.contains(entity));
            raw.add("state", state(entity));
            observations.add(raw);
        }
    }

    JsonObject snapshot() {
        JsonObject result = copy(binding.proof);
        result.add("observations", new JsonParser().parse(observations.toString()));
        result.addProperty("pendingCandidates", pending.size());
        result.addProperty("openDeathScopes", scopes.size());
        result.addProperty("adoptedInstances", adopted.size());
        result.addProperty("settlementBoundary", "same-thread ServerTick.END/HIGHEST post-native-update");
        result.addProperty("returnProbeUsed", false);
        result.addProperty("foreignThreadObserved", foreignThreadObserved);
        result.addProperty("existingComparableUnchanged", true);
        result.addProperty("typeProjection", "raw random native texture variant retained, existing replay projection unchanged");
        return result;
    }

    private final class Scope {
        final LivingDeathEvent event;
        final EntityPlayerMP owner = host.owner();
        final int serverTick = host.server().getTickCounter(), sequence = deaths++;
        final long worldTick = host.world().getTotalWorldTime();
        final double x = owner.posX, y = owner.posY, z = owner.posZ;
        final String playerName = owner.getGameProfile().getName();
        final JsonObject row = observation("death-scope");
        final boolean eligible;
        boolean closed, sourceWitness;
        Entity candidate;
        EntityJoinWorldEvent join;
        UUID uuid;
        JsonObject deathLedger;

        Scope(LivingDeathEvent event) {
            this.event = event;
            eligible = host.active() && event.entityLiving == owner && owner.getClass() == EntityPlayerMP.class
                && !(owner instanceof FakePlayer) && owner.worldObj == host.world() && host.owned(owner);
            row.addProperty("scope", sequence);
            row.addProperty("eventIdentity", System.identityHashCode(event));
            row.addProperty("victimIdentity", System.identityHashCode(event.entityLiving));
            row.addProperty("sourceIdentity", System.identityHashCode(event.source));
            row.addProperty("sourceType", event.source.getDamageType());
            if (event.source.getEntity() != null)
                row.addProperty("attackerUuid", event.source.getEntity().getUniqueID().toString());
            if (event.source.getSourceOfDamage() != null)
                row.addProperty("directUuid", event.source.getSourceOfDamage().getUniqueID().toString());
            row.addProperty("ownerUuid", owner.getUniqueID().toString());
        }
    }

    private JsonObject observation(String kind) {
        require(observations.size() < 24000, "Soul observation capacity exceeded");
        JsonObject row = new JsonObject();
        row.addProperty("kind", kind);
        row.addProperty("frame", host.frame());
        row.addProperty("serverTick", host.server().getTickCounter());
        row.addProperty("worldTick", host.world().getTotalWorldTime());
        return row;
    }

    private JsonObject state(Entity entity) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", entity.getUniqueID().toString());
            row.addProperty("entityId", entity.getEntityId());
            row.addProperty("entityIdentity", System.identityHashCode(entity));
            row.addProperty("worldIdentity", System.identityHashCode(entity.worldObj));
            row.addProperty("classIdentity", System.identityHashCode(entity.getClass()));
            row.addProperty("class", entity.getClass().getName());
            row.addProperty("registry", EntityList.getEntityString(entity));
            row.addProperty("playerName", playerName(entity));
            row.addProperty("type", binding.type.getInt(entity));
            row.addProperty("xBits", Long.toHexString(Double.doubleToLongBits(entity.posX)));
            row.addProperty("yBits", Long.toHexString(Double.doubleToLongBits(entity.posY)));
            row.addProperty("zBits", Long.toHexString(Double.doubleToLongBits(entity.posZ)));
            row.addProperty("ticksExisted", entity.ticksExisted);
            row.addProperty("dead", entity.isDead);
            NBTTagCompound nbt = new NBTTagCompound();
            entity.writeToNBT(nbt);
            row.add("nbt", SleepAttributePortSnapshot.binaryNbt(nbt));
            row.addProperty("nbtText", nbt.toString());
            return row;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Soul native state unreadable", failure);
        }
    }

    private String playerName(Entity entity) {
        try {
            return (String) binding.playerName.get(entity);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Soul native owner name unreadable", failure);
        }
    }

    private void thread() {
        if (Thread.currentThread() != host.thread()) {
            foreignThreadObserved = true;
            throw new IllegalStateException("Soul observation moved off its actual server thread");
        }
        require(!foreignThreadObserved, "Foreign-thread Soul observation was retained");
    }

    private static int index(List<EffectArrowRandomInput.Frame> frames, Class<?> type, String method, String descriptor) {
        return indexAfter(frames, 0, type, method, descriptor);
    }

    private static int indexAfter(List<EffectArrowRandomInput.Frame> frames, int from, Class<?> type, String method,
        String descriptor) {
        for (int i = from; i < frames.size(); i++) {
            EffectArrowRandomInput.Frame frame = frames.get(i);
            if (frame.type == type && frame.method.equals(method) && frame.descriptor.equals(descriptor)) return i;
        }
        return -1;
    }

    private static int deathIndex(List<EffectArrowRandomInput.Frame> frames) {
        for (int i = 0; i < frames.size(); i++) if (frames.get(i).type == EntityPlayerMP.class
            && isNativeDeath(frames.get(i))) return i;
        return -1;
    }

    private static boolean isNativeDeath(EffectArrowRandomInput.Frame frame) {
        return (frame.type == EntityPlayerMP.class || frame.type == EntityPlayer.class
            || frame.type == EntityLivingBase.class) && frame.descriptor.equals(DEATH)
            && (frame.method.equals("onDeath") || frame.method.equals("func_70645_a"));
    }

    private static boolean isNativeDamage(EffectArrowRandomInput.Frame frame) {
        boolean nativeClass = frame.type == EntityPlayerMP.class || frame.type == EntityPlayer.class
            || frame.type == EntityLivingBase.class;
        return nativeClass && ((frame.descriptor.equals("(Lnet/minecraft/util/DamageSource;F)Z")
            && (frame.method.equals("attackEntityFrom") || frame.method.equals("func_70097_a")))
            || (frame.descriptor.equals("(Lnet/minecraft/util/DamageSource;F)V")
                && (frame.method.equals("damageEntity") || frame.method.equals("func_70665_d"))));
    }

    private static JsonArray frames(List<EffectArrowRandomInput.Frame> frames) {
        JsonArray result = new JsonArray();
        for (EffectArrowRandomInput.Frame frame : frames) {
            JsonObject row = new JsonObject();
            row.addProperty("class", frame.type.getName());
            row.addProperty("classIdentity", System.identityHashCode(frame.type));
            row.addProperty("method", frame.method);
            row.addProperty("descriptor", frame.descriptor);
            result.add(row);
        }
        return result;
    }

    private static boolean complete(JsonObject row) {
        return row != null && row.get("complete").getAsBoolean()
            && !row.get("tailIncomplete").getAsBoolean()
            && (!row.get("resetObserved").getAsBoolean() || row.get("resetWasExpected").getAsBoolean());
    }

    private static boolean equalBits(double left, double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private static String physical(Path path, String name) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile());
            InputStream input = zip.getInputStream(zip.getEntry(name.replace('.', '/') + ".class"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
            return hash(bytes.toByteArray());
        }
    }

    private static JsonObject read(Path path) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    private static String text(JsonObject value, String key) {
        return value.get(key).getAsString();
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes))
            value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
