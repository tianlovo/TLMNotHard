package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Reads the approved native cache relation before world creation. */
final class InputDerivationProtocol {

    static final String REVISION = "pre-world-native-cache-and-default-inputs-v1";
    static final String CONTRACT = "dc16165527616ea7980128a38d62b86f1c6460bd2407a746c01958e140f4fd89";
    static final String DECISION = "ec891745b1a3bd6ca7efba5c9d83c50a17cb6ca347454d7d9865e58b6a0c1716";
    static final String PROPERTY = "tlmnh.qaInputDerivation";
    static final String PHYSICAL_REVISION = "same-default-provider-new-production-artifact-v1";
    static final String PHYSICAL_CONTRACT = "ab426ab0ef978d401b41fcdeec552510e6bbaf4a452f765cde8a05280e98f1bf";
    static final String PHYSICAL_DECISION = "752ea8e2393e6d891fb8c0a3cb4551123cfd2218ded85bb5551bc924d9866822";

    interface Access {

        JsonObject certify(Class<?> type) throws Exception;

        Path checked(Path path) throws Exception;

        void observed(JsonObject value);
    }

    private InputDerivationProtocol() {}

    static JsonObject verify(Path root, Path output, JsonObject declaration, Access access) throws Exception {
        boolean declared = declaration.has("inputDerivation");
        String requested = System.getProperty(PROPERTY);
        require(declared == (requested != null), "Input derivation declaration/request differs");
        if (!declared) return null;
        require(REVISION.equals(requested), "Unknown input derivation request");
        JsonObject contract = documents(declaration, output.getParent(), access);
        JsonObject binding = declaration.getAsJsonObject("inputDerivation");
        JsonObject ae = contract.getAsJsonObject("ae"), tlm = contract.getAsJsonObject("tlm");
        String physicalMain = physicalMainIdentity(declaration, contract, access);
        byte[] before = Base64.getDecoder()
            .decode(text(binding, "aeBeforeBase64"));
        byte[] tlmBefore = Base64.getDecoder()
            .decode(text(binding, "tlmBeforeBase64"));
        require(
            hash(before).equals(text(ae, "beforeSha256")) && hash(tlmBefore).equals(text(tlm, "beforeSha256")),
            "Original derivation bytes changed");
        JsonObject files = declaration.getAsJsonObject("files");
        require(
            text(files, text(ae, "path")).equals(text(ae, "beforeSha256"))
                && text(files, text(tlm, "path")).equals(text(tlm, "afterSha256"))
                && text(binding, "tlmAfterSha256").equals(text(tlm, "afterSha256")),
            "Prelaunch derivation input identity differs");

        JsonObject sources = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> item : ae.getAsJsonObject("classes")
            .entrySet()) {
            JsonObject source = source(item.getKey(), root, declaration, access);
            require(
                root.resolve(text(ae, "providerJar"))
                    .equals(Paths.get(text(source, "codeSource")))
                    && text(source, "sourceSha256").equals(text(ae, "providerJarSha256"))
                    && text(source, "entrySha256").equals(
                        item.getValue()
                            .getAsString()),
                "AE physical provider differs: " + item.getKey());
            sources.add(item.getKey(), source);
        }
        String tlmClass = text(tlm, "providerClass");
        JsonObject tlmSource = source(tlmClass, root, declaration, access);
        require(
            root.resolve(text(declaration.getAsJsonObject("artifacts"), "main"))
                .equals(Paths.get(text(tlmSource, "codeSource")))
                && text(tlmSource, "sourceSha256").equals(physicalMain)
                && text(tlmSource, "entrySha256").equals(text(tlm, "providerClassSha256")),
            "TLM default provider differs");
        sources.add(tlmClass, tlmSource);
        sources.add(Loader.class.getName(), source(Loader.class.getName(), root, declaration, access));
        JsonObject helper = source(InputDerivationProtocol.class.getName(), root, declaration, access);
        require(
            root.resolve(text(declaration.getAsJsonObject("artifacts"), "qa"))
                .equals(Paths.get(text(helper, "codeSource"))),
            "Input derivation QA source differs");
        sources.add(InputDerivationProtocol.class.getName(), helper);

