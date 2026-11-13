package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.zip.ZipFile;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.EventBus;

/** Pre-world source and actual transformed-method binding; never evaluates native XP getters. */
final class DeathProductsIdentity {

    static final String CONTRACT = "6313a0ea0223ae846dd4570cb68bf2ba30a02820d6104eba313d1ce0c07b61ea";
    static final String DECISION = "2bc2b7ce19204ae138bc5f615b04d4ec4f8b12dd0f30036161a64e5220a9f5f5";
    final Class<?> aspectOrb, tcHandler;
    final Method aspect, amount, aspectTag, early;
    final Field cache;
    final JsonObject proof;

    private DeathProductsIdentity(Class<?> orb, Class<?> handler, Method aspect, Method amount,
        Method aspectTag, Method early, Field cache, JsonObject proof) {
        this.aspectOrb = orb;
        this.tcHandler = handler;
        this.aspect = aspect;
        this.amount = amount;
        this.aspectTag = aspectTag;
        this.early = early;
        this.cache = cache;
        this.proof = proof;
    }

    static DeathProductsIdentity verify(Path root, Path output, JsonObject declaration,
        InputDerivationProtocol.Access access) throws Exception {
        boolean declared = declaration.has("deathProducts");
        require(declared == (System.getProperty(DeathProductsBootstrap.PROPERTY) != null),
            "Death-products request/declaration differs");
        if (!declared) return null;
        require(DeathProductsBootstrap.requested(), "Death-products observer was not requested");
        JsonObject binding = declaration.getAsJsonObject("deathProducts");
        require(CONTRACT.equals(text(binding, "contractSha256")) && DECISION.equals(text(binding, "decisionSha256"))
            && DeathProductsBootstrap.REVISION.equals(text(binding, "revision")), "Death-products identity differs");
        Path folder = access.checked(output.getParent().resolve("death-products-inputs"));
        Path contractFile = access.checked(Paths.get(text(binding, "contractPath")));
        Path decisionFile = access.checked(Paths.get(text(binding, "decisionPath")));
        require(contractFile.equals(folder.resolve("contract.json")) && decisionFile.equals(folder.resolve("decision.json"))
            && CONTRACT.equals(hash(Files.readAllBytes(contractFile)))
            && DECISION.equals(hash(Files.readAllBytes(decisionFile))), "Death-products documents differ");
        JsonObject contract = read(contractFile), decision = read(decisionFile);
        require(decision.get("approved").getAsBoolean()
            && decision.getAsJsonObject("explicitRecovery").get("newAttempts").getAsInt() == 1,
            "Explicit death-products approval absent");
        JsonObject artifacts = contract.getAsJsonObject("providerArtifacts"), sources = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : artifacts.entrySet()) {
            Path artifact = access.checked(root.resolve(entry.getKey()));
            require(hash(Files.readAllBytes(artifact)).equals(entry.getValue().getAsString())
                && declaration.getAsJsonObject("files").get(entry.getKey()).getAsString()
                    .equals(entry.getValue().getAsString()), "Death-products physical provider changed");
        }
        ClassLoader loader = DeathProductsIdentity.class.getClassLoader();
        Class<?> orb = Class.forName("thaumcraft.common.entities.EntityAspectOrb", false, loader);
        Class<?> handler = Class.forName("thaumcraft.common.lib.events.EventHandlerEntity", false, loader);
        Class<?> aspectType = Class.forName("thaumcraft.api.aspects.Aspect", false, loader);
        for (Class<?> type : new Class<?>[] { EntityLivingBase.class, EntityXPOrb.class, orb, handler, aspectType,
            net.minecraftforge.common.ForgeHooks.class, EventBus.class, cpw.mods.fml.common.FMLCommonHandler.class,
            DeathProductsProtocol.class }) {
            JsonObject source = copy(access.certify(type));
            Path path = access.checked(Paths.get(text(source, "codeSource")));
            require(declaration.getAsJsonObject("files").get(root.relativize(path).toString().replace('\\', '/'))
                .getAsString().equals(text(source, "sourceSha256")), "Unfrozen death-products class source");
            source.addProperty("classIdentity", System.identityHashCode(type));
            sources.add(type.getName(), source);
        }
        for (Map.Entry<String, JsonElement> entry : contract.getAsJsonObject("physicalClasses").entrySet()) {
            String jar = entry.getKey().startsWith("org.embeddedt.") ? "mods/archaicfix-0.8.2.jar"
                : "mods/Thaumcraft-1.7.10-4.2.3.5.jar";
            require(physical(access.checked(root.resolve(jar)), entry.getKey()).equals(entry.getValue().getAsString()),
                "Death-products packaged class changed: " + entry.getKey());
        }
        ModContainer tc = Loader.instance().getIndexedModList().get("Thaumcraft");
        ModContainer arch = Loader.instance().getIndexedModList().get("archaicfix");
        require(tc != null && "4.2.3.5".equals(tc.getVersion())
            && access.checked(tc.getSource().toPath()).equals(root.resolve("mods/Thaumcraft-1.7.10-4.2.3.5.jar"))
            && arch != null && "0.8.2".equals(arch.getVersion())
            && access.checked(arch.getSource().toPath()).equals(root.resolve("mods/archaicfix-0.8.2.jar")),
            "Death-products actual mod registration differs");
        String cfg = new String(Files.readAllBytes(access.checked(root.resolve("config/archaicfix.cfg"))), StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*B:dropXpImmediatelyOnDeath=true\\s*$").matcher(cfg);
        require(matcher.find() && !matcher.find(), "Actual early-XP switch differs");
        Constructor<?> nativeCtor = EntityXPOrb.class.getDeclaredConstructor(World.class, double.class,
            double.class, double.class, int.class);
        Constructor<?> aspectCtor = orb.getDeclaredConstructor(World.class, double.class, double.class,
            double.class, aspectType, int.class);
        Method callback = handler.getDeclaredMethod("livingTick", LivingDeathEvent.class);
        require(nativeCtor.getDeclaringClass() == EntityXPOrb.class && aspectCtor.getDeclaringClass() == orb
            && callback.getDeclaringClass() == handler && callback.getReturnType() == void.class
            && Modifier.isPublic(callback.getModifiers()) && !Modifier.isStatic(callback.getModifiers())
            && EntityList.stringToClassMapping.get("Thaumcraft.AspectOrb") == orb
            && EntityList.stringToClassMapping.get("XPOrb") == EntityXPOrb.class,
            "Exact death-products constructor/callback/registry differs");
        JsonObject application = DeathProductsBootstrap.snapshot();
        JsonObject update = application.getAsJsonObject(EntityLivingBase.class.getName());
        Field cache = EntityLivingBase.class.getDeclaredField(text(update, "cacheField"));
        Method early = EntityLivingBase.class.getDeclaredMethod(text(update, "earlyProducerMethod"), CallbackInfo.class);
        require(cache.getType() == int.class && !Modifier.isStatic(cache.getModifiers())
            && early.getDeclaringClass() == EntityLivingBase.class && early.getReturnType() == void.class,
            "Actual merged Archaic producer/cache differs");
        cache.setAccessible(true);
        Method aspect = orb.getDeclaredMethod("getAspect"), amount = orb.getDeclaredMethod("getAspectValue");
        Method tag = aspectType.getDeclaredMethod("getTag");
        require(aspect.getReturnType() == aspectType && amount.getReturnType() == int.class
            && tag.getReturnType() == String.class, "Actual AspectOrb read-only API differs");
        JsonObject proof = new JsonObject();
        proof.addProperty("revision", DeathProductsBootstrap.REVISION);
        proof.addProperty("contractSha256", CONTRACT);
        proof.addProperty("decisionSha256", DECISION);
        proof.addProperty("preWorld", true);
        proof.addProperty("existingComparableUnchanged", true);
        proof.addProperty("rngChanged", false);
        proof.add("sources", sources);
        proof.add("application", application);
        proof.addProperty("xpClassIdentity", System.identityHashCode(EntityXPOrb.class));
        proof.addProperty("aspectOrbClassIdentity", System.identityHashCode(orb));
        proof.addProperty("tcHandlerClassIdentity", System.identityHashCode(handler));
        proof.addProperty("mergedProducerClassIdentity", System.identityHashCode(EntityLivingBase.class));
        proof.addProperty("mergedEarlyMethod", early.getName());
        return new DeathProductsIdentity(orb, handler, aspect, amount, tag, early, cache, proof);
    }

    int cachedXp(EntityLivingBase actor) {
        try {
            return cache.getInt(actor);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Actual Archaic cache unreadable", failure);
        }
    }

    JsonObject aspectState(Object orb) {
        try {
            Object actual = aspect.invoke(orb);
            JsonObject result = new JsonObject();
            result.addProperty("aspect", actual == null ? null : (String) aspectTag.invoke(actual));
            result.addProperty("aspectIdentity", System.identityHashCode(actual));
            result.addProperty("value", (Integer) amount.invoke(orb));
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual AspectOrb state unreadable", failure);
        }
    }

    private static JsonObject read(Path path) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
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

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes))
            value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    private static String text(JsonObject value, String key) {
        return value.get(key).getAsString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
