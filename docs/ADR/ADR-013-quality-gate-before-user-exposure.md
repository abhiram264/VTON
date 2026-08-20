# ADR-013: Quality gate before user exposure

## Status

Accepted

## Context

Provider completion or HTTP success does not prove that a generated try-on is visually acceptable. Provider-quality failures can occur even when provider requests technically succeed.

The first product proof is Share → resolve item → My Model → VTON → quality gate → Look. A completed API request must not automatically create an acceptable Look.

## Decision

Every candidate generation passes a product-owned quality decision before it can become a successful user-facing Look.

Quality outcomes are pass, bounded retry, or fail. Provider HTTP or technical success cannot bypass the quality gate.

The quality evaluator is replaceable and versionable behind the quality boundary. Quality algorithms can evolve without weakening this architecture.

## Alternatives considered

- Expose every technically completed provider output
- Rely only on user reporting
- Use provider success as the acceptance signal

## Consequences

- Additional latency and evaluation cost
- Product metrics reflect usable output rather than transport success
- Quality handling is deterministic and operationally measurable
- Visibly broken try-ons are not treated as product success

## Migration strategy

Version quality evaluators and benchmark replacement behavior before rollout. Every VTON provider continues to feed the same product-owned quality boundary.