        require(exporterThreads() == 0, "AE CSV exporter is still running");
        List<ModContainer> active = Loader.instance()
            .getActiveModList();
        Object[] identities = active.toArray();
        JsonArray rows = rows(active, identities);
        String expectedDigest = modDigest(rows);
        Path aeFile = access.checked(root.resolve(text(ae, "path")));
        BasicFileAttributes first = Files.readAttributes(aeFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        byte[] actual = Files.readAllBytes(aeFile);
        JsonObject observation = new JsonObject();
        observation.add("activeMods", rows);
        observation.addProperty("expectedDigest", expectedDigest);
        observation.addProperty("actualAeSha256", hash(actual));
        access.observed(observation);
        BasicFileAttributes second = Files.readAttributes(aeFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(
            first.isRegularFile() && second.isRegularFile()
                && first.size() == second.size()
                && first.lastModifiedTime()
                    .equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey()),
            "AE cache changed while reading");
        require(
            Arrays.equals(expected(before, expectedDigest), actual),
            "AE cache differs from its exact native relation");
        require(
            Loader.instance()
                .getActiveModList() == active && rows.equals(rows(active, identities)),
            "Active mod list changed while reading");
        require(
            exporterThreads() == 0 && Arrays.equals(actual, Files.readAllBytes(aeFile)),
            "AE exporter/read race at authentication");
        require(
            hash(Files.readAllBytes(access.checked(root.resolve(text(tlm, "path"))))).equals(text(tlm, "afterSha256")),
            "TLM derived input changed");

        JsonObject proof = new JsonObject();
        proof.addProperty("schema", "tlmnh-native-input-derivation-proof-v1");
        proof.addProperty("revision", REVISION);
        proof.addProperty("contractSha256", contractIdentity());
        proof.addProperty("decisionSha256", decisionIdentity());
        proof.addProperty("mode", text(declaration, "mode"));
        proof.add("activeMods", rows);
        proof.addProperty("aeDigest", expectedDigest);
        proof.addProperty("aeBeforeSha256", hash(before));
        proof.addProperty("aeAfterSha256", hash(actual));
        proof.addProperty(
            "aeAfterBase64",
            Base64.getEncoder()
                .encodeToString(actual));
        proof.addProperty("tlmAfterSha256", text(tlm, "afterSha256"));
        proof.addProperty("exporterThreadsBefore", 0);
        proof.addProperty("exporterThreadsAfter", 0);
        proof.addProperty("activeListStable", true);
        proof.addProperty("fileReadStable", true);
        proof.addProperty("preWorld", true);
        proof.addProperty("qaWroteCache", false);
        proof.add("sources", sources);
        if (declaration.has("inputDerivationPhysicalBinding"))
            proof.add("physicalProviderBinding", declaration.get("inputDerivationPhysicalBinding"));
        verifyPriors(declaration, output.getParent(), contract, access);
        return proof;
    }

    private static String contractIdentity() {
        return FormalMethodAuthority.requested() ? FormalMethodAuthority.INPUT_CONTRACT : CONTRACT;
    }

    private static String decisionIdentity() {
        return FormalMethodAuthority.requested() ? FormalMethodAuthority.DECISION : DECISION;
    }

    private static JsonObject documents(JsonObject declaration, Path suite, Access access) throws Exception {
        if (FormalMethodAuthority.requested()) FormalMethodAuthority.verifyMethod(declaration);
        JsonObject binding = declaration.getAsJsonObject("inputDerivation");
        require(
            REVISION.equals(text(binding, "revision")) && contractIdentity().equals(text(binding, "contractSha256"))
                && decisionIdentity().equals(text(binding, "decisionSha256")),
            "Input derivation binding differs");
        Path folder = access.checked(suite.resolve("input-derivation-inputs"));
        Path contractFile = access.checked(Paths.get(text(binding, "contractPath")));
        Path decisionFile = access.checked(Paths.get(text(binding, "decisionPath")));
        require(
            contractFile.equals(folder.resolve("contract.json")) && decisionFile.equals(folder.resolve("decision.json"))
                && contractIdentity().equals(hash(Files.readAllBytes(contractFile)))
                && decisionIdentity().equals(hash(Files.readAllBytes(decisionFile))),
            "Input derivation documents changed");
        JsonObject contract = read(contractFile);
        require(
            text(contract, "originalBaselineSha256").equals(text(declaration, "sourceBaselineReceiptSha256"))
                && text(contract, "tlmTransformSha256")
                    .equals(hash(Files.readAllBytes(access.checked(folder.resolve("tlm-transform.json"))))),
            "Original baseline268/derived rule changed");
        return contract;
    }

