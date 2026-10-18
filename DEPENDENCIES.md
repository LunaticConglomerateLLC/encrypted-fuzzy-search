# Dependency and license policy

The project is licensed under Apache-2.0. It is a permissive license for reuse and distribution, and includes a contributor patent grant that is useful for a cryptography-focused library. Keep runtime, test, build, and release dependencies open source and compatible with that distribution model.

## Default allowlist

Dependencies using Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, or EPL-2.0 may be used after checking the exact license and version. EPL-2.0 is permitted for build and test tooling as well as runtime dependencies.

Do not add proprietary, commercial-only, source-available, or non-commercial dependencies. Licenses outside the allowlist, including copyleft licenses, require explicit review before adoption. Apply the same review to transitive dependencies.

## Review expectations

- Record the purpose of each new runtime dependency and prefer the JDK or existing modules when practical.
- Review maintenance activity, known vulnerabilities, transitive dependencies, and license metadata.
- Keep dependency versions reproducible and run vulnerability and license checks in CI.
- Generate an SBOM when supported by the chosen CI tooling.
- Pull requests are checked against the SPDX allowlist with GitHub's dependency review. GitHub may report a dependency with unknown license metadata without failing the check; manually verify unknown or ambiguous licenses before merge. Build plugins and workflow actions also require manual license/version review because dependency review may not enumerate them as project dependencies.
- A weekly OWASP Dependency-Check workflow scans resolved project dependencies and publishes its report with a CycloneDX SBOM. Configure the optional `NVD_API_KEY` Actions secret to improve NVD update throughput; audit reports fail on CVSS 7.0 or higher.
