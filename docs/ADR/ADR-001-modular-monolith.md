# ADR-001: Modular monolith

## Status

Accepted

## Context

V1 is one product. The first proof is a narrow Share → resolve item → My Model → VTON → quality gate → Look vertical slice. Later Vault, Studio, and natural-language layers must not become the source of truth for that slice.

Microservices, Kafka, Kubernetes, service mesh, CQRS, event sourcing, and dozens of independently deployed services are V1 non-goals. Transaction boundaries and delivery should stay simple while the product domain is still evolving.

## Decision

Build one feature-modular Spring Boot backend.

Generation workers may run as separately scalable processes or deployments so expensive generation can scale independently of API traffic. Do not decompose business modules into independent services in V1.

## Alternatives considered

- Service-per-domain-module microservices
- Dozens of independently deployed microservices
- Serverless function decomposition

## Consequences

- Simpler transactional and deployment model
- Module boundaries must be enforced internally
- Generation capacity can scale independently of the API
- Distributed-system complexity is deferred until measured need

## Migration strategy

Extract a module only when measured scaling, security, organizational ownership, access-control, or independent-release requirements justify the cost. Preserve the existing module contract through extraction so decomposition is a migration, not a redesign.
