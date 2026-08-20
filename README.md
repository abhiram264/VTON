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

The intended future Compose filename is `compose.yaml`.

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

Application boot commands do not exist yet. This slice is repository initialization, not application scaffolding.

## Next slices

Settled order:

1. Backend Project Initialization
2. Mobile Project Initialization
3. Local Development Environment
