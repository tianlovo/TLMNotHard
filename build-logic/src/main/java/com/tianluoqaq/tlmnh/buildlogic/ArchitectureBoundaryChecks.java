package com.tianluoqaq.tlmnh.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * 架构边界与全量领域字面量治理门禁（T0）：
 * 校验公开 API 自包含、foundation 零反向依赖、核心与适配隔离、核心不依赖 Mixin/主入口、包容量上限、定义文件反上帝类约束，以及业务文件零散落字符串与业务魔数
 */
public final class ArchitectureBoundaryChecks {

    private static final Pattern IMPORT_PATTERN = Pattern
        .compile("^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE_PATTERN = Pattern
        .compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern COMPAT_FQN_PATTERN = Pattern
        .compile("\\bcom\\.tianluoqaq\\.tlmnh\\.compat\\.[A-Za-z0-9_.]+");
    private static final Pattern INTERNAL_TLMNH_FQN_PATTERN = Pattern
        .compile("\\bcom\\.tianluoqaq\\.tlmnh\\.(?!common\\.foundation\\b)[A-Za-z0-9_.]+");
    private static final Pattern MIXIN_FQN_PATTERN = Pattern
        .compile("\\bcom\\.tianluoqaq\\.tlmnh\\.(?:[a-z0-9_.]+\\.)?mixin\\.[A-Za-z0-9_.]+");
    private static final Pattern COMPAT_PKG_PATTERN = Pattern
        .compile("^com\\.tianluoqaq\\.tlmnh\\.compat\\.([a-z0-9_]+)(?:\\..*|$)");
    private static final Pattern STATIC_FINAL_CONST_PATTERN = Pattern.compile(
        "\\b(?:static\\s+final|final\\s+static)\\s+(?:byte|short|int|long|float|double|boolean|char|String)\\s+([A-Za-z0-9_]+)\\s*=");
    private static final Pattern LOCAL_ZERO_INIT_PATTERN = Pattern.compile(
        "^(?!\\s*(?:public|protected|private|static)\\b).*\\b(?:byte|short|int|long|float|double)\\s+[A-Za-z0-9_]+\\s*=\\s*$");

    private static final Set<String> ALLOWED_COMPAT_EDGES = new HashSet<>(
        Arrays.asList("tinkersdefense->tconstruct", "tinkersdefense->battlegear2"));

    private static final List<String> DEFINITION_SUFFIXES = Arrays
        .asList("NbtKeys", "Tuning", "Layout", "Protocol", "Ids", "ConfigSpec", "ReflectionSpec", "Diagnostics");

    private static final Set<String> EXPLICIT_DEFINITION_CLASSES = new HashSet<>(
        Arrays.asList("KnownModIds", "ModIds"));

    private static final Set<String> FORBIDDEN_GOD_CONSTANT_NAMES = new HashSet<>(
        Arrays.asList(
            "Constants",
            "GlobalConstants",
            "ModConstants",
            "CommonConstants",
            "MagicNumbers",
            "Strings",
            "Literals"));

    private static final Set<String> ALLOWED_PINNED_CONST_FIELDS = new HashSet<>(
        Arrays.asList(
            "TLMNotHard#MOD_ID",
            "TLMNotHard#MOD_NAME",
            "MaidBehaviorId#BUILTIN_NAMESPACE",
            "CompatibilityLedger#NAME",
            "MaidLifecycleData#NAME",
            "Tags#VERSION"));

    private static final Set<String> EXACT_ZERO_TOKENS = new HashSet<>(
        Arrays.asList("0", "0L", "0l", "0.0", "0.0F", "0.0f", "0.0D", "0.0d", "0F", "0f", "0D", "0d", "0x0", "0X0"));

    private static final Set<String> EXACT_INT_ONE_TOKENS = new HashSet<>(Arrays.asList("1", "1L", "1l"));

    private static final List<String> UNARY_KEYWORDS = Arrays
        .asList("return", "case", "yield", "throw", "else", "assert", "instanceof", "new");

    private static final int MAX_CONSTANTS_PER_DEFINITION_FILE = 120;
    private static final int DEFAULT_MAX_BUSINESS_CLASSES_PER_PACKAGE = 12;

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
        verifyFoundationZeroInternalDeps(
            new File(mainJava, "com/tianluoqaq/tlmnh/common/foundation"),
            projectDir,
            violations);
        verifyCoreFreeOfCompat(mainJava, projectDir, violations);
        verifyCoreFreeOfCompat(clientJava, projectDir, violations);
        verifyNonMixinFreeOfMixinImports(mainJava, projectDir, violations);
        verifyNonMixinFreeOfMixinImports(clientJava, projectDir, violations);
        verifyNonMixinFreeOfMixinImports(compatJava, projectDir, violations);
        verifyNonMixinFreeOfMixinImports(compatClientJava, projectDir, violations);
        verifyNoEntryBackEdges(mainJava, projectDir, violations);
        verifyNoEntryBackEdges(clientJava, projectDir, violations);
        verifyMainFreeOfUnscopedClientTypes(mainJava, projectDir, violations);
        verifyDevFlagSinglePoint(mainJava, projectDir, violations);
        verifyDevFlagSinglePoint(clientJava, projectDir, violations);
        verifyDevFlagSinglePoint(compatJava, projectDir, violations);
        verifyDevFlagSinglePoint(compatClientJava, projectDir, violations);
        verifyCompatCrossModEdges(compatJava, projectDir, violations);
        verifyCompatCrossModEdges(compatClientJava, projectDir, violations);

        verifyPackageCapacity(mainJava, "main", violations);
        verifyPackageCapacity(clientJava, "client", violations);

        verifyDomainDefinitionsAndLiterals(new File(mainJava, "com/tianluoqaq/tlmnh"), projectDir, violations);
        verifyDomainDefinitionsAndLiterals(new File(clientJava, "com/tianluoqaq/tlmnh"), projectDir, violations);
        verifyDomainDefinitionsAndLiterals(new File(compatJava, "com/tianluoqaq/tlmnh"), projectDir, violations);
        verifyDomainDefinitionsAndLiterals(new File(compatClientJava, "com/tianluoqaq/tlmnh"), projectDir, violations);

        if (!violations.isEmpty()) {
            StringBuilder report = new StringBuilder("Architecture boundary check failed (").append(violations.size())
                .append(" violation(s)):\n");
            int limit = Math.min(violations.size(), 150);
            for (int i = 0; i < limit; i++) {
                report.append("  - ")
                    .append(violations.get(i))
                    .append('\n');
            }
            if (violations.size() > limit) {
                report.append("  ... and ")
                    .append(violations.size() - limit)
                    .append(" more violation(s)\n");
            }
            throw new GradleException(report.toString());
        }
    }

