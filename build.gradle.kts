
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import com.tianluoqaq.tlmnh.buildlogic.ReleaseArchiveChecks
import java.util.jar.JarFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    id("tlmnh.release-verification")
}

// Jabel 使用 Java 17 语法；IDE 导入模型也应保留 Java 8 的目标字节码
extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    project {
        languageLevel = org.gradle.plugins.ide.idea.model.IdeaLanguageLevel(JavaVersion.VERSION_17)
        targetBytecodeVersion = JavaVersion.VERSION_1_8
    }
}

val verifyProjectLicense = tasks.named("verifyProjectLicense")
val verifyBuiltinSounds = tasks.named("verifyBuiltinSounds")
val verifyBuiltinRetention = tasks.named("verifyBuiltinRetention")

val mainSourceSet = sourceSets.main.get()
val testSourceSet = sourceSets.test.get()

evaluationDependsOn(":kernel:molang")
val molangJar = project(":kernel:molang").tasks.named<Jar>("jar")
val molangRuntime = files(molangJar.flatMap { it.archiveFile })

val clientSourceSet = sourceSets.create("client") {
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath + molangRuntime
    runtimeClasspath += output + mainSourceSet.output + mainSourceSet.runtimeClasspath + molangRuntime
}

val clientTestSourceSet = sourceSets.create("clientTest") {
    compileClasspath += clientSourceSet.output + clientSourceSet.compileClasspath + testSourceSet.compileClasspath
    runtimeClasspath += output + clientSourceSet.output + clientSourceSet.runtimeClasspath + testSourceSet.runtimeClasspath
}

val compatSourceSet = sourceSets.create("compat") {
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + mainSourceSet.output + mainSourceSet.runtimeClasspath
}

val compatClientSourceSet = sourceSets.create("compatClient") {
    compileClasspath += compatSourceSet.output + clientSourceSet.output + mainSourceSet.output + compatSourceSet.compileClasspath + clientSourceSet.compileClasspath
    runtimeClasspath += output + compatSourceSet.output + clientSourceSet.output + compatSourceSet.runtimeClasspath + clientSourceSet.runtimeClasspath
}

val compatTestSourceSet = sourceSets.create("compatTest") {
    compileClasspath += compatClientSourceSet.output + compatSourceSet.output + clientSourceSet.output + compatClientSourceSet.compileClasspath + testSourceSet.compileClasspath
    runtimeClasspath += output + compatClientSourceSet.output + compatSourceSet.output + compatClientSourceSet.runtimeClasspath + testSourceSet.runtimeClasspath
}

dependencies {
    add(compatSourceSet.compileOnlyConfigurationName, "com.github.GTNewHorizons:AppleCore:3.3.13:dev") { isTransitive = false }
    add(compatSourceSet.compileOnlyConfigurationName, "com.github.GTNewHorizons:Battlegear2-for-Backhand:1.6.8-backhand:dev") { isTransitive = false }
    add(compatClientSourceSet.compileOnlyConfigurationName, "com.github.GTNewHorizons:Battlegear2-for-Backhand:1.6.8-backhand:dev") { isTransitive = false }
}

// Contract tests may inspect adapters; main compilation remains independent of their output
testSourceSet.compileClasspath += compatClientSourceSet.output + compatClientSourceSet.compileClasspath
testSourceSet.runtimeClasspath += compatClientSourceSet.output + compatSourceSet.output + compatClientSourceSet.runtimeClasspath + compatClientSourceSet.compileClasspath
clientTestSourceSet.compileClasspath += compatClientSourceSet.output + compatClientSourceSet.compileClasspath
clientTestSourceSet.runtimeClasspath += compatClientSourceSet.output + compatClientSourceSet.runtimeClasspath + compatClientSourceSet.compileClasspath

val replaySourceSet = sourceSets.create("replay") {
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + mainSourceSet.output + mainSourceSet.runtimeClasspath
}

val devtoolsSourceSet = sourceSets.create("devtools") {
    compileClasspath += compatClientSourceSet.output + compatSourceSet.output + clientSourceSet.output + mainSourceSet.output + compatClientSourceSet.compileClasspath
    runtimeClasspath += output + compatClientSourceSet.output + compatSourceSet.output + clientSourceSet.output + compatClientSourceSet.runtimeClasspath
}

