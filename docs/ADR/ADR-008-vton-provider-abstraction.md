# ADR-008: VTON provider abstraction

## Status

Accepted

## Context

VTON providers and model capabilities can change. Provider-specific contracts must not become mobile or domain contracts.

The first product proof is Share → resolve item → My Model → VTON → quality gate → Look. Privacy, cost, retries, abuse controls, and quality decisions must stay centralized. Exact FASHN model-routing configuration is replaceable adapter configuration, not product architecture.

## Decision

Mobile communicates only with the AI Fashion Vault backend. The mobile application must not talk directly to a VTON provider.

Backend generation uses an application-owned, provider-neutral VTON port/capability abstraction. Domain and mobile contracts stay on that port.

Provider DTOs, model names, error types, and transport details remain inside adapters. FASHN is the initial adapter, not the architecture.

## Alternatives considered

- Direct mobile-to-provider calls
- Exposing provider DTOs through domain models or mobile DTOs
- Hard-coding the product to FASHN's current request/response shape

## Consequences

- Extra mapping code between domain contracts and provider adapters
- Centralized privacy, retry, quality, cost, routing, and provider-replacement control
- FASHN-specific types cannot leak into Studio, Vault, Look, or mobile contracts
- Provider capability and routing can change behind the abstraction without a new product architecture

## Migration strategy

Add and contract-test a replacement adapter on the same VTON port. Benchmark quality, latency, cost, and privacy before traffic migration. Migrate traffic gradually, then remove the old adapter after outstanding work is settled.
