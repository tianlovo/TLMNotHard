
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Jabel 使用 Java17 语法；IDE 导入模型也应保留 Java8 的目标字节码
extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    project {
        languageLevel = org.gradle.plugins.ide.idea.model.IdeaLanguageLevel(JavaVersion.VERSION_17)
        targetBytecodeVersion = JavaVersion.VERSION_1_8
    }
}

// 固定上游的435配音与10基础音效均为源码输入，构建不依赖Python、研究checkout或网络导入
fun verifyBuiltinSoundResources(read: (String) -> ByteArray, names: List<String>, label: String) {
    fun requireSound(condition: Boolean, message: String) {
        if (!condition) throw GradleException("Builtin sounds [$label]: $message")
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val manifestPath = "assets/tlmnh/builtin-sounds-provenance.json"
    val catalogPath = "assets/tlmnh/builtin-sounds.json"
    val raw = read(manifestPath)
    requireSound(sha256(raw) == "2c7957f377103ea90ec71b82c5969c334b52954c66e21c13036869d429aaf5b0",
        "authority manifest differs from the fixed original-byte inventory")
    val manifest = JsonSlurper().parseText(raw.toString(Charsets.UTF_8)) as Map<*, *>
    requireSound(manifest["schema"] == 2 && manifest["commit"] == "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6",
        "unexpected source schema or upstream commit")
    requireSound(sha256(read(catalogPath)) == manifest["catalog_sha256"], "catalog differs from authority manifest")
    requireSound(names.count { it == manifestPath } == 1 && names.count { it == catalogPath } == 1,
        "missing or duplicate sound manifests")
    val recorded = mutableSetOf<String>()
    val audio = mutableSetOf<String>()
    var voiceCount = 0
    var baseCount = 0
    for (value in manifest["files"] as List<*>) {
        val entry = value as Map<*, *>
        val path = entry["path"] as String
        requireSound(path.startsWith("assets/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
            && '\\' !in path && ':' !in path, "unsafe resource path: $path")
        requireSound(recorded.add(path) && names.count { it == path } == 1, "missing or duplicate resource: $path")
        val bytes = read(path)
        requireSound(bytes.size.toLong() == (entry["bytes"] as Number).toLong() && sha256(bytes) == entry["sha256"],
            "original resource bytes differ: $path")
        requireSound(entry["audio"] is Boolean, "missing audio classification: $path")
        if (entry["audio"] == true) {
            requireSound(path.endsWith(".ogg") && bytes.size >= 4
                && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4f, 0x67, 0x67, 0x53)), "invalid Ogg: $path")
            audio.add(path)
            when (entry["audio_group"]) {
                "voice" -> voiceCount++
                "base" -> baseCount++
                else -> throw GradleException("Builtin sounds [$label]: unknown audio group: $path")
            }
        }
    }
    val actualAudio = names.filter { it.endsWith(".ogg", ignoreCase = true) }
    requireSound(recorded.size == 483 && audio.size == 445 && voiceCount == 435 && baseCount == 10,
        "expected 483 sourced entries, including 435 voice and 10 base Ogg files")
    requireSound(actualAudio.size == actualAudio.toSet().size && actualAudio.toSet() == audio,
        "actual Ogg inventory differs from the complete fixed inventory")
}

fun verifyBuiltinSoundDirectory(directory: File) {
    val names = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toList()
    verifyBuiltinSoundResources({ File(directory, it).readBytes() }, names, directory.path)
}

fun verifyBuiltinSoundJar(file: File) {
    ZipFile(file).use { archive ->
        val names = archive.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        verifyBuiltinSoundResources({ name -> archive.getInputStream(archive.getEntry(name)).use { it.readBytes() } }, names, file.path)
    }
}

