package com.tianluoqaq.tlmnh.buildlogic;

import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/** 纯 Java 内核子项目约定：JDK 17 + Jabel 编译为 Java 8 字节码，Java 25 运行单测 */
public abstract class JabelJavaConventionsPlugin implements Plugin<Project> {

    private static final String JABEL = "com.github.GTNewHorizons:jabel-javac-plugin:1.0.2-GTNH";
    private static final String JNA_PLATFORM = "net.java.dev.jna:jna-platform:5.19.1";

    @Inject
    protected abstract JavaToolchainService getJavaToolchains();

    @Override
    public void apply(Project project) {
        project.getPluginManager()
            .apply(JavaLibraryPlugin.class);

        project.getRepositories()
            .maven(repo -> {
                repo.setName("GTNH Maven");
                repo.setUrl("https://nexus.gtnewhorizons.com/repository/public/");
            });
        project.getRepositories()
            .mavenCentral();

        JavaPluginExtension java = project.getExtensions()
            .getByType(JavaPluginExtension.class);
        java.getToolchain()
            .getLanguageVersion()
            .set(JavaLanguageVersion.of(17));

        var compiler17 = getJavaToolchains().compilerFor(
            spec -> spec.getLanguageVersion()
                .set(JavaLanguageVersion.of(17)));
        project.getTasks()
            .withType(JavaCompile.class)
            .configureEach(task -> {
                task.getOptions()
                    .setEncoding(StandardCharsets.UTF_8.name());
                task.setSourceCompatibility("17");
                task.getOptions()
                    .getRelease()
                    .set(8);
                task.getJavaCompiler()
                    .set(compiler17);
            });

        DependencyHandler deps = project.getDependencies();
        deps.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, JABEL);
        deps.add(JavaPlugin.TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME, JABEL);
        ((ModuleDependency) deps.add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, JABEL)).setTransitive(false);
        deps.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, JNA_PLATFORM);
        deps.add(JavaPlugin.TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME, JNA_PLATFORM);

        project.getTasks()
            .withType(Jar.class)
            .configureEach(jar -> {
                jar.setPreserveFileTimestamps(false);
                jar.setReproducibleFileOrder(true);
            });

        var launcher25 = getJavaToolchains().launcherFor(
            spec -> spec.getLanguageVersion()
                .set(JavaLanguageVersion.of(25)));
        project.getTasks()
            .withType(Test.class)
            .configureEach(test -> {
                test.getJavaLauncher()
                    .set(launcher25);
                test.useJUnit();
                test.setWorkingDir(
                    project.getLayout()
                        .getBuildDirectory()
                        .dir("test-runtime/" + test.getName())
                        .get()
                        .getAsFile());
                test.doFirst(
                    t -> ((Test) t).getWorkingDir()
                        .mkdirs());
            });
    }
}
