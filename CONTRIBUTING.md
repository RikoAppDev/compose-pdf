# Contributing to compose-pdf

Thanks for your interest in improving compose-pdf! This document explains how to contribute and,
just as importantly, the invariants that keep the library correct. Please read the
[Code of Conduct](CODE_OF_CONDUCT.md) first.

## How to contribute

1. **Open an issue first** for anything non-trivial (a bug report or a feature proposal) so the
   direction can be agreed before you spend time on a PR.
2. **Fork** the repository and create a topic branch (`feature/…` or `fix/…`).
3. Make your change with tests, then open a **pull request** against `main`.
4. CI must be green and a maintainer (code owner) must approve before a PR can be merged. Direct
   pushes to `main` are not accepted from contributors.

## The non-negotiable invariants

compose-pdf's whole reason to exist is producing **identical output on every platform** with
**selectable vector text**. A change that weakens any of these will be rejected:

1. **Integer-only layout.** All layout, shaping and measurement math must stay integer (font units
   / PDF points). No floating point in the layout/serialization hot path — floats are not bit-identical
   across JVM and Kotlin/Native and would break cross-platform identity.
2. **Pure shared engine.** Everything in `commonMain` must compile as common metadata
   (`./gradlew :composepdf:compileCommonMainKotlinMetadata`). Do not call `java.*` or any
   platform-specific API from `commonMain`.
3. **The cross-platform golden must pass.** `GoldenLayoutTest` asserts exact glyph origins and runs
   on JVM and natively on iOS. If you intentionally change layout, regenerate the golden, **explain
   why in the PR**, and make sure it still passes on every platform.
4. **No real-world data.** Tests, samples, comments and docs use invented English dummy data only
   (e.g. `ACME Inc.`). No real companies, people, or third-party product names.

## Running the checks locally

```
./gradlew :composepdf:jvmTest                          # identity + feature gates + golden
./gradlew :composepdf:compileCommonMainKotlinMetadata  # shared-code purity
./gradlew :composepdf:compileAndroidMain               # Android target
./gradlew :composepdf:iosSimulatorArm64Test            # golden on iOS (requires macOS)
```

Requires JDK 17+ (CI uses 21). On Windows the Apple targets can't be built locally — rely on CI for
the iOS job.

## Style & commits

- Kotlin official code style; match the surrounding code's naming, structure and comment density.
- Keep public API small and documented with KDoc.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for commit and PR titles
  (`feat:`, `fix:`, `docs:`, `chore:`, `ci:`, `refactor:`, `test:`).

## License of contributions

By contributing you agree that your contributions are licensed under the project's
[Apache-2.0 License](LICENSE).
