
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

tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyBuiltinSounds)
    doLast { verifyBuiltinSoundDirectory(destinationDir) }
}

listOf("jar", "shadowJar").forEach { taskName ->
    tasks.named<Jar>(taskName) {
        dependsOn(verifyBuiltinSounds)
        doLast { verifyBuiltinSoundJar(archiveFile.get().asFile) }
    }
}

tasks.named("reobfJar") {
    doLast {
        val artifacts = outputs.files.files.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        if (artifacts.isEmpty()) throw GradleException("No final reobfuscated JAR was produced for sound verification")
        artifacts.forEach(::verifyBuiltinSoundJar)
    }
}

tasks.named("assemble") { dependsOn("reobfJar", verifyBuiltinSounds) }
tasks.named("check") { dependsOn(verifyBuiltinSounds) }

tasks.withType<Jar>().configureEach {
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
