# AI Fashion Vault

Canonical repository slug: `ai-fashion-vault`.

This repository is private and proprietary. No open-source license is granted.

`VTON` is a local working-folder name only. It is not the repository identity.

## Repository boundary

This is a single monorepo. Mobile, backend, infrastructure definitions, documentation, and repository automation live in one private repository.

There is no root JavaScript package, workspace, Nx, Turborepo, or Bazel configuration. `mobile/` is a standalone npm project. Maven plus Maven Wrapper builds `backend/`.

## Repository topology

Repository Initialization defined the `Target topology` below; every path in it now exists.

```
ai-fashion-vault/
├── mobile/                  # React Native app (npm project: android/, ios/, src/, e2e/)
├── backend/                 # Spring Boot service (mvnw, mvnw.cmd, src/, pom.xml)
├── infra/                   # Terraform (storage, queue, IAM, networking, observability) and alert definitions, plus LocalStack init hooks
├── scripts/                 # run_backend.py, repository/backend/mobile init generators, test_*.py
├── docs/
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── API_CONTRACTS.md
│   ├── PRIVACY.md
│   ├── AI_EVALUATION.md
│   ├── DATABASE_DEPLOYMENT_STRATEGY.md
│   ├── AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md
│   ├── runbooks/
│   └── ADR/
├── .github/
│   └── workflows/
├── compose.yaml
├── .env.example
├── .editorconfig
├── .gitattributes
└── .gitignore
```

Repository sanity is checked by [`.github/workflows/repo-sanity.yml`](.github/workflows/repo-sanity.yml). The environment, backend, mobile, and security (Trivy) workflows live alongside it.

## Stack

- **Backend** — Spring Boot 4.1.0 on Java 21, Spring MVC with Spring JDBC (no JPA), Flyway migrations `V001`–`V023`, PostgreSQL 18 as the source of truth.
- **Queues and storage** — AWS SQS and S3 through the AWS SDK v2. Locally both are emulated by LocalStack (`s3,sqs`).
- **Mobile** — React Native 0.86.2 / React 19.2.3, npm, Node `^22.13.0 || >=24`.

## Local environment

Commercial LocalStack entitlement is a local-development prerequisite. Set `LOCALSTACK_AUTH_TOKEN` (never commit a real token). Copy `.env.example` to an untracked `.env` and set `LOCALSTACK_AUTH_TOKEN` and `POSTGRES_PASSWORD`; Compose refuses to start without them.

The stack is PostgreSQL and LocalStack only. Backend and mobile stay on host toolchains; they are not Compose services.

```text
docker compose up -d
docker compose down          # preserves named volumes
docker compose down -v       # explicit destructive reset
```

Port conflicts and parallel worktrees: set `POSTGRES_PORT` and/or `LOCALSTACK_PORT`, and override the Compose project name. Do not edit tracked Compose files for those overrides.

The generation-temp bucket is a storage-domain boundary with scheduled deletion: the backend retention sweep runs hourly with a PT24H max age (application.yaml:22-23), and LocalStack installs a 1-day lifecycle expiration on the temp bucket. S3 lifecycle timing is not a precise 24-hour erasure guarantee.

## Application boot

Clean checkout (do not edit tracked Compose files):

1. Copy `.env.example` to an untracked `.env`.
2. Set `LOCALSTACK_AUTH_TOKEN` and `POSTGRES_PASSWORD` in `.env`. Do not add `DB_PASSWORD` to `.env.example`. The backend additionally requires `FASHN_API_KEY` and `FIREBASE_PROJECT_ID` at startup (VaultEnvironmentValidator rejects blanks); set real or placeholder values in `.env` for local runs.
3. `docker compose up -d`
4. Verify PostgreSQL and LocalStack readiness.
5. Launch the host-run backend: `python scripts/run_backend.py`

The launcher reads only the repository-root `.env` (the same file Compose uses), requires `LOCALSTACK_AUTH_TOKEN` and `POSTGRES_PASSWORD`, and derives the JDBC URL, the S3/SQS endpoints, and the generation queue URL from it (bucket names are fixed local constants, not .env keys) before running the backend's Maven Wrapper `spring-boot:run` goal. `POSTGRES_PORT` and `LOCALSTACK_PORT` overrides apply to both Compose and the derived JDBC/LocalStack endpoints. `LOCALSTACK_AUTH_TOKEN` is infrastructure-only and is not passed to the JVM.

The mobile app runs on host Node from `mobile/`: `npm ci`, then `npm start` (Metro) plus `npm run android` or `npm run ios`.

## Validation

Backend. Docker is required: the suite starts `postgres:18` Testcontainers, so a missing Docker daemon fails the build rather than skipping it.

```text
backend\mvnw.cmd -f backend\pom.xml verify     # Windows
./backend/mvnw -f backend/pom.xml verify       # macOS/Linux
```

186 test classes; roughly 3–7 min on this host (host-pace varies; see docs/BASELINE.md for the recorded runs).

Mobile, from `mobile/`:

```text
npm ci
npm run typecheck
npm run lint
npm run test:ci        # 17 suites / 243 tests
```

CI additionally runs `npm run format:check`.

Repository scripts:

```text
python scripts/test_repo_init.py
python scripts/test_local_dev_init.py
```

CI additionally runs `python scripts/test_environment_contract.py`, `python scripts/secret_content_scan.py`, and `python scripts/test_localstack_resources.py` (the last requires a real `LOCALSTACK_AUTH_TOKEN`).

## Documentation

- [docs/PRD.md](docs/PRD.md) — canonical product PRD
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/DATA_MODEL.md](docs/DATA_MODEL.md)
- [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md)
- [docs/PRIVACY.md](docs/PRIVACY.md)
- [docs/CONTRACT_INVENTORY.md](docs/CONTRACT_INVENTORY.md) — frozen contract inventory (routes, stores, config, findings)
- [docs/BASELINE.md](docs/BASELINE.md) — baseline gate evidence and owning tickets
- [docs/AI_EVALUATION.md](docs/AI_EVALUATION.md)
- [docs/DATABASE_DEPLOYMENT_STRATEGY.md](docs/DATABASE_DEPLOYMENT_STRATEGY.md)
- [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md) — MVP production-readiness PR backlog (PR-01 … PR-26)
- [docs/ADR/README.md](docs/ADR/README.md) — ADR namespace (ADR-001 … ADR-016)
- [docs/runbooks/README.md](docs/runbooks/README.md) — operations runbooks

`docs/VTON-PRD.md` and `docs/AI_Fashion_Vault_Technical_Implementation_Plan.md` are pre-existing source material that remains in the tree. The canonical product PRD path is `docs/PRD.md`.

## Remaining work

Tracked as PR-01 … PR-26 in [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md).

## Secrets

Real `.env` files and credentials must not be committed.

## Portability

Use repository-relative paths only. Do not commit machine-specific absolute paths. Line endings and editor policy live in `.gitattributes` and `.editorconfig` so Windows, macOS, and Linux contributors share one contract.
