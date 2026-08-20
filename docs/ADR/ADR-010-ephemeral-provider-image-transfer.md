# ADR-010: Ephemeral provider image transfer

## Status

Accepted

## Context

VTON requires transmitting personal and product images to third-party providers. External access must not become permanent application state.

Persistent My Model storage (ADR-009) is a separate concern from provider-transfer persistence. Providers must not receive permanent public access to personal images.

## Decision

Transfer provider inputs ephemerally.

Prefer base64 when appropriate for sensitive inputs and acceptable payload sizes; otherwise use the shortest practical signed URL.

Never expose a permanent public avatar URL.

Copy accepted results into private application storage promptly. Do not persist provider URLs as domain truth.

Every replacement provider must meet the same transfer and retention contract before activation.

## Alternatives considered

- Permanent public media URLs
- Permanent provider-hosted media
- Indefinitely proxying every media transfer through the backend

## Consequences

- More transfer orchestration
- Substantially less long-lived external exposure
- Provider-retention behavior must be reviewed and documented
- Changing providers cannot silently weaken privacy

## Migration strategy

Require every provider adapter to satisfy the same privacy-transfer contract before activation. Reject providers that cannot satisfy the contract unless this ADR is explicitly superseded.