val devtoolsTestSourceSet = sourceSets.create("devtoolsTest") {
    compileClasspath += devtoolsSourceSet.output + clientSourceSet.output + devtoolsSourceSet.compileClasspath + testSourceSet.compileClasspath
    runtimeClasspath += output + devtoolsSourceSet.output + devtoolsSourceSet.runtimeClasspath + testSourceSet.runtimeClasspath
}

testSourceSet.compileClasspath += replaySourceSet.output
testSourceSet.runtimeClasspath += replaySourceSet.output

listOf(clientSourceSet, clientTestSourceSet, compatSourceSet, compatClientSourceSet, compatTestSourceSet, replaySourceSet, devtoolsSourceSet, devtoolsTestSourceSet).forEach { extraSourceSet ->
    tasks.named<JavaCompile>(extraSourceSet.compileJavaTaskName) {
        options.annotationProcessorPath = configurations.named("testAnnotationProcessor").get()
    }
}

afterEvaluate {
    tasks.named<ProcessResources>(devtoolsSourceSet.processResourcesTaskName) {
        val props = mapOf("modVersion" to project.version.toString(), "minecraftVersion" to "1.7.10")
        inputs.properties(props)
        filesMatching("mcmod.info") { expand(props) }
    }
}

listOf("jar", "shadowJar").forEach { taskName ->
    tasks.named<Jar>(taskName) {
        dependsOn(molangJar)
        from(clientSourceSet.output)
        from(compatSourceSet.output)
        from(compatClientSourceSet.output)
        from({ molangRuntime.map { zipTree(it).matching { include("**/*.class") } } })
        manifest.attributes("MixinConfigs" to "mixins.tlmnh.json,mixins.tlmnh.ysmu.json")
        dependsOn(verifyBuiltinSounds, verifyBuiltinRetention, verifyProjectLicense)
        doLast {
            val output = archiveFile.get().asFile
            ReleaseArchiveChecks.verifyBuiltinSoundJar(output)
            ReleaseArchiveChecks.verifyBuiltinRetentionJar(output)
            ReleaseArchiveChecks.verifyProjectLicenseJar(output)
            ReleaseArchiveChecks.verifyReleaseJarContents(output)
        }
    }
}

val baseReobfJar = tasks.named<ReobfuscatedJar>("reobfJar")
baseReobfJar.configure {
    referenceClasspath.from(clientSourceSet.compileClasspath, compatSourceSet.compileClasspath, compatClientSourceSet.compileClasspath)
    doLast {
        val artifacts = outputs.files.files.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        if (artifacts.isEmpty()) throw GradleException("No final reobfuscated JAR was produced for sound verification")
        artifacts.forEach {
            ReleaseArchiveChecks.verifyBuiltinSoundJar(it)
            ReleaseArchiveChecks.verifyBuiltinRetentionJar(it)
            ReleaseArchiveChecks.verifyProjectLicenseJar(it)
            ReleaseArchiveChecks.verifyReleaseJarContents(it)
        }
    }
}