    public static boolean isDefinitionClassName(String simpleClassName) {
        if (EXPLICIT_DEFINITION_CLASSES.contains(simpleClassName)) return true;
        for (String suffix : DEFINITION_SUFFIXES) {
            if (simpleClassName.endsWith(suffix) && simpleClassName.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    private static void verifyFoundationZeroInternalDeps(File foundationDir, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(foundationDir)) {
            String rel = relPath(projectDir, file);
            String source = stripCommentsAndStrings(readUtf8(file));
            Matcher imp = IMPORT_PATTERN.matcher(source);
            while (imp.find()) {
                String imported = imp.group(1);
                if (imported.startsWith("com.tianluoqaq.tlmnh.")
                    && !imported.startsWith("com.tianluoqaq.tlmnh.common.foundation.")) {
                    violations.add(
                        "[Foundation->Internal] " + rel
                            + " imports non-foundation package "
                            + imported
                            + "; common.foundation must stay zero-dependency");
                }
            }
            Matcher fqn = INTERNAL_TLMNH_FQN_PATTERN.matcher(source);
            while (fqn.find()) {
                String match = fqn.group();
                // Allow string-free package declaration itself
                if ("com.tianluoqaq.tlmnh.common.foundation".startsWith(match)) continue;
                violations.add(
                    "[Foundation->Internal] " + rel
                        + " references non-foundation symbol "
                        + match
                        + "; common.foundation must stay zero-dependency");
            }
        }
    }

    private static void verifyNonMixinFreeOfMixinImports(File sourceRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(sourceRoot)) {
            String rel = relPath(projectDir, file);
            if (rel.contains("/mixin/") || rel.endsWith("/MixinCompatibilityPlugin.java")) continue;
            String source = stripCommentsAndStrings(readUtf8(file));
            Matcher matcher = MIXIN_FQN_PATTERN.matcher(source);
            if (matcher.find()) {
                violations.add(
                    "[Core->Mixin] " + rel
                        + " references mixin type directly: "
                        + matcher.group()
                        + "; use a domain access interface");
            }
        }
    }

    private static void verifyNoEntryBackEdges(File sourceRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(sourceRoot)) {
            String rel = relPath(projectDir, file);
            if (rel.endsWith("/TLMNotHard.java")) continue;
            String source = stripCommentsAndStrings(readUtf8(file));
            if (source.contains("com.tianluoqaq.tlmnh.TLMNotHard") || Pattern.compile("\\bTLMNotHard\\b")
                .matcher(source)
                .find()) {
                violations.add(
                    "[Package->TLMNotHard] " + rel + " references TLMNotHard entry class; use ModIds or ModContext");
            }
        }
    }

