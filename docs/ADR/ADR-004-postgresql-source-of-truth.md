# ADR-004: PostgreSQL as source of truth

## Status

Accepted

## Context

AI Fashion Vault requires durable transactional relationships among users, identity mappings, avatars, imports, generations, attempts, quality decisions, Looks, consent, and economics.

Binary media is large and does not belong in relational rows. Queues deliver long-running generation work but must not become canonical workflow state: redelivery or queue replacement must not corrupt business truth.

This ADR does not pin a PostgreSQL major version. Routine database lifecycle management is not an architecture-record change.

## Decision

PostgreSQL is the source of truth for durable structured application and workflow state.

Binary media lives in object storage. Domain records own stable asset identity; they do not store media as BLOBs.

Queues carry work delivery only and never own canonical workflow truth.

## Alternatives considered

- Document database as the primary store
- DynamoDB-first design
- Separate database per module
- Event-sourced or CQRS write models

## Consequences

- Simple transactional relationships and one authoritative workflow store
- Media remains outside relational rows
- Queue replacement or redelivery cannot rewrite canonical generation or Look state
- Schema and ownership discipline are required so later modules do not create a second writable truth

## Migration strategy

Use versioned schema migrations.

If a future module is extracted, transfer ownership of its tables deliberately and avoid dual writable truth. Preserve canonical identities so extraction is a migration, not a rewrite of user, generation, or Look keys.
