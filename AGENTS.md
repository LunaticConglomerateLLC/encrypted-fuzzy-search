# Contribution guidance

## Commit and pull-request titles

- Use [Conventional Commits](https://www.conventionalcommits.org/) for every commit message and pull-request title. Release Please uses these messages to determine releases and changelog entries.
- Use the `type(scope): description` form where a scope is useful, for example `feat(search): add bounded deletion variants` or `test(index): cover namespace isolation`.
- Use an appropriate conventional type such as `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, `perf`, or `chore`. Mark breaking changes with `!` and document them with a `BREAKING CHANGE:` footer.
- Ensure the final pull-request title remains conventional, including when the branch will be squash-merged.