    private static void verifyPackageCapacity(File sourceRoot, String label, List<String> violations) {
        File tlmnhRoot = new File(sourceRoot, "com/tianluoqaq/tlmnh");
        Map<String, Integer> businessCounts = new HashMap<>();
        for (Path file : walkJavaFiles(tlmnhRoot)) {
            String simpleName = simpleClassName(file);
            if (isDefinitionClassName(simpleName) || "package-info".equals(simpleName)) continue;
            String pkg = parsePackage(readUtf8(file));
            businessCounts.put(pkg, businessCounts.getOrDefault(pkg, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : businessCounts.entrySet()) {
            String pkg = entry.getKey();
            int count = entry.getValue();
            int cap = packageBusinessCap(pkg);
            if (count > cap) {
                violations.add(
                    "[Package overcrowding] " + label
                        + ":"
                        + pkg
                        + " has "
                        + count
                        + " non-definition classes (cap "
                        + cap
                        + "); split cohesive subpackages");
            }
        }
    }

    private static int packageBusinessCap(String pkg) {
        if ("com.tianluoqaq.tlmnh.common.lifecycle".equals(pkg)) return 23;
        if (pkg.startsWith("com.tianluoqaq.tlmnh.mixin") || pkg.startsWith("com.tianluoqaq.tlmnh.api")) return 16;
        return DEFAULT_MAX_BUSINESS_CLASSES_PER_PACKAGE;
    }

    private static void verifyDomainDefinitionsAndLiterals(File tlmnhRoot, File projectDir, List<String> violations) {
        for (Path file : walkJavaFiles(tlmnhRoot)) {
            String rel = relPath(projectDir, file);
            String simpleName = simpleClassName(file);
            if ("package-info".equals(simpleName) || "Tags".equals(simpleName)) continue;
            if (rel.contains("/mixin/")) continue;

            if (FORBIDDEN_GOD_CONSTANT_NAMES.contains(simpleName)) {
                violations.add(
                    "[God-constants forbidden] " + rel
                        + " uses generic constant-bag name '"
                        + simpleName
                        + "'; split by domain role (*NbtKeys, *Tuning, *Layout, *Protocol, *Ids, *ConfigSpec, *ReflectionSpec, *Diagnostics)");
                continue;
            }

            String raw = readUtf8(file);
            String noComments = stripCommentsPreservingLines(raw);

            if (isDefinitionClassName(simpleName)) {
                int constCount = 0;
                Matcher cm = STATIC_FINAL_CONST_PATTERN.matcher(stripStringsOnly(noComments));
                while (cm.find()) constCount++;
                if (constCount > MAX_CONSTANTS_PER_DEFINITION_FILE) {
                    violations.add(
                        "[Definition file too large] " + rel
                            + " declares "
                            + constCount
                            + " constants (max "
                            + MAX_CONSTANTS_PER_DEFINITION_FILE
                            + "); split by protocol/role");
                }
                continue;
            }

            // Business file checks:
            // Only @SuppressWarnings(...) JLS warning tokens are masked; @Mod/@SidedProxy/@Optional must use constants
            String masked = maskSuppressWarningsOnly(noComments);
            String codeNoStrings = stripStringsOnly(masked);

            // 1. No embedded static final primitive/String constants (except contract-pinned aliases & *Compat#MOD_ID)
            Matcher cm = STATIC_FINAL_CONST_PATTERN.matcher(codeNoStrings);
            while (cm.find()) {
                String fieldName = cm.group(1);
                if ("serialVersionUID".equals(fieldName)) continue;
                if (ALLOWED_PINNED_CONST_FIELDS.contains(simpleName + "#" + fieldName)) continue;
                if (simpleName.endsWith("Compat") && "MOD_ID".equals(fieldName)) continue;
                violations.add(
                    "[Embedded business constant] " + rel
                        + " declares static final constant '"
                        + fieldName
                        + "'; move to domain definition file");
            }

            // 2. Scan all lines for non-empty strings and non-structural numeric literals
            scanDisallowedLiterals(masked, codeNoStrings, rel, violations);
        }
    }

    private static String maskSuppressWarningsOnly(String noComments) {
        char[] chars = noComments.toCharArray();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            char c = chars[i];
            if (c == '"' || c == '\'') {
                i = skipQuoted(chars, i);
                continue;
            }
            if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(chars[i + 1])) {
                int j = i + 1;
                while (j < n && (Character.isJavaIdentifierPart(chars[j]) || chars[j] == '.')) j++;
                String annName = new String(chars, i + 1, j - (i + 1));
                int k = j;
                while (k < n && Character.isWhitespace(chars[k])) k++;
                if (k < n && chars[k] == '('
                    && ("SuppressWarnings".equals(annName) || "java.lang.SuppressWarnings".equals(annName))) {
                    i = maskBalancedParens(chars, k);
                    continue;
                }
            }
            i++;
        }
        return new String(chars);
    }

