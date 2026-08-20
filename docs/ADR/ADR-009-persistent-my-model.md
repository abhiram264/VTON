# ADR-009: Persistent My Model

## Status

Accepted

## Context

My Model is core reusable user state for Share → resolve item → My Model → VTON → quality gate → Look. Users should not need to upload a new personal image for every try-on.

Temporary provider transfer is a separate privacy concern. Provider-hosted copies must not become the persistent My Model.

## Decision

Persist the user's chosen My Model image privately in application-controlled storage for future try-ons.

Persistence remains user-controlled and subject to deletion and applicable retention policy. Do not treat provider-hosted copies as the persistent My Model.

## Alternatives considered

- Require a fresh image per generation
- Persist only provider-hosted state
- Persist only a derived representation

## Consequences

- Improved repeat-use experience at the cost of retaining sensitive personal media
- Consent, authorization, deletion, and storage controls become mandatory
- Persistent application-owned My Model stays distinct from ephemeral provider transfer

## Migration strategy

Represent My Model through application-owned identifiers so underlying storage or provider changes do not change domain identity. Replacement or deletion removes the associated controlled assets.
