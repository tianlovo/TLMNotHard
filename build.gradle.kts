
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

val gsonRc2Client = configurations.create("gsonRc2Client") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(gsonRc2Client.name, "com.google.code.gson:gson:2.10.1")
}

val testRc2ClientGson = tasks.register<Test>("testRc2ClientGson") {
    description = "Runs model and development protocol checks with the Gson version used by the RC2 client"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("gson-") } + gsonRc2Client
    include("**/BedrockGeometryTest.class", "**/GeckoGeometryTest.class", "**/BuiltinMaidModelsTest.class", "**/BedrockPoseTest.class", "**/BuiltinBedrockAnimationsTest.class", "**/GeckoAnimationTest.class", "**/GeckoControllerTest.class", "**/BuiltinGeckoPlayerTest.class", "**/ClientDevelopmentRequestTest.class")
    useJUnit()
}
tasks.named("check") {
    dependsOn(testRc2ClientGson)
}
