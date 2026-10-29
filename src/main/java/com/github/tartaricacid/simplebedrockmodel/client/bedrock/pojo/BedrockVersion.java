/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/pojo/BedrockVersion.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo;

import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.InvalidVersionSpecificationException;
import cpw.mods.fml.common.versioning.VersionRange;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public enum BedrockVersion {

    /**
     * 旧版本基岩版模型，仅限 1.10.0
     */
    LEGACY("[1.10.0]"),
    /**
     * 新版本基岩版模型，往后的 1.14.0，1.16.0 1.21.0 通通用此版本读取
     */
    NEW("[1.12.0,)");

    private final VersionRange versionRange;

    BedrockVersion(String version) {
        this.versionRange = createFromVersionSpec(version);
    }

    public static boolean isNewVersion(BedrockModelPOJO bedrockModel) {
        DefaultArtifactVersion inputVersion = new DefaultArtifactVersion(bedrockModel.getFormatVersion());
        return NEW.versionRange.containsVersion(inputVersion);
    }

    public static boolean isLegacyVersion(BedrockModelPOJO bedrockModel) {
        DefaultArtifactVersion inputVersion = new DefaultArtifactVersion(bedrockModel.getFormatVersion());
        return LEGACY.versionRange.containsVersion(inputVersion);
    }

    private static VersionRange createFromVersionSpec(final String spec) {
        try {
            return VersionRange.createFromVersionSpec(spec);
        } catch (InvalidVersionSpecificationException e) {
            TLMNotHard.LOG.fatal("Failed to parse version spec {}", spec, e);
            throw new RuntimeException("Failed to parse spec", e);
        }
    }

    public static BedrockVersion getVersion(BedrockModelPOJO pojo) throws InvalidVersionSpecificationException {
        if (isNewVersion(pojo)) {
            return NEW;
        } else if (isLegacyVersion(pojo)) {
            return LEGACY;
        }
        throw new InvalidVersionSpecificationException("Invalid version for model: " + pojo);
    }
}
