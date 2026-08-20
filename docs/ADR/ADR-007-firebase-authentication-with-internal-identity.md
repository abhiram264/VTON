# ADR-007: Firebase authentication with internal identity

## Status

Accepted

## Context

V1 needs external authentication without making an authentication provider the owner of the domain's identity model. A custom credential system is not required for V1 delivery.

Firebase UID must not become the domain-wide primary identity. Domain entities must remain keyed to an application-owned user identity so a later authentication-provider replacement does not require rewriting every owned entity.

## Decision

Use Firebase Authentication for external authentication.

The backend verifies Firebase-issued identity tokens and maps external issuer and subject identifiers to an AI Fashion Vault internal user UUID. Domain entities reference the internal UUID only. Firebase UID is not domain identity.

## Alternatives considered

- Amazon Cognito
- Self-hosted OIDC
- A custom credential/password system

## Consequences

- Authentication is provider-backed while domain identity remains application-owned
- Provider isolation lives in this decision; a separate authentication-abstraction ADR is not required
- Provider replacement is possible but not seamless

## Migration strategy

Add a second issuer mapping. Migrate or link external accounts while retaining internal user UUIDs so user-owned domain records do not require wholesale key rewrites. Retire Firebase only after the identity transition is complete.
