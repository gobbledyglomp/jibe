# Contributing

## Branching

`main` is always stable and represents the latest release-ready state. Never commit directly to `main`.

Use feature branches with standard prefixes:

| Prefix | Purpose |
|--------|---------|
| `feat/` | New feature |
| `fix/` | Bug fix |
| `refactor/` | Code restructure without behaviour change |
| `test/` | Tests only |
| `docs/` | Documentation only |
| `chore/` | Tooling, CI, deps, repo hygiene |
| `perf/` | Performance improvement |

Branch names should be lowercase with hyphens: `feat/clipboard-history`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
type(scope): short imperative description

Optional body explaining the why, not the what.
```

- **type**: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `style`, `ci`
- **scope**: `daemon`, `android`, `web`, `config`, `tls`, `auth`, `transfer`, `docker`, etc.
- **description**: lowercase, imperative mood, no trailing period, max ~72 chars

Examples:
```
feat(daemon): add clipboard history retention
fix(android): handle NSD service loss during pairing
chore(deps): bump aiohttp to 3.12
```

## Changelog

This project uses `git log` as its changelog — no separate `CHANGELOG.md` file. Tag messages serve as release summaries.

## Pull requests

1. Branch from `main`.
2. Keep PRs small and focused on one logical change.
3. All commits within a branch should tell a coherent story.
4. Merge with `--no-ff` to preserve branch history.
5. Delete the branch after merging.

## Code style

### Python (daemon)

- Follow [PEP 8](https://peps.python.org/pep-0008/).
- Type-annotate all public functions and class attributes.
- No magic numbers — extract to named constants in `core/config.py`.
- No silent exception swallowing — use explicit error handling with descriptive messages.
- Keep functions small and single-purpose.
- Run `ruff` or `flake8` before pushing.

### Kotlin (Android)

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Jetpack Compose: one composable per file for screen-level components.
- State hoisting: keep `@Composable` functions stateless where practical.
- Use `sealed class` for all UI state representations.

## Testing

### Daemon

```bash
cd daemon
source .venv/bin/activate
pytest tests/ -v
```

All new handlers must have a corresponding test in `tests/`.

### Android

```bash
cd android
./gradlew test                    # unit tests
./gradlew connectedAndroidTest    # instrumentation (device required)
```

## Licensing

By contributing, you agree that your contributions will be licensed under the GNU General Public License v3.0.
