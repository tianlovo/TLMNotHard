# Touhou Little Maid (Not Hard) _(TLMNotHard)_

A Touhou Little Maid port for GTNH 2.9.0 RC2, retaining core maid gameplay and improving behaviour AI

**Indev**: under development, with no stable release; current builds are for development and testing

[简体中文](README.md) | English

This project ports the Minecraft 1.20.1 version of Touhou Little Maid to GTNH 2.9.0 RC2,
with support for the client and dedicated server.
This page covers the development scope, progress and build instructions.
Complete gameplay, installation, update instructions and acceptance results will be added before the stable release.

## Table of Contents

- [Background](#background)
- [Install](#install)
- [Usage](#usage)
- [Development status](#development-status)
- [Maintainers](#maintainers)
- [Contributing](#contributing)
- [License](#license)

## Background

The build uses [GTNH ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10).
The upstream source is [Touhou Little Maid's 1.20 branch](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20).
Modern Java syntax is compiled to Java 8 bytecode; both game sides run on Java 25.

The first stable release is planned to include the following; this is a scope list, not a list of completed features:

- Core maid gameplay, original player interactions, inventory and both hands, built-in model switching,
  and individual acceptance checks for the full wine-fox series
- Configurable natural spawning and an initial owner-bound soul spell, shrine revival with the upstream health cost
- Snack stealing with a minimal snack cabinet, sleeping with a maid bed, and recipes fitted to GTNH progression
- Improved non-generative behaviour AI, covering perception, memory, command constraints, interruption and failure recovery,
  benchmarked against the original TLM 1.20.1
- Predictive aiming for non-magical ranged weapons, starting with bows and compensating for target motion and arrow drop
  using RC2's actual gravity and drag

The first release excludes LLMs, jobs beyond idle and combat, other non-core TLM items, ownership transfer,
and integrations with other content mods such as YSM.
GTNH machine maintenance and CropsNH breeding are future extensions. Simplified Chinese is primary, with English support.

## Install

Build from source for now; no stable package for survival play has been released.
Run these commands in Windows PowerShell:

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0-indev.local"
./gradlew.bat build
```

Artifacts are written to `build/libs/`. Use the same candidate on the separate RC2 test client and server.
Release acceptance is incomplete; the Prism instance used for regular play remains untouched until formal distribution.

### Dependencies

- Target pack: GTNH 2.9.0 RC2, Minecraft 1.7.10, Forge 10.13.4.1614
- JDK 25 for Gradle, tests and the game; JDK 17 for Jabel compilation targeting Java 8 bytecode
- Gradle Wrapper 9.7.1, GTNHGradle 2.0.33 and Blowdryer 0.2.2, pinned in the build
- Python 3 for standalone verification scripts; Node.js 22 or newer for README linting

See [Development notes](DEVELOPMENT.md) for toolchains, initial dependency downloads and local caches.
The detailed development notes are currently in Chinese.

## Usage

Run builds and checks from the source root:

```powershell
./gradlew.bat test
./gradlew.bat build
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0-indev.local.jar
npx --yes markdownlint-cli2@0.24.0
```

These commands reuse `VERSION` from the installation example; adjust the JAR name if you choose another version.
By default, versions are derived from Git tags and commits and retain `indev` until the stable release.
Ordinary commits do not receive individual tags.

Open the source root in IntelliJ IDEA as a Gradle project and delegate builds and tests to Gradle.
Use Java 25 for the project SDK and Gradle JVM, language level 17 and target bytecode 8.
The local RC2 test server and client entries are described in [IDEA setup](DEVELOPMENT.md#intellij-idea-本机配置).

## Development status

Completed checks and unfinished work are recorded separately. A successful build does not establish gameplay acceptance.

| Area | Current status |
| --- | --- |
| Build and loading | Java 25 builds and Java 8 bytecode checks pass; the entry point has RC2 loading records on both sides |
| Entity basics | Core state, six inventory slots, both hands, restart persistence and permissions have server fixture checks |
| Basic interaction | Owner GUI and basic taming entry points are present; actual client clicks await acceptance |
| Models and animation | Built-in assets, both geometry paths, Bedrock poses and the Molang expression core are present |
| Unfinished | Gecko controllers, complete built-in model switching, equipment layers, life and combat AI, player interactions |
| Acceptance pending | Wine-fox and other model visuals, animation transitions, Angelica compatibility, multiplayer and gameplay |
| Behaviour AI and aiming | Research and architecture exist; implementation, upstream benchmarks and arrow-physics tests are pending |

Asset hashes, CPU geometry and pose checks do not replace in-game rendering and animation acceptance.
A void world for looping model animations is planned for visual acceptance and has not been implemented.
See [Development notes](DEVELOPMENT.md) and [Source provenance](SOURCE_PROVENANCE.md) for implementation details and origins.

## Maintainers

[tianlovo](https://github.com/tianlovo), email: <tianluoqaq@gmail.com>

## Contributing

Use [Issues](https://github.com/tianlovo/TLMNotHard/issues) for questions and suggestions. PRs are welcome.
Discuss substantial gameplay or architecture changes first, and include reproduction steps, validation and the RC2 setup.

New standalone adaptation files use the `com.tianluoqaq.tlmnh` package root.
Ported upstream files keep their original packages, provenance and applicable licences.
Use Conventional Commits in Simplified Chinese.
Keep new Chinese text natural and precise, without a final Chinese full stop.
Run `build` and README linting before submitting, and keep scope, progress and commands consistent across both READMEs.
IDE settings, modpack files, saves, logs, caches and build artifacts stay outside version control.

## License

The distribution licence for new original code by tianlovo has not yet been selected (`UNLICENSED`).
Ported upstream code retains its respective licences, including MIT.
Models, textures, animations and related assets use CC BY-NC-SA 4.0 with original attribution retained.
See [Third-party notices](THIRD_PARTY_NOTICES.md) and the [licence directory](licenses/) for scope, holders and full texts.
The template or code licence does not describe the licence of the entire mod and its assets.
