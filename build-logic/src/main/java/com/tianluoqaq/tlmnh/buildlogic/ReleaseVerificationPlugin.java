package com.tianluoqaq.tlmnh.buildlogic;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.language.jvm.tasks.ProcessResources;

/** 注册根项目资源、许可证、保留资产与架构边界校验任务 */
public class ReleaseVerificationPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        File licenseFile = project.file("LICENSE");
        File mcmodInfoFile = project.file("src/main/resources/mcmod.info");
        File thirdPartyFile = project.file("THIRD_PARTY_NOTICES.md");
        File resourcesDir = project.file("src/main/resources");
        File licensesDir = project.file("licenses");

        var verifyProjectLicense = project.getTasks()
            .register("verifyProjectLicense", task -> {
                task.setGroup("verification");
                task.setDescription("Verifies the complete project license and matching original-code metadata");
                task.getInputs()
                    .files(licenseFile, mcmodInfoFile, thirdPartyFile);
                task.doLast(ignored -> {
                    try {
                        ReleaseArchiveChecks.validateProjectLicense(
                            Files.readAllBytes(licenseFile.toPath()),
                            Files.readAllBytes(mcmodInfoFile.toPath()),
                            Files.readAllBytes(thirdPartyFile.toPath()),
                            "source");
                    } catch (Exception failure) {
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new GradleException("Failed to read source license files", failure);
                    }
                });
            });

        var verifyBuiltinSounds = project.getTasks()
            .register("verifyBuiltinSounds", task -> {
                task.setGroup("verification");
                task.setDescription(
                    "Verifies all 445 tracked original-byte Ogg resources and their authoritative source manifest");
                task.getInputs()
                    .files(project.fileTree("src/main/resources", tree -> {
                        tree.include("**/*.ogg", "assets/tlmnh/builtin-sounds*.json");
                        for (String ns : Arrays.asList("littlemaid_peco", "touhou_little_maid")) {
                            tree.include(
                                "assets/" + ns + "/lang/*.json",
                                "assets/" + ns + "/maid_sound.json",
                                "assets/" + ns + "/sounds.json");
                        }
                        tree.include(
                            "assets/touhou_little_maid/textures/gui/maid_custom_sound.png",
                            "assets/touhou_little_maid/textures/sound_icon.png");
                    }));
                task.doLast(ignored -> ReleaseArchiveChecks.verifyBuiltinSoundDirectory(resourcesDir));
            });

        var verifyBuiltinRetention = project.getTasks()
            .register("verifyBuiltinRetention", task -> {
                task.setGroup("verification");
                task.setDescription(
                    "Verifies all 332 retained original files and protects the existing fixed model resources");
                task.getInputs()
                    .files(
                        project.fileTree("src/main/resources", tree -> tree.exclude("**/*.ogg")),
                        project.fileTree("licenses"));
                task.doLast(ignored -> ReleaseArchiveChecks.verifyBuiltinRetentionDirectory(resourcesDir, licensesDir));
            });

        project.getTasks()
            .register("verifyArchitectureBoundaries", task -> {
                task.setGroup("verification");
                task.setDescription(
                    "Verifies T0 source-set, public-API self-containment, compat isolation, and client boundary rules");
                task.getInputs()
                    .files(
                        project.fileTree("src/main/java"),
                        project.fileTree("src/client/java"),
                        project.fileTree("src/compat/java"),
                        project.fileTree("src/compatClient/java"));
                task.doLast(ignored -> ArchitectureBoundaryChecks.verifyProjectBoundaries(project.getProjectDir()));
            });

        project.getTasks()
            .named("processResources", ProcessResources.class)
            .configure(task -> {
                task.dependsOn(verifyBuiltinSounds, verifyBuiltinRetention, verifyProjectLicense);
                task.doLast(t -> {
                    File dest = ((ProcessResources) t).getDestinationDir();
                    ReleaseArchiveChecks.verifyBuiltinSoundDirectory(dest);
                    ReleaseArchiveChecks.verifyBuiltinRetentionDirectory(dest, licensesDir);
                });
            });
    }
}
