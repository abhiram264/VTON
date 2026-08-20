# ADR-002: React Native with native share integration

## Status

Accepted

## Context

Mobile product code should be shared so Android and iOS can share a primary implementation. Android sharing and iOS Share Extension behavior are native operating-system seams and cannot be treated as a pure JavaScript concern.

This ADR records the mobile application boundary only. It does not generate or rewrite mobile scaffolding.

## Decision

Use React Native for the shared mobile application and the minimum required native Android/iOS implementation for share integration.

Do not pin a React Native minor release in this ADR. Routine mobile framework upgrades are implementation maintenance, not architecture.

## Alternatives considered

- Fully separate Kotlin and Swift applications
- Another cross-platform framework
- Implementing all share behavior purely in JavaScript

## Consequences

- Most mobile product code remains shared
- Platform-specific share behavior remains native
- Platform projects still require native Android and iOS tooling
- Share payload handling stays a native seam rather than a JS-only abstraction

## Migration strategy

If React Native is later replaced, preserve backend contracts and native share payload boundaries. Do not use this ADR slice to generate or rewrite mobile scaffolding.