    private static int maskBalancedParens(char[] chars, int openIdx) {
        int depth = 0;
        int i = openIdx;
        int n = chars.length;
        while (i < n) {
            char c = chars[i];
            if (c == '"' || c == '\'') {
                int end = skipQuoted(chars, i);
                for (int m = i; m < end; m++) {
                    if (chars[m] != '\n' && chars[m] != '\r') chars[m] = ' ';
                }
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
                chars[i] = ' ';
            } else if (c == ')') {
                depth--;
                chars[i] = ' ';
                if (depth == 0) {
                    return i + 1;
                }
            } else if (c != '\n' && c != '\r') {
                chars[i] = ' ';
            }
            i++;
        }
        return n;
    }

    private static void scanDisallowedLiterals(String masked, String codeNoStrings, String rel,
        List<String> violations) {
        String[] maskedLines = masked.split("\n", -1);
        String[] noStrLines = codeNoStrings.split("\n", -1);
        int lineCount = Math.min(maskedLines.length, noStrLines.length);
        String prevNonEmptyLine = "";

        for (int idx = 0; idx < lineCount; idx++) {
            int lineNo = idx + 1;
            String lnMasked = stripTrailingCr(maskedLines[idx]);
            String lnNoStr = stripTrailingCr(noStrLines[idx]);
            String trimmedNoStr = lnNoStr.trim();
            if (trimmedNoStr.startsWith("package ") || trimmedNoStr.startsWith("import ")) {
                continue;
            }

            // Scan non-empty string literals
            char[] mc = lnMasked.toCharArray();
            int i = 0;
            while (i < mc.length) {
                if (mc[i] == '\'') {
                    i = skipQuoted(mc, i);
                    continue;
                }
                if (mc[i] == '"') {
                    int end = skipQuoted(mc, i);
                    String content = new String(mc, i + 1, Math.max(0, end - i - 2));
                    if (!content.isEmpty()) {
                        violations.add(
                            "[Hardcoded string] " + rel + ":" + lineNo + " contains \"" + truncate(content, 48) + "\"");
                    }
                    i = end;
                    continue;
                }
                i++;
            }

            // Scan numeric literals
            char[] nc = lnNoStr.toCharArray();
            i = 0;
            while (i < nc.length) {
                char ch = nc[i];
                boolean prevIdent = i > 0 && Character.isJavaIdentifierPart(nc[i - 1]);
                if (!prevIdent
                    && (Character.isDigit(ch) || (ch == '.' && i + 1 < nc.length && Character.isDigit(nc[i + 1])))) {
                    int start = i;
                    if (ch == '0' && i + 1 < nc.length
                        && (nc[i + 1] == 'x' || nc[i + 1] == 'X' || nc[i + 1] == 'b' || nc[i + 1] == 'B')) {
                        i += 2;
                        while (i < nc.length && (Character.isLetterOrDigit(nc[i]) || nc[i] == '_')) i++;
                    } else {
                        while (i < nc.length && (Character.isDigit(nc[i]) || nc[i] == '_' || nc[i] == '.')) i++;
                        if (i < nc.length && (nc[i] == 'e' || nc[i] == 'E')) {
                            i++;
                            if (i < nc.length && (nc[i] == '+' || nc[i] == '-')) i++;
                            while (i < nc.length && (Character.isDigit(nc[i]) || nc[i] == '_')) i++;
                        }
                        while (i < nc.length && Character.isLetter(nc[i])) i++;
                    }
                    String numToken = new String(nc, start, i - start);
                    if (!isStructuralZeroOrOne(lnNoStr, prevNonEmptyLine, start, i, numToken)) {
                        violations.add("[Magic number] " + rel + ":" + lineNo + " contains literal " + numToken);
                    }
                    continue;
                }
                i++;
            }
            if (!trimmedNoStr.isEmpty()) {
                prevNonEmptyLine = trimmedNoStr;
            }
        }
    }