val verifyBuiltinSounds = tasks.register("verifyBuiltinSounds") {
    group = "verification"
    description = "Verifies all 445 tracked original-byte Ogg resources and their authoritative source manifest"
    inputs.files(fileTree("src/main/resources") {
        include("**/*.ogg", "assets/tlmnh/builtin-sounds*.json")
        listOf("littlemaid_peco", "touhou_little_maid").forEach { namespace ->
            include("assets/$namespace/lang/*.json", "assets/$namespace/maid_sound.json", "assets/$namespace/sounds.json")
        }
        include("assets/touhou_little_maid/textures/gui/maid_custom_sound.png", "assets/touhou_little_maid/textures/sound_icon.png")
    })
    doLast { verifyBuiltinSoundDirectory(file("src/main/resources")) }
}

// 保留范围按固定Git树完整枚举，与可玩254目录分开；资源和产物检查不触发本地导入
fun verifyBuiltinRetentionResources(read: (String) -> ByteArray, names: List<String>, label: String) {
    fun requireRetention(condition: Boolean, message: String) {
        if (!condition) throw GradleException("Builtin retention [$label]: $message")
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val counts = names.groupingBy { it }.eachCount()
    val manifestPath = "assets/tlmnh/builtin-retention-provenance.json"
    val raw = read(manifestPath)
    requireRetention(sha256(raw) == "9829ef13de6c8134c947df3e3e2568805a075a2075f3737a27eac22674cafe90",
        "authority manifest differs from the complete fixed Git-tree inventory")
    val manifest = JsonSlurper().parseText(raw.toString(Charsets.UTF_8)) as Map<*, *>
    requireRetention(manifest["schema"] == 1 && manifest["commit"] == "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
        && manifest["license"] == "CC-BY-NC-SA-4.0", "unexpected source schema, commit or license")
    requireRetention(counts[manifestPath] == 1, "missing or duplicate retention manifest")
    fun check(path: String, entry: Map<*, *>) {
        requireRetention(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path
            && path.split('/').none { it.isEmpty() || it == "." || it == ".." }, "unsafe resource path: $path")
        requireRetention(counts[path] == 1, "missing or duplicate retained/protected resource: $path")
        val bytes = read(path)
        requireRetention(sha256(bytes) == entry["sha256"]
            && (entry["bytes"] == null || bytes.size.toLong() == (entry["bytes"] as Number).toLong()),
            "original resource bytes differ: $path")
    }
    val files = manifest["files"] as List<*>
    val recorded = mutableSetOf<String>()
    var totalBytes = 0L
    for (value in files) {
        val entry = value as Map<*, *>
        val path = entry["path"] as String
        requireRetention(recorded.add(path), "duplicate retention declaration: $path")
        check(path, entry)
        totalBytes += (entry["bytes"] as Number).toLong()
    }
    requireRetention(recorded.size == 332 && totalBytes == 5538204L, "expected all 332 reviewed original files")
    val catalog = manifest["catalog"] as Map<*, *>
    check(catalog["path"] as String, catalog)
    for (value in manifest["baseline_manifests"] as List<*>) {
        val baseline = value as Map<*, *>
        val path = baseline["path"] as String
        check(path, baseline)
        // 声音已有独立完整门槛；这里只绑定既有清单，避免重复扫描全部OGG
        if (path.endsWith("builtin-sounds-provenance.json")) continue
        val summary = JsonSlurper().parseText(read(path).toString(Charsets.UTF_8)) as Map<*, *>
        val entries = summary["files"]
        val targets: List<Pair<String, Map<*, *>>> = when (entries) {
            is Map<*, *> -> entries.entries.map { (target, entry) -> target as String to (entry as Map<*, *>) }
            is List<*> -> entries.map { entry ->
                val record = entry as Map<*, *>
                record["path"] as String to record
            }
            else -> throw GradleException("Builtin retention [$label]: invalid protected manifest: $path")
        }
        for ((target, entry) in targets) {
            val resource = if (target.startsWith("licenses/")) "META-INF/$target"
                else target.removePrefix("src/main/resources/")
            requireRetention(resource !in recorded, "retained resource would override an existing effective asset: $resource")
            check(resource, entry)
        }
    }
}

fun verifyBuiltinRetentionDirectory(directory: File) {
    val names = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toList()
    // 处理后资源尚不含Jar阶段加入的许可；此阶段核源码许可，Jar阶段只读实际ZIP条目
    val licenses = file("licenses").listFiles()?.filter { it.isFile } ?: emptyList()
    verifyBuiltinRetentionResources({ name ->
        if (name.startsWith("META-INF/licenses/")) file(name.removePrefix("META-INF/")).readBytes()
        else File(directory, name).readBytes()
    }, names + licenses.map { "META-INF/licenses/${it.name}" }, directory.path)
}

fun verifyBuiltinRetentionJar(file: File) {
    ZipFile(file).use { archive ->
        val names = archive.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        verifyBuiltinRetentionResources({ name -> archive.getInputStream(archive.getEntry(name)).use { it.readBytes() } }, names, file.path)
    }
}

val verifyBuiltinRetention = tasks.register("verifyBuiltinRetention") {
    group = "verification"
    description = "Verifies all 332 retained original files and protects the existing fixed model resources"
    inputs.files(fileTree("src/main/resources") { exclude("**/*.ogg") }, fileTree("licenses"))
    doLast { verifyBuiltinRetentionDirectory(file("src/main/resources")) }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyBuiltinSounds, verifyBuiltinRetention)
    doLast {
        verifyBuiltinSoundDirectory(destinationDir)
        verifyBuiltinRetentionDirectory(destinationDir)
    }
}

