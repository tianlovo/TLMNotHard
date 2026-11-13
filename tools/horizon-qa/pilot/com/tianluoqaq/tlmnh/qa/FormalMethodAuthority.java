package com.tianluoqaq.tlmnh.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 显式消费新正式版本的方法来源，原native证明仍由原消费者逐项重算 */
public final class FormalMethodAuthority {
    public static final String DECISION = "5d38fca11de46e05c3f6bc43417d0cc4f58f7e71744c564342eb8d64de1b356a";
    public static final String INPUT_CONTRACT = "5ad883dc35022e4dcc03ce978d9edb9313154ab8735b405988fd9a51db171ac2";

    private FormalMethodAuthority() {}

    public static boolean requested() {
        return Boolean.getBoolean("tlmnh.qaTacticsFormalV2");
    }

    public static void verifyMethod(JsonObject declaration) throws Exception {
        require(requested() && declaration.has("formalMethod"), "Missing formal runtime method binding");
        JsonObject binding = declaration.getAsJsonObject("formalMethod");
        require(text(binding, "schema").equals("tlmnh-tactics-runtime-method-v2")
            && text(binding, "rootDecisionSha256").equals(DECISION), "Formal runtime method identity differs");
        JsonObject contract = bound(binding.getAsJsonObject("contract"));
        require(text(contract, "schema").equals("tlmnh-tactics-prospective-contract-v2"), "Formal policy schema differs");
        JsonObject amendment = contract.getAsJsonObject("budgetAmendment");
        require(text(amendment, "sha256").equals("58d094fc7d37af014162799d51175b4e72b59808c07da2e94382c30d183347fc"),
            "Formal purpose-wide error budget differs");
        bound(amendment);
        JsonObject paidClarification = contract.getAsJsonObject("paidWearClarification");
        require(text(paidClarification, "sha256").equals("94af933c4d1c8a12ef41d7aabecd03abb953519ad245e19865cebcb212836f86"),
            "Paid-wear passive semantics or physical unit differs");
        bound(paidClarification);
        JsonObject decisionBinding = contract.getAsJsonObject("rootDecision");
        require(text(decisionBinding, "sha256").equals(DECISION), "Formal policy Root source differs");
        JsonObject decision = bound(decisionBinding);
        require(decision.get("implementation_approved").getAsBoolean(), "Formal implementation was not approved");
        JsonObject tools = bound(binding.getAsJsonObject("tools"));
        require(text(tools, "rootDecisionSha256").equals(DECISION), "Formal tool provenance differs");
        Path toolRoot = checked(Paths.get(text(binding.getAsJsonObject("tools"), "path"))).getParent();
        for (Map.Entry<String, JsonElement> file : tools.getAsJsonObject("files").entrySet()) {
            Path path = checked(toolRoot.resolve(file.getKey()));
            require(path.startsWith(toolRoot) && hash(path).equals(file.getValue().getAsString()), "Formal source/tool drift");
        }
    }