    private static boolean isStructuralZeroOrOne(String lineNoStrings, String prevNonEmptyLine, int colStart,
        int colEnd, String numToken) {
        String trimmed = lineNoStrings.trim();
        String before = rtrim(lineNoStrings.substring(0, colStart));
        String after = lineNoStrings.substring(colEnd)
            .trim();

        // Reject unary minus (e.g. -1, -0.0); allow single-line or wrapped binary subtraction `expr - 1`
        if (before.endsWith("-")) {
            if (before.endsWith("--")) {
                return false;
            }
            String prevBeforeMinus = rtrim(before.substring(0, before.length() - 1));
            if (prevBeforeMinus.isEmpty()) {
                prevBeforeMinus = prevNonEmptyLine;
            }
            if (!isBinaryExprEnd(prevBeforeMinus)) {
                return false;
            }
        }

        if (EXACT_INT_ONE_TOKENS.contains(numToken)) {
            if (trimmed.startsWith("for ") || trimmed.startsWith("for(")) {
                return true;
            }
            if (before.endsWith("+=") || before.endsWith("-=")) {
                return true;
            }
            if (before.endsWith("+") || before.endsWith("-")) {
                if (before.endsWith("++") || before.endsWith("--")) {
                    return false;
                }
                String prevOp = rtrim(before.substring(0, before.length() - 1));
                if (prevOp.isEmpty()) {
                    prevOp = prevNonEmptyLine;
                }
                if (isBinaryExprEnd(prevOp)) {
                    return true;
                }
            }
            return false;
        }

        if (EXACT_ZERO_TOKENS.contains(numToken)) {
            if ((trimmed.startsWith("for ") || trimmed.startsWith("for("))
                && ("0".equals(numToken) || "0L".equals(numToken) || "0l".equals(numToken))) {
                return true;
            }
            String effectiveBefore = before.isEmpty() ? prevNonEmptyLine : before;
            if (endsWithRelOp(effectiveBefore) || startsWithRelOp(after)) {
                return true;
            }
            if ("0".equals(numToken) && before.endsWith("[") && after.startsWith("]") && before.contains("new ")) {
                return true;
            }
            if ("return".equals(before.trim()) && after.startsWith(";")) {
                return true;
            }
            if (LOCAL_ZERO_INIT_PATTERN.matcher(lineNoStrings.substring(0, colStart))
                .matches() && (after.startsWith(";") || after.startsWith(","))) {
                return true;
            }
            return false;
        }

        return false;
    }

