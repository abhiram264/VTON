# ADR-014: Generation attempt cost accounting

## Status

Accepted

## Context

Provider cost varies by provider, model or mode, and retries. Final-Look-only accounting hides failed and quality-rejected spend.

Historical provider economics must be retained rather than reconstructed from current price tables. Pricing changes must not rewrite past unit economics.

This record is documentation only. It does not implement persistence, schemas, or billing adapters.

## Decision

Persist provider economics at the GenerationAttempt level. Do not attach cost only to the final Look.

At minimum record:

- provider
- provider model or mode
- external prediction identifier where appropriate
- billed credits or units
- currency or monetary amount when determinable
- relevant pricing snapshot or reference
- technical outcome
- quality outcome

Aggregate attempt cost to Generation and Look metrics so cost per quality-approved Look can be measured accurately.

Do not reconstruct historical costs from current prices later.

## Alternatives considered

- Reconstruct historical costs from current pricing
- Record only aggregate daily provider spend
- Attach cost only to final successful Looks

## Consequences

- Slightly more durable operational metadata
- Accurate cost per quality-approved Look and provider comparison become possible
- Retries and failed quality attempts are included in actual economics

## Migration strategy

Each provider adapter maps its billing model into the common attempt-accounting structure while retaining provider-specific details only in infrastructure metadata.
