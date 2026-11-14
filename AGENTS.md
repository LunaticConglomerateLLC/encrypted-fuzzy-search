# Contribution guidance

## Commit and pull-request titles

- Use [Conventional Commits](https://www.conventionalcommits.org/) for every commit message and pull-request title. Release Please uses these messages to determine releases and changelog entries.
- Use the `type(scope): description` form where a scope is useful, for example `feat(search): add bounded deletion variants` or `test(index): cover namespace isolation`.
- Use an appropriate conventional type such as `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, `perf`, or `chore`. Mark breaking changes with `!` and document them with a `BREAKING CHANGE:` footer.
- Ensure the final pull-request title remains conventional, including when the branch will be squash-merged.

## Documentation

- Write project documentation for application developers and other human readers. Do not write user-facing pages as agent instructions, release notes, or backlog summaries.
- Explain the product's purpose and boundaries, supported behavior, security and leakage limits, setup, integration, examples, operations, benchmarks, and how to interpret performance results. Be explicit when a feature is experimental, opt-in, unsupported, or outside the production allowlist.
- Verify API names, commands, configuration, compatibility claims, and security statements against the current source, tests, and accepted architecture decisions. Do not imply guarantees the implementation does not provide.
- Do not include backlog task IDs or internal planning references in repository documentation.
- Keep documentation examples runnable or covered by CI. Build the MkDocs site with strict validation in CI; broken navigation and internal links must fail the build.
- Before finalizing human-facing prose, review it with the available `humanizer` skill and revise generic or artificial-sounding language while preserving technical accuracy and a neutral reference tone.
- The published project site belongs under `https://wearelunatic.com/encrypted-fuzzy-search/`. Preserve the organization home site's ownership of the root domain.