    public static Path priorSuite(JsonObject declaration, Path suite) throws Exception {
        if (!requested()) return suite;
        verifyMethod(declaration);
        if (!text(declaration, "mode").equals("RUN")) {
            require(!declaration.has("tacticsBlock") && declaration.get("tacticsLogicalSeed").isJsonNull(),
                "N/P authority received sampled tactics input");
            return suite;
        }
        JsonObject binding = declaration.getAsJsonObject("formalMethod").getAsJsonObject("authority");
        JsonObject authority = bound(binding);
        Path file = checked(Paths.get(text(binding, "path")));
        Path root = checked(Paths.get(text(authority, "suite")));
        require(file.equals(root.resolve("authority.json")) && root.getParent().equals(checked(suite).getParent())
            && text(authority, "schema").equals("tlmnh-tactics-native-method-authority-v2")
            && !authority.get("statisticalSample").getAsBoolean() && authority.get("logicalSeed").isJsonNull(),
            "Authority is outside canonical non-statistical method scope");
        Set<String> observed = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path item : (Iterable<Path>) paths::iterator) {
                Path path = checked(item);
                if (!Files.isRegularFile(path) || path.equals(file)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                JsonElement expected = authority.getAsJsonObject("files").get(relative);
                require(expected != null && hash(path).equals(expected.getAsString()), "Authority native source drift");
                observed.add(relative);
            }
        }
        require(observed.size() == authority.getAsJsonObject("files").entrySet().size(), "Authority native source missing");
        return root;
    }

    public static JsonObject proof(JsonObject declaration, Path suite) throws Exception {
        verifyMethod(declaration);
        Path prior = priorSuite(declaration, suite);
        JsonObject result = new JsonObject();
        result.addProperty("schema", "tlmnh-native-formal-method-consumption-v2");
        result.addProperty("mode", text(declaration, "mode"));
        result.addProperty("rootDecisionSha256", DECISION);
        result.add("formalMethod", declaration.get("formalMethod"));
        result.addProperty("resolvedPriorRoot", prior.toString());
        result.addProperty("ownDynamicProofsRequired", true);
        return result;
    }

    public static JsonObject artifacts() {
        try {
            Path manifest = checked(Paths.get(System.getProperty("tlmnh.qaEffectEmptyWorldManifest")));
            require(hash(manifest).equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256")),
                "Paid-wear source launch manifest drift");
            JsonObject declaration = read(manifest), result = new JsonObject();
            verifyMethod(declaration);
            for (String role : new String[] { "main", "dev", "qa" }) {
                String file = text(declaration.getAsJsonObject("artifacts"), role);
                result.addProperty(role.equals("qa") ? "pilot" : role, text(declaration.getAsJsonObject("files"), file));
            }
            return result;
        } catch (Exception problem) {
            throw new IllegalStateException("Paid-wear source binding failed", problem);
        }
    }

    public static void verifyProviders(JsonObject roles, JsonObject stackSource, JsonObject itemSource, JsonObject observerApis) {
        try {
            JsonObject declaration = read(checked(Paths.get(System.getProperty("tlmnh.qaEffectEmptyWorldManifest"))));
            JsonObject method = declaration.getAsJsonObject("formalMethod");
            if (!text(method, "purpose").equals("block")) return;
            JsonObject expected = bound(method.getAsJsonObject("paidWearProviders"));
            require(text(expected, "schema").equals("tlmnh-paid-wear-provider-baseline-v2")
                && expected.get("nativeStackSource").equals(stackSource) && expected.get("nativeItemSource").equals(itemSource) && expected.get("observerApiSources").equals(observerApis),
                "Native durability base provider source changed");
            for (Map.Entry<String, JsonElement> role : roles.entrySet()) {
                JsonObject actual = role.getValue().getAsJsonObject();
                JsonObject saved = expected.getAsJsonObject("roles").getAsJsonObject(role.getKey());
                require(actual.get("registry").equals(saved.get("registry"))
                    && actual.get("providerClass").equals(saved.get("providerClass"))
                    && actual.getAsJsonObject("initial").get("maxDamage").equals(saved.get("maxDamage"))
                    && actual.get("providerSource").equals(saved.get("providerSource")),
                    "Native item registration or paid-wear provider changed");
            }
        } catch (Exception problem) {
            throw new IllegalStateException("Native paid-wear provider freeze mismatch", problem);
        }
    }

    private static JsonObject bound(JsonObject binding) throws Exception {
        Path path = checked(Paths.get(text(binding, "path")));
        require(hash(path).equals(text(binding, "sha256")), "Formal source path/SHA mismatch");
        return read(path);
    }

    private static JsonObject read(Path path) throws IOException {
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Path checked(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        require(Files.exists(absolute) && absolute.equals(absolute.toRealPath()), "Noncanonical formal source path");
        for (Path parent = absolute; parent != null; parent = parent.getParent())
            require(!Files.isSymbolicLink(parent), "Formal source symlink refused");
        return absolute;
    }

    private static String hash(Path path) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
            result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static String text(JsonObject value, String key) {
        return value.get(key).getAsString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