val devtoolsJar = tasks.register<Jar>("devtoolsJar") {
    group = "build"
    description = "Packages the un-reobfuscated tlmnhdevtools mod archive"
    dependsOn(devtoolsSourceSet.classesTaskName, verifyProjectLicense)
    from(devtoolsSourceSet.output)
    archiveFileName.set(providers.provider { "tlmnhdevtools-${project.version}-dev.jar" })
    destinationDirectory.set(layout.buildDirectory.dir("devtools"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doLast { ReleaseArchiveChecks.verifyProjectLicenseJar(archiveFile.get().asFile) }
}

val reobfDevtoolsJar = tasks.register<ReobfuscatedJar>("reobfDevtoolsJar") {
    group = "build"
    description = "Reobfuscates the standalone tlmnhdevtools mod archive to SRG names"
    dependsOn(devtoolsJar)
    inputJar.set(devtoolsJar.flatMap { it.archiveFile })
    mcVersion.set(baseReobfJar.flatMap { it.mcVersion })
    srg.set(baseReobfJar.flatMap { it.srg })
    fieldCsv.set(baseReobfJar.flatMap { it.fieldCsv })
    methodCsv.set(baseReobfJar.flatMap { it.methodCsv })
    exceptorCfg.set(baseReobfJar.flatMap { it.exceptorCfg })
    recompMcJar.set(baseReobfJar.flatMap { it.recompMcJar })
    extraSrgEntries.set(baseReobfJar.flatMap { it.extraSrgEntries })
    extraSrgFiles.from(baseReobfJar.map { it.extraSrgFiles })
    referenceClasspath.from(baseReobfJar.map { it.referenceClasspath }, devtoolsSourceSet.compileClasspath)
    archiveFileName.set(providers.provider { "tlmnhdevtools-${project.version}.jar" })
    destinationDirectory.set(layout.buildDirectory.dir("devtools"))
    doLast {
        val output = archiveFile.get().asFile
        ReleaseArchiveChecks.verifyProjectLicenseJar(output)
        JarFile(output).use { archive ->
            if (archive.manifest?.mainAttributes?.getValue("MixinConfigs") != null)
                throw GradleException("Devtools JAR [${output.name}] must not carry production MixinConfigs")
            if (archive.getJarEntry("com/tianluoqaq/tlmnh/devtools/TLMNotHardDevtools.class") == null)
                throw GradleException("Devtools JAR [${output.name}] is missing TLMNotHardDevtools entry point")
        }
    }
}

tasks.named("assemble") { dependsOn("reobfJar", verifyBuiltinSounds, verifyBuiltinRetention, verifyProjectLicense) }
tasks.named("build") { dependsOn(reobfDevtoolsJar) }

tasks.withType<Jar>().configureEach {
    from("licenses") { into("META-INF/licenses") }
    from("THIRD_PARTY_NOTICES.md") { into("META-INF") }
    from("LICENSE") { into("META-INF"); rename { "TLMNH-LICENSE.txt" } }
}

tasks.named<Jar>("shadowJar") {
    exclude("module-info.class", "META-INF/versions/**", "org/joml/*Kt.class", "META-INF/joml.kotlin_module")
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    workingDir = layout.buildDirectory.dir("test-runtime/$name").get().asFile
    doFirst { workingDir.mkdirs() }
}

tasks.named<Jar>("sourcesJar") {
    from(clientSourceSet.allSource)
    from(compatSourceSet.allSource)
    from(compatClientSourceSet.allSource)
    from("kernel/molang/src/main/java")
}

extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        target("src/*/java/**/*.java", "src/*/scala/**/*.java", "kernel/*/src/*/java/**/*.java", "build-logic/src/*/java/**/*.java")
    }
}

val clientTest = tasks.register<Test>("clientTest") {
    description = "Runs the isolated client source set unit tests"
    group = "verification"
    testClassesDirs = clientTestSourceSet.output.classesDirs
    classpath = clientTestSourceSet.runtimeClasspath
    useJUnit()
}

val compatTest = tasks.register<Test>("compatTest") {
    description = "Runs third-party adapter descriptor and boundary unit tests"
    group = "verification"
    testClassesDirs = compatTestSourceSet.output.classesDirs
    classpath = compatTestSourceSet.runtimeClasspath
    useJUnit()
}

val devtoolsTest = tasks.register<Test>("devtoolsTest") {
    description = "Runs unit tests for the standalone tlmnhdevtools source set"
    group = "verification"
    testClassesDirs = devtoolsTestSourceSet.output.classesDirs
    classpath = devtoolsTestSourceSet.runtimeClasspath
    useJUnit()
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
    testClassesDirs = testSourceSet.output.classesDirs + clientTestSourceSet.output.classesDirs + devtoolsTestSourceSet.output.classesDirs
    classpath = (testSourceSet.runtimeClasspath + clientTestSourceSet.runtimeClasspath + devtoolsSourceSet.output + devtoolsTestSourceSet.output)
        .filter { !it.name.startsWith("gson-") } + gsonClientRuntime
    include("**/BedrockGeometryTest.class", "**/GeckoGeometryTest.class", "**/BuiltinMaidModelsTest.class", "**/BedrockPoseTest.class", "**/BuiltinBedrockAnimationsTest.class", "**/GeckoAnimationTest.class", "**/GeckoControllerTest.class", "**/BuiltinGeckoPlayerTest.class", "**/ClientDevelopmentRequestTest.class", "**/RenderSubmissionTraceTest.class", "**/BuiltinModelSelectionTest.class", "**/BuiltinModelNamesTest.class")
    useJUnit()
}
tasks.named("check") {
    dependsOn("jar", "verifyArchitectureBoundaries", ":kernel:molang:test", clientTest, compatTest, devtoolsTest, testClientGson, verifyBuiltinSounds, verifyBuiltinRetention, verifyProjectLicense)
}
