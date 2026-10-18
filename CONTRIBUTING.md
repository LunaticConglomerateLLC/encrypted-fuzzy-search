# Contributing

This project is building a reusable Java and Kotlin library. Keep the core independent of application frameworks, and keep adapter-specific behavior in adapter modules.

## Changes

- Include focused tests with every behavior change.
- Maintain at least 90% line coverage per module containing production sources; CI enforces the gate.
- Tests use TestNG. Keep randomized/property-style tests reproducible by using explicit fixed seeds and reporting the seed when a generated case fails.
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

CI runs `./gradlew clean check` on JDK 21 and 25. `check` includes formatting, compiler static warnings, tests, and a per-module 90% JaCoCo line-coverage gate for modules with production sources. Coverage reports are generated under each module's `build/reports/jacoco/test/` directory. New or updated dependencies are reviewed for vulnerabilities and allowed licenses; the scheduled dependency audit also generates a CycloneDX SBOM.

### Release process

Google release-please reads Conventional Commits on `main` and opens a release pull request that updates the changelog, manifest, and root Gradle version. Review and merge that pull request to publish the version tag and GitHub release. The workflow uses GitHub Actions' built-in `GITHUB_TOKEN` (available as `github.token`), so no separately configured token secret is required. Because events created with this token do not normally trigger other workflows, checks on the release pull request may need manual approval and downstream workflows will not run automatically. If unattended CI on release pull requests is required, configure a GitHub App token or personal access token with the required repository permissions and use it instead. Release failures are reported by Actions and require fixing the workflow/configuration before retrying.

## Dependencies

Read [DEPENDENCIES.md](DEPENDENCIES.md) before adding a dependency. New direct and transitive dependencies must be open source, pass the project license policy, and be reviewed for security and maintenance risk.
