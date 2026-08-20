# Architecture Decision Records

This directory is the canonical namespace for Architecture Decision Records (ADRs) for AI Fashion Vault.

## Convention

- One ADR per hard-to-reverse decision.
- Filename: `ADR-NNN-short-title.md` with a three-digit number.
- Each ADR contains: Status, Context, Decision, Alternatives considered, Consequences, Migration strategy.
- Status values: Proposed, Accepted, Superseded, Deprecated.
- Do not open an ADR for reversible implementation details.

Repository Initialization created this convention only. Section 4 architecture ADRs are recorded as the fifteen files listed below.

## Accepted repository-init decisions

These two decisions were accepted during Repository Initialization. They are recorded here so the namespace exists without pre-creating later architecture ADR files.

### Single monorepo

- Status: Accepted
- Context: AI Fashion Vault is one product with coordinated mobile, backend, infrastructure, documentation, privacy, and CI concerns.
- Decision: Use one private repository named `ai-fashion-vault` with stable eventual top-level boundaries for mobile, backend, infrastructure, and documentation.
- Alternatives considered: separate repositories for mobile/backend/infra; service-per-repository; JavaScript-centric monorepo orchestration from the start.
- Consequences: Cross-component changes share one history and review boundary. Each technology retains its local build/package tooling. Repository ownership boundaries must remain clear.
- Migration strategy: Extract a top-level boundary later only if independent organizational ownership, access restrictions, security boundaries, or release governance materially justify a split.

### Do not create generator-owned scaffolds in phase zero

- Status: Accepted
- Context: The eventual tree includes mobile, backend, and Compose artifacts, but those are separate later initialization steps.
- Decision: Repository Initialization documents Target topology but commits only the Phase-zero baseline. It does not manually create React Native, Spring Boot/Maven, or Compose scaffolds.
- Alternatives considered: create every eventual path immediately; create empty placeholder directories; fabricate `package.json` or `pom.xml`; fill future directories with `.gitkeep`.
- Consequences: The first commit accurately represents completed work. Project generators establish authoritative application layouts. Repository verification must be phase-aware rather than assuming the final topology exists immediately.
- Migration strategy: If later scaffolds already exist, classify the repository as Advanced, preserve the work, and verify the repository-init contract around it rather than deleting it.

## Section 4 architecture ADRs

Accepted Section 4 records. ADR-005 replaces the implementation plan’s suggested Redis queue with Amazon SQS in production and LocalStack SQS locally.

- [ADR-001-modular-monolith.md](ADR-001-modular-monolith.md)
- [ADR-002-react-native-native-share-integration.md](ADR-002-react-native-native-share-integration.md)
- [ADR-003-spring-boot-4-java-21.md](ADR-003-spring-boot-4-java-21.md)
- [ADR-004-postgresql-source-of-truth.md](ADR-004-postgresql-source-of-truth.md)
- [ADR-005-durable-generation-work-queue.md](ADR-005-durable-generation-work-queue.md)
- [ADR-006-s3-object-storage.md](ADR-006-s3-object-storage.md)
- [ADR-007-firebase-authentication-with-internal-identity.md](ADR-007-firebase-authentication-with-internal-identity.md)
- [ADR-008-vton-provider-abstraction.md](ADR-008-vton-provider-abstraction.md)
- [ADR-009-persistent-my-model.md](ADR-009-persistent-my-model.md)
- [ADR-010-ephemeral-provider-image-transfer.md](ADR-010-ephemeral-provider-image-transfer.md)
- [ADR-011-deterministic-studio-state.md](ADR-011-deterministic-studio-state.md)
- [ADR-012-retailer-scraping-optional.md](ADR-012-retailer-scraping-optional.md)
- [ADR-013-quality-gate-before-user-exposure.md](ADR-013-quality-gate-before-user-exposure.md)
- [ADR-014-generation-attempt-cost-accounting.md](ADR-014-generation-attempt-cost-accounting.md)
- [ADR-015-raw-images-excluded-from-telemetry.md](ADR-015-raw-images-excluded-from-telemetry.md)
