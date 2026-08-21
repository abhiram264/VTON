# AI Fashion Vault

Canonical repository slug: `ai-fashion-vault`.

This repository is private and proprietary. No open-source license is granted.

`VTON` is a local working-folder name only. It is not the repository identity.

## Repository boundary

This is a single monorepo. Mobile, backend, infrastructure definitions, documentation, and repository automation live in one private repository.

There is no root JavaScript package, workspace, Nx, Turborepo, or Bazel configuration. npm is the recommended package manager for the eventual `mobile/` project. Maven plus Maven Wrapper is the eventual backend-local build tool. Those applications are not created in this slice.

## Phase-zero

This slice commits the Phase-zero baseline only. Generator-owned paths (`mobile/`, `backend/`, `infra/`, `compose.yaml`) are not created here.

```
ai-fashion-vault/
├── docs/
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── API_CONTRACTS.md
│   ├── PRIVACY.md
│   ├── AI_EVALUATION.md
│   └── ADR/
│       └── README.md
├── .github/
│   └── workflows/
│       └── repo-sanity.yml
├── .editorconfig
├── .gitattributes
├── .gitignore
└── README.md
```

Repository sanity is checked by [`.github/workflows/repo-sanity.yml`](.github/workflows/repo-sanity.yml).

## Target topology

Eventual layout after later initialization slices:

```
ai-fashion-vault/
├── mobile/                  # created by Mobile Project Initialization
├── backend/                 # created by Backend Project Initialization
├── infra/                   # populated by later infrastructure slices
├── docs/
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── API_CONTRACTS.md
│   ├── PRIVACY.md
│   ├── AI_EVALUATION.md
│   └── ADR/
├── .github/
│   └── workflows/
├── compose.yaml             # created by Local Development Environment
├── .editorconfig
├── .gitattributes
├── .gitignore
└── README.md
```

Canonical Compose artifact: `compose.yaml` (now present).

## Local Development Environment

Commercial LocalStack entitlement is a local-development prerequisite. Set `LOCALSTACK_AUTH_TOKEN` (never commit a real token). Copy `.env.example` to an untracked `.env`.

The stack is PostgreSQL and LocalStack only (no Redis). Backend and mobile stay on host toolchains; they are not Compose services.

```text
docker compose up -d
docker compose down          # preserves named volumes
docker compose down -v       # explicit destructive reset
```

Port conflicts and parallel worktrees: set `POSTGRES_PORT` and/or `LOCALSTACK_PORT`, and override the Compose project name. Do not edit tracked Compose files for those overrides.

The generation-temp bucket is a storage-domain boundary only; this environment does not enforce 24-hour deletion.

## Documentation

- [docs/PRD.md](docs/PRD.md) — canonical product PRD
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/DATA_MODEL.md](docs/DATA_MODEL.md)
- [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md)
- [docs/PRIVACY.md](docs/PRIVACY.md)
- [docs/AI_EVALUATION.md](docs/AI_EVALUATION.md)
- [docs/ADR/README.md](docs/ADR/README.md) — ADR namespace

`docs/VTON-PRD.md` and `docs/AI_Fashion_Vault_Technical_Implementation_Plan.md` are pre-existing source material that remains in the tree. The canonical product PRD path is `docs/PRD.md`.

## Secrets

Real `.env` files and credentials must not be committed.

## Portability

Use repository-relative paths only. Do not commit machine-specific absolute paths. Line endings and editor policy live in `.gitattributes` and `.editorconfig` so Windows, macOS, and Linux contributors share one contract.

## Application boot

Clean checkout (do not edit tracked Compose files):

1. Copy `.env.example` to an untracked `.env`.
2. Set `LOCALSTACK_AUTH_TOKEN` and `POSTGRES_PASSWORD` in `.env`. Do not add `DB_PASSWORD` to `.env.example`.
3. `docker compose up -d`
4. Verify PostgreSQL and LocalStack readiness.
5. Launch the host-run backend: `python scripts/run_backend.py`

The launcher reads only the repository-root `.env` (the same file Compose uses). `POSTGRES_PORT` and `LOCALSTACK_PORT` overrides apply to both Compose and the derived JDBC/LocalStack endpoints. `LOCALSTACK_AUTH_TOKEN` is infrastructure-only and is not passed to the JVM.

## Next slices

Settled remaining order:

1. Backend Project Initialization
2. Mobile Project Initialization