    private static String physicalMainIdentity(JsonObject declaration, JsonObject legacy, Access access)
        throws Exception {
        if (!declaration.has("inputDerivationPhysicalBinding"))
            return text(legacy.getAsJsonObject("tlm"), "providerMainSha256");
        JsonObject binding = declaration.getAsJsonObject("inputDerivationPhysicalBinding");
        require(
            PHYSICAL_REVISION.equals(text(binding, "revision"))
                && PHYSICAL_CONTRACT.equals(text(binding, "contractSha256"))
                && PHYSICAL_DECISION.equals(text(binding, "decisionSha256")),
            "Physical provider binding identity differs");
        Path suite = access.checked(
            Paths.get(text(declaration.getAsJsonObject("inputDerivation"), "contractPath"))
                .getParent()
                .getParent());
        Path folder = access.checked(suite.resolve("input-derivation-physical-inputs"));
        Path contractFile = access.checked(Paths.get(text(binding, "contractPath")));
        Path decisionFile = access.checked(Paths.get(text(binding, "decisionPath")));
        require(
            contractFile.equals(folder.resolve("contract.json")) && decisionFile.equals(folder.resolve("decision.json"))
                && PHYSICAL_CONTRACT.equals(hash(Files.readAllBytes(contractFile)))
                && PHYSICAL_DECISION.equals(hash(Files.readAllBytes(decisionFile))),
            "Physical provider documents changed");
        JsonObject contract = read(contractFile), decision = read(decisionFile);
        require(
            PHYSICAL_REVISION.equals(text(contract, "revision"))
                && CONTRACT.equals(text(contract, "baselineInputContractSha256"))
                && legacy.getAsJsonObject("ae")
                    .equals(contract.getAsJsonObject("ae"))
                && legacy.getAsJsonObject("tlm")
                    .equals(contract.getAsJsonObject("baselineTlm"))
                && !contract.get("inputDerivationBytesAlgorithmsAndDefaultsChanged")
                    .getAsBoolean()
                && !contract.get("actualProviderClassChanged")
                    .getAsBoolean()
                && contract.get("physicalArtifactBindingOnly")
                    .getAsBoolean()
                && decision.get("approved")
                    .getAsBoolean()
                && PHYSICAL_CONTRACT.equals(text(decision, "contractSha256"))
                && !decision.get("normalizationScopeExpanded")
                    .getAsBoolean()
                && !decision.get("formalStatisticsAuthorized")
                    .getAsBoolean(),
            "Physical provider relation/authority differs");
        String main = text(contract, "providerMainSha256");
        require(
            main.equals(
                text(declaration.getAsJsonObject("files"), text(declaration.getAsJsonObject("artifacts"), "main"))),
            "Physical provider main artifact differs");
        return main;
    }

