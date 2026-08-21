# ADR-016: Licensed LocalStack for local AWS emulation

## Status

Accepted

## Context

ADR-005 selects Amazon SQS as the production generation-work queue and LocalStack SQS locally. ADR-006 selects S3 as object storage. Local development must emulate those AWS boundaries so storage and durable-work transport stay aligned with production.

A single AWS-compatible emulator is the local substitute for those services. Community-only LocalStack does not cover the project's local-development needs without a commercial entitlement.

## Decision

Use licensed LocalStack as the local AWS emulator for the S3 and SQS boundaries selected by ADR-005 and ADR-006.

The project accepts commercial LocalStack entitlement and `LOCALSTACK_AUTH_TOKEN` as local-development prerequisites. Auth tokens are never committed. Automated runtime CI uses a dedicated CI Auth Token.

Do not pin image tags, ports, Compose filenames, volume names, healthchecks, or Python hook details in this ADR; those are reversible implementation choices.

## Alternatives considered

- Community LocalStack without commercial entitlement
- Separate local substitutes for S3 and SQS (MinIO plus a non-SQS queue)
- Pointing local development at real AWS accounts
- moto or other in-process AWS mocks

## Consequences

- Local S3 and SQS stay on one AWS-compatible emulator
- Developers and CI must hold a valid LocalStack entitlement and `LOCALSTACK_AUTH_TOKEN`
- Tokens stay out of the repository
- Revisit this decision if licensing, AWS parity, or operational burden makes LocalStack materially unsuitable

## Migration strategy

Keep production S3 and SQS adapters unchanged. Replace the local emulator only if licensing, AWS parity, or operational burden makes LocalStack materially unsuitable, then swap the local AWS boundary while preserving the same S3 and SQS contracts.
