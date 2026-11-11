# Touhou Little Maid (Not Hard) _(TLMNotHard)_

A Touhou Little Maid port for GTNH 2.9.0, retaining core maid gameplay and rebuilding non-generative behaviour AI

**Beta**: the current public test release is `0.1.0-beta.2` (`0.1.0-beta.1` remains archived). Work continues towards `0.1.0`.

[简体中文](README.md) | English

This repository ports the Minecraft 1.20.1 version of Touhou Little Maid to GTNH 2.9.0,
supporting both the client and the dedicated server.
This page covers the current Beta scope, build commands and contribution rules;
complete stable-release installation and upgrade guides will be finalised before `0.1.0`.

## Table of Contents

- [Background](#background)
- [Install](#install)
- [Usage](#usage)
- [Development status](#development-status)
- [Maintainers](#maintainers)
- [Contributing](#contributing)
- [License](#license)

## Background

The build is based on [GTNH ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10),
and upstream code comes from [Touhou Little Maid's 1.20 branch](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20).
Sources use modern Java syntax compiled to Java 8 bytecode via Jabel; both client and server run on Java 25.

The first stable release is planned around the capabilities below; see [Development progress](PROGRESS.md) for current status:

- Core maid gameplay, original player interactions, six-slot backpack and dual-hand equipment, built-in model switching,
  and item-by-item acceptance checks for the full wine-fox series
- Configurable natural spawning and an initial owner-bound soul spell, plus shrine revival with the upstream health cost
- Snack stealing with a minimal snack cabinet, sleeping with a maid bed, and progression recipes tailored to GTNH
  Steam and LV tiers
- Rebuilt non-generative behaviour AI covering perception, memory, command constraints, preemption, modular session
  extensions and failure recovery, benchmarked against upstream TLM 1.20.1
- Predictive aiming for non-magical ranged weapons, starting with bows and compensating for target motion, dimension
  gravity and fluid drag in 2.9.0

The first release excludes LLMs, jobs beyond idle and combat, other non-core TLM items, ownership transfer,
and deep content integrations with mods such as YSM.
GTNH maintenance hatches and CropsNH breeding are left for future extensions.
Simplified Chinese is primary, with English support.

## Install

Download `tlmnh-0.1.0-beta.2.jar` from
[GitHub Releases](https://github.com/tianlovo/TLMNotHard/releases/tag/0.1.0-beta.2)
and verify it against `SHA256SUMS.txt`.
Stop both client and server cleanly, back up the full world, configurations and previous JARs,
then place the identical new JAR into `mods/` on both sides and remove the old JAR.
See the [Beta.2 release notes](docs/releases/0.1.0-beta.2.md) for architecture changes, known issues and remaining work.
See the [user guide](docs/USER_GUIDE.en.md) for controls, configuration options and paired world rollback.
Fresh configurations default to Enhanced; existing explicit Legacy settings are preserved,
and changing the profile requires a server restart.
Enhanced uses dynamic target detection with default non-home acquisition and retention radii of 20 and 24 blocks.
The [overlay packaging and update guide](scripts/distribution/README.md) documents local-validation-only bundles.
To build from source in Windows PowerShell:

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0-beta.2"
./gradlew.bat build
```

Release mod archives are written to `build/libs/`, while the standalone development diagnostics addon is written to
`build/devtools/` (`tlmnhdevtools` is not needed for normal play).
Deploy the exact same candidate JAR on the dedicated 2.9.0 test server and client,
and keep regular-play Prism instances untouched until stable release acceptance is complete.

### Dependencies

- Target modpack: GTNH 2.9.0, Minecraft 1.7.10, Forge 10.13.4.1614
- UniMixins as bundled in 2.9.0, providing Mixin bootstrap for navigation stop feedback and arrow provenance hooks
- JDK 25 for Gradle, tests and the game; JDK 17 for Jabel compilation targeting Java 8 bytecode
- Gradle Wrapper 9.7.1, GTNHGradle 2.0.33 and Blowdryer 0.2.2, pinned in the repository
- Python 3 for bytecode, built-in asset and repository path checks; Node.js 22 or newer for README linting

See [Development notes](DEVELOPMENT.md) (in Chinese) for toolchain setup, source-set layout and local caches.

## Usage

Run unit tests, full builds and archive checks from the source root:

```powershell
./gradlew.bat test
./gradlew.bat build
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0-beta.2.jar
npx --yes markdownlint-cli2@0.24.0
```

`build` runs `:kernel:molang:test`, `test`, `clientTest`, `compatTest`, `devtoolsTest`, `testClientGson` and the
architecture boundary checks. Versions are derived from Git tags and commits unless `VERSION` is set explicitly;
release milestones use tags.

Open the source root in IntelliJ IDEA as a Gradle project and delegate builds and tests to Gradle.
Select Java 25 for the project SDK and Gradle JVM, language level 17 and target bytecode 8.
Local 2.9.0 test server and client run configurations are documented in [IDEA setup](DEVELOPMENT.md#intellij-idea-本机配置).

## Development status

The project is in public Beta. `0.1.0-beta.2` completes the multi-source-set architecture refactoring (`:kernel:molang`,
isolated `client` and `compat` source sets, and the standalone `tlmnhdevtools` addon), while remaining survival/equipment
adaptations and final acceptance continue towards `0.1.0`.
Module progress, native test evidence and open issues are tracked in [Development progress](PROGRESS.md),
bound to exact source commits and artifact digests.

See [Development notes](DEVELOPMENT.md), [Source provenance](SOURCE_PROVENANCE.md) and the
[animation comparison experiment](tools/animation-reference-1.20.1/DESIGN.md) for further technical context.

## Maintainers

[tianlovo](https://github.com/tianlovo), email: <tianluoqaq@gmail.com>

## Contributing

Bug reports and feature ideas are welcome in [Issues](https://github.com/tianlovo/TLMNotHard/issues);
PRs are welcome as well.
For substantial gameplay or architectural changes, please open an issue first to align on scope,
reproduction steps and 2.9.0 setup.

Place new standalone code under `com.tianluoqaq.tlmnh`, keep optional third-party mod adapters inside `src/compat` or
`src/compatClient` behind domain SPIs, and preserve original packages, headers and licences on ported upstream files.
Use Conventional Commits in Simplified Chinese with clear, natural phrasing: Chinese code comments omit only the final
full stop on a line, while earlier complete sentences on the same line and documentation prose use normal full stops.
Run `build`, the repository path check and README linting before submitting,
keeping both READMEs aligned in scope and commands.
Update `PROGRESS.md` in the same batch when implementation or verification status changes; keep IDE files,
modpack directories, saves, logs and caches out of version control.

## License

Original adaptation code for which tianlovo and contributors hold licensing rights is licensed under
GNU GPL version 3 only (`GPL-3.0-only`).
Copyright (c) 2026 tianlovo and contributors

See [LICENSE](LICENSE) for the full text.
Existing source, author and file-level licence notices remain in effect.
Ported upstream code retains MIT and other original notices; models, textures, animations and related assets retain
CC BY-NC-SA 4.0 and original author attribution.
`BuiltinBedrockAnimations.java`, translated from original asset scripts, remains under CC BY-NC-SA 4.0.
Voice packs and base sound effects retain their respective authors' terms; see [Third-party notices](THIRD_PARTY_NOTICES.md)
and the [licence directory](licenses/) for scope, rights holders and full texts.