    private static JsonObject source(String name, Path root, JsonObject declaration, Access access) throws Exception {
        Class<?> type = Class.forName(name, false, InputDerivationProtocol.class.getClassLoader());
        JsonObject proof = new JsonParser().parse(
            access.certify(type)
                .toString())
            .getAsJsonObject();
        Path artifact = access.checked(Paths.get(text(proof, "codeSource")));
        require(artifact.startsWith(root), "Unowned input derivation class source");
        String relative = root.relativize(artifact)
            .toString()
            .replace('\\', '/');
        require(
            text(declaration.getAsJsonObject("files"), relative).equals(text(proof, "sourceSha256")),
            "Unfrozen input derivation class source");
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            java.util.zip.ZipEntry entry = jar.getEntry(name.replace('.', '/') + ".class");
            require(entry != null, "Physical input derivation class missing");
            try (InputStream stream = jar.getInputStream(entry);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = stream.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                proof.addProperty("entrySha256", hash(bytes.toByteArray()));
            }
        }
        return proof;
    }

    private static JsonArray rows(List<ModContainer> active, Object[] identities) {
        require(active.size() == identities.length, "Active mod list size changed");
        JsonArray result = new JsonArray();
        for (int i = 0; i < identities.length; i++) {
            require(
                active.get(i) == identities[i] && identities[i] instanceof ModContainer,
                "Active mod list identity changed");
            ModContainer mod = (ModContainer) identities[i];
            String id = mod.getModId(), version = mod.getVersion();
            JsonObject row = new JsonObject();
            row.addProperty("modId", id);
            row.addProperty("version", version);
            result.add(row);
        }
        return result;
    }

    private static String modDigest(JsonArray rows) throws Exception {
        StringBuilder concatenation = new StringBuilder();
        for (JsonElement item : rows) {
            JsonObject row = item.getAsJsonObject();
            require(
                row.entrySet()
                    .size() == 2,
                "Unexpected active mod proof fields");
            String id = nativeString(row, "modId"), version = nativeString(row, "version");
            concatenation.append(id)
                .append(version);
        }
        return hex(
            MessageDigest.getInstance("MD5")
                .digest(
                    concatenation.toString()
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static String nativeString(JsonObject row, String key) {
        JsonElement value = row.get(key);
        require(
            value != null && (value.isJsonNull() || value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isString()),
            "Malformed native mod string");
        return value.isJsonNull() ? null : value.getAsString();
    }

    private static byte[] expected(byte[] before, String digest) {
        String text = new String(before, StandardCharsets.UTF_8);
        require(Arrays.equals(before, text.getBytes(StandardCharsets.UTF_8)), "AE input is not exact UTF8");
        Matcher matcher = Pattern.compile("(?m)^    S:digest=([0-9a-f]{32})\\r?$")
            .matcher(text);
        require(matcher.find(), "AE digest field missing or malformed");
        int start = matcher.start(1), end = matcher.end(1);
        require(!matcher.find(), "Duplicate AE digest field");
        return (text.substring(0, start) + digest + text.substring(end)).getBytes(StandardCharsets.UTF_8);
    }

    private static int exporterThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces()
            .keySet())
            if (thread.isAlive() && thread.getName()
                .equals("AE2 CSV Export")) count++;
        return count;
    }

    private static void verifyPriors(JsonObject declaration, Path suite, JsonObject contract, Access access)
        throws Exception {
        Path priorSuite = FormalMethodAuthority.priorSuite(declaration, suite);
        for (JsonElement item : declaration.getAsJsonArray("priorCanaries")) {
            JsonObject prior = item.getAsJsonObject();
            Path folder = access.checked(priorSuite.resolve(text(prior, "mode")));
            JsonObject accepted = read(access.checked(folder.resolve("accepted.json")));
            Path manifest = access.checked(folder.resolve("launch-manifest.json"));
            JsonObject priorDeclaration = read(manifest);
            documents(priorDeclaration, priorSuite, access);
            Path finalFile = access.checked(folder.resolve("empty-world-final.json"));
            Path receiptFile = access.checked(folder.resolve("input-derivation-receipt.json"));
            JsonObject receipt = read(receiptFile), observation = read(finalFile);
            require(
                text(receipt, "schema").equals("tlmnh-native-input-derivation-receipt-v1") && accepted.get("passed")
                    .getAsBoolean()
                    && text(accepted, "mode").equals(text(prior, "mode"))
                    && text(priorDeclaration, "mode").equals(text(prior, "mode"))
                    && text(accepted, "inputDerivationReceiptSha256").equals(hash(Files.readAllBytes(receiptFile)))
                    && text(receipt, "finalObservationSha256").equals(hash(Files.readAllBytes(finalFile)))
                    && text(accepted, "finalObservationSha256").equals(hash(Files.readAllBytes(finalFile)))
                    && text(accepted, "manifestSha256").equals(hash(Files.readAllBytes(manifest)))
                    && text(prior, "manifestSha256").equals(hash(Files.readAllBytes(manifest)))
                    && text(receipt, "contractSha256").equals(contractIdentity())
                    && text(receipt, "decisionSha256").equals(decisionIdentity())
                    && receipt.get("passed")
                        .getAsBoolean()
                    && receipt.get("proof")
                        .equals(observation.get("inputDerivation")),
                "Prior input derivation receipt differs");
            for (String key : new String[] { "driverSha256", "sourceBaselineReceiptSha256", "sourceInventorySha256" })
                require(
                    text(priorDeclaration, key).equals(text(declaration, key)),
                    "Prior input derivation source identity differs");
            for (String role : new String[] { "main", "qa", "dev" }) require(
                text(
                    priorDeclaration.getAsJsonObject("files"),
                    text(priorDeclaration.getAsJsonObject("artifacts"), role)).equals(
                        text(
                            declaration.getAsJsonObject("files"),
                            text(declaration.getAsJsonObject("artifacts"), role))),
                "Prior input derivation candidate identity differs");
            JsonObject proof = observation.getAsJsonObject("inputDerivation");
            require(
                text(proof, "revision").equals(REVISION) && text(proof, "contractSha256").equals(contractIdentity())
                    && text(proof, "decisionSha256").equals(decisionIdentity())
                    && text(proof, "mode").equals(text(prior, "mode")),
                "Prior input derivation proof identity differs");
            require(
                proof.get("exporterThreadsBefore")
                    .getAsInt() == 0
                    && proof.get("exporterThreadsAfter")
                        .getAsInt() == 0
                    && proof.get("activeListStable")
                        .getAsBoolean()
                    && proof.get("fileReadStable")
                        .getAsBoolean()
                    && proof.get("preWorld")
                        .getAsBoolean()
                    && !proof.get("qaWroteCache")
                        .getAsBoolean(),
                "Prior native derivation stability closure differs");
            byte[] before = Base64.getDecoder()
                .decode(text(priorDeclaration.getAsJsonObject("inputDerivation"), "aeBeforeBase64"));
            String digest = modDigest(proof.getAsJsonArray("activeMods"));
            byte[] after = expected(before, digest);
            require(
                hash(before).equals(text(contract.getAsJsonObject("ae"), "beforeSha256"))
                    && text(proof, "aeBeforeSha256").equals(hash(before))
                    && text(proof, "aeDigest").equals(digest)
                    && text(proof, "aeAfterSha256").equals(hash(after))
                    && Arrays.equals(
                        after,
                        Base64.getDecoder()
                            .decode(text(proof, "aeAfterBase64")))
                    && text(proof, "tlmAfterSha256").equals(text(contract.getAsJsonObject("tlm"), "afterSha256")),
                "Prior input derivation relation differs");
            verifyRecordedSources(proof, priorDeclaration, contract, access);
            int events = 0;
            for (JsonElement event : observation.getAsJsonArray("events")) {
                JsonObject row = event.getAsJsonObject();
                if (text(row, "kind").equals("inputDerivationProof")) {
                    require(
                        row.get("details")
                            .equals(proof),
                        "Prior native derivation event differs");
                    events++;
                }
            }
            require(events == 1, "Prior native derivation proof event missing/duplicate");
        }
    }

    private static void verifyRecordedSources(JsonObject proof, JsonObject declaration, JsonObject contract,
        Access access) throws Exception {
        JsonObject sources = proof.getAsJsonObject("sources");
        JsonObject ae = contract.getAsJsonObject("ae"), tlm = contract.getAsJsonObject("tlm");
        String physicalMain = physicalMainIdentity(declaration, contract, access);
        require(
            declaration.has("inputDerivationPhysicalBinding") == proof.has("physicalProviderBinding")
                && (!declaration.has("inputDerivationPhysicalBinding")
                    || declaration.get("inputDerivationPhysicalBinding")
                        .equals(proof.get("physicalProviderBinding"))),
            "Prior physical provider binding proof differs");
        require(
            sources.entrySet()
                .size()
                == ae.getAsJsonObject("classes")
                    .entrySet()
                    .size() + 3,
            "Prior input derivation provider set differs");
        Path root = access.checked(Paths.get(text(declaration, "instanceRoot")));
        for (java.util.Map.Entry<String, JsonElement> entry : sources.entrySet()) {
            String name = entry.getKey();
            JsonObject source = entry.getValue()
                .getAsJsonObject();
            Path artifact = access.checked(Paths.get(text(source, "codeSource")));
            require(artifact.startsWith(root) && name.equals(text(source, "class")), "Prior provider location differs");
            String relative = root.relativize(artifact)
                .toString()
                .replace('\\', '/');
            require(
                text(source, "sourceSha256").equals(text(declaration.getAsJsonObject("files"), relative))
                    && text(source, "sourceSha256").equals(hash(Files.readAllBytes(artifact)))
                    && !source.get("transformedDefinitionBytesVerified")
                        .getAsBoolean(),
                "Prior provider artifact differs");
            try (ZipFile jar = new ZipFile(artifact.toFile())) {
                java.util.zip.ZipEntry physical = jar.getEntry(name.replace('.', '/') + ".class");
                require(physical != null, "Prior physical provider missing");
                try (InputStream stream = jar.getInputStream(physical);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = stream.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                    require(
                        text(source, "entrySha256").equals(hash(bytes.toByteArray())),
                        "Prior physical provider bytes differ");
                }
            }
            if (ae.getAsJsonObject("classes")
                .has(name))
                require(
                    relative.equals(text(ae, "providerJar"))
                        && text(source, "sourceSha256").equals(text(ae, "providerJarSha256"))
                        && text(source, "entrySha256").equals(text(ae.getAsJsonObject("classes"), name)),
                    "Prior AE provider differs");
            else if (name.equals(text(tlm, "providerClass"))) require(
                relative.equals(text(declaration.getAsJsonObject("artifacts"), "main"))
                    && text(source, "sourceSha256").equals(physicalMain)
                    && text(source, "entrySha256").equals(text(tlm, "providerClassSha256")),
                "Prior TLM provider differs");
            else if (name.equals(InputDerivationProtocol.class.getName())) require(
                relative.equals(text(declaration.getAsJsonObject("artifacts"), "qa")),
                "Prior QA helper differs");
            else require(name.equals(Loader.class.getName()), "Unknown prior input derivation provider");
        }
    }

    private static JsonObject read(Path path) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static String hash(byte[] bytes) throws Exception {
        return hex(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String text(JsonObject value, String key) {
        return value.get(key)
            .getAsString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