    private static boolean isBinaryExprEnd(String prev) {
        if (prev == null || prev.isEmpty()) {
            return false;
        }
        if (!isExprEndChar(prev.charAt(prev.length() - 1))) {
            return false;
        }
        for (String kw : UNARY_KEYWORDS) {
            if (prev.endsWith(kw)) {
                int idx = prev.length() - kw.length();
                if (idx == 0 || !Character.isJavaIdentifierPart(prev.charAt(idx - 1))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isExprEndChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ')' || c == ']';
    }

    private static boolean endsWithRelOp(String s) {
        return s.endsWith("==") || s
            .endsWith("!=") || s.endsWith("<=") || s.endsWith(">=") || s.endsWith("<") || s.endsWith(">");
    }

    private static boolean startsWithRelOp(String s) {
        return s.startsWith("==") || s
            .startsWith("!=") || s.startsWith("<=") || s.startsWith(">=") || s.startsWith("<") || s.startsWith(">");
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int skipQuoted(char[] chars, int quoteIdx) {
        char quote = chars[quoteIdx];
        int i = quoteIdx + 1;
        int n = chars.length;
        while (i < n) {
            if (chars[i] == '\\') {
                i += 2;
                continue;
            }
            if (chars[i] == quote) {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static String stripStringsOnly(String noComments) {
        char[] chars = noComments.toCharArray();
        int i = 0;
        int n = chars.length;
        while (i < n) {
            if (chars[i] == '"' || chars[i] == '\'') {
                int end = skipQuoted(chars, i);
                for (int k = i + 1; k < end - 1; k++) {
                    if (chars[k] != '\n' && chars[k] != '\r') chars[k] = ' ';
                }
                i = end;
                continue;
            }
            i++;
        }
        return new String(chars);
    }

    private static String stripCommentsAndStrings(String source) {
        return stripStringsOnly(stripCommentsPreservingLines(source));
    }

    private static String stripCommentsPreservingLines(String source) {
        char[] chars = source.toCharArray();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            if (chars[i] == '"' || chars[i] == '\'') {
                i = skipQuoted(chars, i);
                continue;
            }
            if (chars[i] == '/' && i + 1 < n && chars[i + 1] == '/') {
                while (i < n && chars[i] != '\n' && chars[i] != '\r') {
                    chars[i++] = ' ';
                }
                continue;
            }
            if (chars[i] == '/' && i + 1 < n && chars[i + 1] == '*') {
                chars[i++] = ' ';
                chars[i++] = ' ';
                while (i < n) {
                    if (chars[i] == '*' && i + 1 < n && chars[i + 1] == '/') {
                        chars[i++] = ' ';
                        chars[i++] = ' ';
                        break;
                    }
                    if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
                    i++;
                }
                continue;
            }
            i++;
        }
        return new String(chars);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static String simpleClassName(Path file) {
        String name = file.getFileName()
            .toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
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
                    || imported.startsWith("com.tianluoqaq.tlmnh.api.")
                    || imported.startsWith("com.tianluoqaq.tlmnh.common.foundation.")) {
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
            String source = stripCommentsAndStrings(readUtf8(file));
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
            String source = stripCommentsAndStrings(raw);
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
            if (rel.endsWith("/DevelopmentFlags.java") || rel.endsWith("/DiagnosticIds.java")) continue;
            String source = stripCommentsPreservingLines(readUtf8(file));
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
        Matcher m = PACKAGE_PATTERN.matcher(source);
        return m.find() ? m.group(1) : "";
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
