# Contributing

This project is a reusable Java and Kotlin library. Keep the core independent of application frameworks, and put storage or framework integrations in their own modules.

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

Google release-please reads Conventional Commits on `main` and opens a release pull request that updates the changelog, manifest, and root Gradle version. Review and merge that pull request to publish the `vX.Y.Z` tag and GitHub release. The workflow uses GitHub Actions' built-in `GITHUB_TOKEN` (available as `github.token`), so no separately configured token secret is required. Because events created with this token do not normally trigger other workflows, checks on the release pull request may need manual approval and downstream workflows will not run automatically. If unattended CI on release pull requests is required, configure a GitHub App token or personal access token with the required repository permissions and use it instead. Release failures are reported by Actions and require fixing the workflow/configuration before retrying.

### Documentation site

Pull requests build the MkDocs site in strict mode, so broken navigation and links fail CI. After a change reaches `main`, the documentation workflow measures the merged revision, adds the benchmark reports, builds the site, and deploys it to [wearelunatic.com/encrypted-fuzzy-search](https://wearelunatic.com/encrypted-fuzzy-search/).

For a new repository or a recreated Pages setup, open **Settings → Pages** and set **Build and deployment → Source** to **GitHub Actions**. The workflow deploys this project's site under `/encrypted-fuzzy-search/`; it does not publish the organization home page or own the root domain. The root domain remains managed by the organization website repository.

## Dependencies

Read [DEPENDENCIES.md](DEPENDENCIES.md) before adding a dependency. New direct and transitive dependencies must be open source, pass the project license policy, and be reviewed for security and maintenance risk.
