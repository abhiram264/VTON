# ADR-003: Spring Boot 4.x and Java 21

## Status

Accepted

## Context

The backend requires a stable JVM and Spring baseline that can survive the 18–24 month product horizon without treating ordinary dependency upgrades as architecture changes.

## Decision

Use Spring Boot 4.x with Java 21.

Do not pin a Spring Boot patch or minor release, and do not pin a JDK vendor, in this ADR.

## Alternatives considered

- Spring Boot 3.x
- Immediately standardizing on a later Java LTS
- Node/TypeScript backend
- Go backend

## Consequences

- Establishes a durable backend family and language baseline
- Routine Spring Boot 4.x dependency updates remain reversible implementation work
- Java 21 is the language LTS for this horizon, not a vendor-specific runtime contract

## Migration strategy

Stay on supported Spring Boot 4.x releases. Plan migration to a later supported Java LTS before the chosen Java 21 support window becomes constraining.
