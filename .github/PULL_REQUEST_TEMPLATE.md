<!-- Thanks for contributing! Please complete the checklist so reviews stay fast. -->

## What & why

<!-- A short description of the change and the motivation. Link the issue it closes. -->

Closes #

## Checklist

- [ ] `./gradlew :composepdf:jvmTest` passes (identity + feature gates + golden)
- [ ] `./gradlew :composepdf:compileCommonMainKotlinMetadata` passes (no platform APIs in `commonMain`)
- [ ] Layout math stays **integer** — no floating point added to the hot path
- [ ] If layout changed intentionally, the cross-platform golden was regenerated and the change is explained below
- [ ] Tests/samples/docs use English dummy data only (no real companies, people, or third-party names)
- [ ] Public API changes are documented with KDoc
- [ ] Commit/PR title follows Conventional Commits

## Notes for reviewers

<!-- Anything that needs extra attention, e.g. why a golden value changed. -->
