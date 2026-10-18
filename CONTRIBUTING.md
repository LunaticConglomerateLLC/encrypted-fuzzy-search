# Contributing

This project is building a reusable Java and Kotlin library. Keep the core independent of application frameworks, and keep adapter-specific behavior in adapter modules.

## Changes

- Include focused tests with every behavior change.
- Maintain the project target of at least 90% line coverage; CI will enforce it once the test and coverage configuration is in place.
- Never add plaintext field values, normalized search values, raw grams, protected tokens, or secrets to logs or committed fixtures.
- Document changes to cryptographic formats, normalization behavior, search guarantees, and leakage.
- Keep public APIs compatible across Java and Kotlin callers.

## Commit messages and releases

Use Conventional Commits, for example:

```text
feat(search-core): add bounded candidate retrieval
fix(crypto-core): reject malformed ciphertext headers
docs: clarify index leakage
```

Release-please will use these commits to propose version and changelog updates. Do not edit generated release notes or version files by hand unless the release workflow specifically requires it.

## Dependencies

Read [DEPENDENCIES.md](DEPENDENCIES.md) before adding a dependency. New direct and transitive dependencies must be open source, pass the project license policy, and be reviewed for security and maintenance risk.