listOf("jar", "shadowJar").forEach { taskName ->
    tasks.named<Jar>(taskName) {
        dependsOn(verifyBuiltinSounds, verifyBuiltinRetention)
        doLast {
            verifyBuiltinSoundJar(archiveFile.get().asFile)
            verifyBuiltinRetentionJar(archiveFile.get().asFile)
        }
    }
}

tasks.named("reobfJar") {
    doLast {
        val artifacts = outputs.files.files.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        if (artifacts.isEmpty()) throw GradleException("No final reobfuscated JAR was produced for sound verification")
        artifacts.forEach {
            verifyBuiltinSoundJar(it)
            verifyBuiltinRetentionJar(it)
        }
    }
}

tasks.named("assemble") { dependsOn("reobfJar", verifyBuiltinSounds, verifyBuiltinRetention) }
tasks.named("check") { dependsOn(verifyBuiltinSounds, verifyBuiltinRetention) }

tasks.withType<Jar>().configureEach {
    manifest.attributes("MixinConfigs" to "mixins.tlmnh.json,mixins.tlmnh.ysmu.json")
    from("licenses") { into("META-INF/licenses") }
    from("THIRD_PARTY_NOTICES.md") { into("META-INF") }
}

tasks.named<Jar>("shadowJar") {
    exclude("module-info.class", "META-INF/versions/**", "org/joml/*Kt.class", "META-INF/joml.kotlin_module")
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    workingDir = layout.buildDirectory.dir("test-runtime/$name").get().asFile
    doFirst { workingDir.mkdirs() }
}

val gsonClientRuntime = configurations.create("gsonClientRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(gsonClientRuntime.name, "com.google.code.gson:gson:2.10.1")
}

val testClientGson = tasks.register<Test>("testClientGson") {
    description = "Runs model and development protocol checks with the Gson version used by the client runtime"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("gson-") } + gsonClientRuntime
    include("**/BedrockGeometryTest.class", "**/GeckoGeometryTest.class", "**/BuiltinMaidModelsTest.class", "**/BedrockPoseTest.class", "**/BuiltinBedrockAnimationsTest.class", "**/GeckoAnimationTest.class", "**/GeckoControllerTest.class", "**/BuiltinGeckoPlayerTest.class", "**/ClientDevelopmentRequestTest.class", "**/RenderSubmissionTraceTest.class", "**/BuiltinModelSelectionTest.class", "**/BuiltinModelNamesTest.class")
    useJUnit()
}
tasks.named("check") {
    dependsOn(testClientGson)
}
