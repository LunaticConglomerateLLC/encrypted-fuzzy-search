# Dependency and license policy

The project is licensed under Apache-2.0. It is a permissive license for reuse and distribution, and includes a contributor patent grant that is useful for a cryptography-focused library. Keep runtime, test, build, and release dependencies open source and compatible with that distribution model.

## Default allowlist

Dependencies using Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, or 0BSD may be used after checking the exact license and version.

Do not add proprietary, commercial-only, source-available, or non-commercial dependencies. Licenses outside the allowlist, including copyleft licenses, require explicit review before adoption. Apply the same review to transitive dependencies.

## Review expectations

- Record the purpose of each new runtime dependency and prefer the JDK or existing modules when practical.
- Review maintenance activity, known vulnerabilities, transitive dependencies, and license metadata.
- Keep dependency versions reproducible and run vulnerability and license checks in CI.
- Generate an SBOM when supported by the chosen CI tooling.
