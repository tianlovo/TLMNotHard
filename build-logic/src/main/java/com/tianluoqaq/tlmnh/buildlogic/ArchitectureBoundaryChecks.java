package com.tianluoqaq.tlmnh.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * 架构边界静态门禁（T0）：校验公开 API 自包含、业务与适配隔离、适配模组间无环、双端物理隔离与开发开关单点读取
 */
public final class ArchitectureBoundaryChecks {

    private static final Pattern IMPORT_PATTERN = Pattern
        .compile("^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern COMPAT_FQN_PATTERN = Pattern
        .compile("\\bcom\\.tianluoqaq\\.tlmnh\\.compat\\.[A-Za-z0-9_.]+");
    private static final Pattern COMPAT_PKG_PATTERN = Pattern
        .compile("^com\\.tianluoqaq\\.tlmnh\\.compat\\.([a-z0-9_]+)(?:\\..*|$)");
    private static final Set<String> ALLOWED_COMPAT_EDGES = new HashSet<>(
        Arrays.asList("tinkersdefense->tconstruct", "tinkersdefense->battlegear2"));

    private ArchitectureBoundaryChecks() {}

    public static void verifyProjectBoundaries(File projectDir) {
        List<String> violations = new ArrayList<>();
        File mainJava = new File(projectDir, "src/main/java");
        File clientJava = new File(projectDir, "src/client/java");
        File compatJava = new File(projectDir, "src/compat/java");
        File compatClientJava = new File(projectDir, "src/compatClient/java");

        verifyNoCompatDirectoryInCore(new File(mainJava, "com/tianluoqaq/tlmnh/compat"), "src/main/java", violations);
        verifyNoCompatDirectoryInCore(
            new File(clientJava, "com/tianluoqaq/tlmnh/compat"),
            "src/client/java",
            violations);

        verifyApiSelfContainment(new File(mainJava, "com/tianluoqaq/tlmnh/api"), projectDir, violations);
        verifyCoreFreeOfCompat(mainJava, projectDir, violations);
        verifyCoreFreeOfCompat(clientJava, projectDir, violations);
        verifyMainFreeOfUnscopedClientTypes(mainJava, projectDir, violations);
        verifyDevFlagSinglePoint(mainJava, projectDir, violations);
        verifyDevFlagSinglePoint(clientJava, projectDir, violations);
        verifyDevFlagSinglePoint(compatJava, projectDir, violations);
        verifyDevFlagSinglePoint(compatClientJava, projectDir, violations);
        verifyCompatCrossModEdges(compatJava, projectDir, violations);
        verifyCompatCrossModEdges(compatClientJava, projectDir, violations);

        if (!violations.isEmpty()) {
            StringBuilder report = new StringBuilder("Architecture boundary check failed (").append(violations.size())
                .append(" violation(s)):\n");
            for (String v : violations) {
                report.append("  - ")
                    .append(v)
                    .append('\n');
            }
            throw new GradleException(report.toString());
        }
    }

    private static void verifyNoCompatDirectoryInCore(File compatDir, String label, List<String> violations) {
        if (!compatDir.exists()) return;
        List<Path> files = walkJavaAndMarkdown(compatDir);
        if (!files.isEmpty()) {
            violations.add(
                label + " still contains compat files (" + files.size() + "); move to src/compat or src/compatClient");
        }
    }

    private static void verifyApiSelfContainment(File apiDir, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(apiDir)) {
            String rel = relPath(projectDir, file);
            String source = readUtf8(file);
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.") || imported.startsWith("net.minecraft.")
                    || imported.startsWith("net.minecraftforge.")
                    || imported.startsWith("cpw.mods.fml.")
                    || imported.startsWith("com.tianluoqaq.tlmnh.api.")) {
                    continue;
                }
                if (rel.endsWith("/MaidAttackTargetEvent.java")
                    && "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid".equals(imported)) {
                    continue;
                }
                violations.add("[API self-containment] " + rel + " imports internal type: " + imported);
            }
        }
    }

    private static void verifyCoreFreeOfCompat(File sourceRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(sourceRoot)) {
            String rel = relPath(projectDir, file);
            String source = stripComments(readUtf8(file));
            Matcher matcher = COMPAT_FQN_PATTERN.matcher(source);
            if (matcher.find()) {
                violations.add("[Core->Compat] " + rel + " references compat symbol: " + matcher.group());
            }
        }
    }

    private static void verifyMainFreeOfUnscopedClientTypes(File mainJava, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(mainJava)) {
            String rel = relPath(projectDir, file);
            if (rel.contains("/mixin/client/")) continue;
            String raw = readUtf8(file);
            if (raw.contains("@SideOnly(Side.CLIENT)")) continue;
            String source = stripComments(raw);
            for (String prefix : Arrays
                .asList("net.minecraft.client.", "net.minecraftforge.client.", "cpw.mods.fml.client.", "org.lwjgl.")) {
                if (source.contains(prefix)) {
                    violations.add(
                        "[Main->Client] " + rel
                            + " references client package "
                            + prefix
                            + " without @SideOnly(Side.CLIENT)");
                }
            }
        }
    }

    private static void verifyDevFlagSinglePoint(File sourceRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(sourceRoot)) {
            String rel = relPath(projectDir, file);
            if (rel.endsWith("/DevelopmentFlags.java")) continue;
            String source = stripComments(readUtf8(file));
            if (source.contains("\"tlmnh.devCommands\"")) {
                violations
                    .add("[DevFlag single-point] " + rel + " reads tlmnh.devCommands directly; use DevelopmentFlags");
            }
        }
    }

    private static void verifyCompatCrossModEdges(File compatRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(compatRoot)) {
            String rel = relPath(projectDir, file);
            String source = readUtf8(file);
            String pkg = parsePackage(source);
            Matcher ownMatcher = COMPAT_PKG_PATTERN.matcher(pkg);
            if (!ownMatcher.matches()) {
                // Root bootstrap / descriptor registry (e.g. com.tianluoqaq.tlmnh.compat) is allowed to wire all
                // modules
                continue;
            }
            String ownMod = ownMatcher.group(1);
            Matcher impMatcher = IMPORT_PATTERN.matcher(source);
            while (impMatcher.find()) {
                String imported = impMatcher.group(1);
                Matcher targetMatcher = COMPAT_PKG_PATTERN.matcher(imported);
                if (!targetMatcher.matches()) continue;
                String targetMod = targetMatcher.group(1);
                if (ownMod.equals(targetMod)) continue;
                String edge = ownMod + "->" + targetMod;
                if (!ALLOWED_COMPAT_EDGES.contains(edge)) {
                    violations.add(
                        "[Compat cross-mod] " + rel
                            + " introduces disallowed compat edge "
                            + edge
                            + " ("
                            + imported
                            + ")");
                }
            }
        }
    }

    private static String parsePackage(String source) {
        Matcher m = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE)
            .matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> walkJavaFiles(File dir) {
        List<Path> files = new ArrayList<>();
        if (!dir.exists()) return files;
        try (var stream = Files.walk(dir.toPath())) {
            stream.filter(
                p -> Files.isRegularFile(p) && p.toString()
                    .endsWith(".java"))
                .sorted()
                .forEach(files::add);
        } catch (IOException failure) {
            throw new GradleException("Failed to scan Java sources under " + dir.getPath(), failure);
        }
        return files;
    }

    private static List<Path> walkJavaAndMarkdown(File dir) {
        List<Path> files = new ArrayList<>();
        if (!dir.exists()) return files;
        try (var stream = Files.walk(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                .sorted()
                .forEach(files::add);
        } catch (IOException failure) {
            throw new GradleException("Failed to scan directory " + dir.getPath(), failure);
        }
        return files;
    }

    private static String relPath(File root, Path path) {
        return root.toPath()
            .relativize(path)
            .toString()
            .replace('\\', '/');
    }

    private static String readUtf8(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GradleException("Failed to read " + path, failure);
        }
    }
}
