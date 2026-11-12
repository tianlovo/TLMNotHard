# Touhou Little Maid (Not Hard) _(TLMNotHard)_

A Touhou Little Maid port for GTNH 2.9.0, retaining core maid gameplay and rebuilding non-generative behaviour AI

**Preparing 0.1.0**: this page describes the first stable release scope. The current public release is `0.1.0-beta.2`;
final artifacts and acceptance records will be added at publication.

[简体中文](README.md) | English

This repository ports the Minecraft 1.20.1 version of Touhou Little Maid to GTNH 2.9.0,
supporting both the client and the dedicated server.
See the [user guide](docs/USER_GUIDE.en.md) for gameplay, equipment and work modes,
and the [0.1.0 release notes](docs/releases/0.1.0.md) for this release scope.

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
and upstream code comes from [Touhou Little Maid's 1.20
branch](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20).
Sources use modern Java syntax compiled to Java 8 bytecode via Jabel; both client and server run on Java 25.

The first release includes:

- Maid acquisition and initial binding, original player interactions, shrine revival, sleeping and maid beds,
  snack stealing and the minimal snack cabinet, with Steam and LV progression recipes.
- All 36 backpack slots by default, separate hands and armour, 30 favourability-gated bauble slots,
  and priority storage and work-material extraction through five backpack integrations.
- Ordinary GregTech maintenance-hatch patrols, bound bells and range previews, and real-world CropsNH breeding
  with crop or mutation-pool targets, stat purification and missing-material reminders.
- Actual hunger, food and saturation restoration, the Healing Axe, hot-item damage and dropping,
  plus flight equipment, special armour, oxygen, thermal protection and fall rescue.
- Enhanced non-generative behaviour AI by default, unified combat and predictive bow aiming accounting for
  dimension gravity, drag and target movement. Existing explicit Legacy settings remain supported.
- 254 built-in model choices, 21 base wine-fox entries and 14 additional texture choices,
  435 voice files and 10 base sound effects.

External model imports, YSM integration, LLMs and ownership transfer are outside this release.
See [Development progress](PROGRESS.md) for test evidence and outstanding acceptance work.

## Install

Once the stable release is published, download `tlmnh-0.1.0.jar` from
[GitHub Releases](https://github.com/tianlovo/TLMNotHard/releases) and verify it against that release's
`SHA256SUMS.txt`.
Until publication, the available public release remains `0.1.0-beta.2`.
Stop both sides, back up the complete world, configurations and old JARs, then place the identical new JAR in
both `mods/` directories and remove the previous version. Normal play needs the main mod only;
`tlmnhdevtools` is a separate development and verification addon.

See the [user guide](docs/USER_GUIDE.en.md) for controls, server configuration and paired rollback.
New configurations use `enhanced`; existing explicit `legacy_prototype` settings remain effective.
Restart the server after changing its behaviour profile.
Enhanced defaults to 20-block target acquisition and 24-block retention outside home mode,
subject to owner-domain limits, visibility and budgets.
The [overlay packaging guide](scripts/distribution/README.md) covers local verification bundles.

Build from source in Windows PowerShell:

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0"
./gradlew.bat build
```

The main mod is written to `build/libs/`; the separate development addon is written to `build/devtools/`.
Use the same build artifact on the client and server.

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
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0.jar
npx --yes markdownlint-cli2@0.24.0
```

`build` runs `:kernel:molang:test`, `test`, `clientTest`, `compatTest`, `devtoolsTest`, `testClientGson` and the
architecture boundary checks. Versions are derived from Git tags and commits unless `VERSION` is set explicitly;
release milestones use tags.

Open the source root in IntelliJ IDEA as a Gradle project and delegate builds and tests to Gradle.
Select Java 25 for the project SDK and Gradle JVM, language level 17 and target bytecode 8.
Local 2.9.0 test server and client run configurations are documented in [IDEA setup](DEVELOPMENT.md#intellij-idea-本机配置).

## Development status

`0.1.0` extends the Beta.2 source-set architecture with the complete inventory, baubles, two work modes
and survival/equipment integrations. Final statistical work, combined scenarios, two-sided networking and player
acceptance remain to be completed before publication, alongside final commit, artifact and CI records.
See [Development progress](PROGRESS.md) and the [release notes](docs/releases/0.1.0.md) for the current public version,
accepted cases and preserved failures.

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
Voice packs and base sound effects retain their respective authors' terms; see [Third-party
notices](THIRD_PARTY_NOTICES.md)
and the [licence directory](licenses/) for scope, rights holders and full texts.

Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>).
LittleMaid_PEKO/PECO: CV 秋野かえで, Tamemaru (<https://booth.pm/ja/items/1903163>).
