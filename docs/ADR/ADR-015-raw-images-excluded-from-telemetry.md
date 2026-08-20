# ADR-015: Raw images excluded from telemetry

## Status

Accepted

## Context

Logs, traces, analytics, crash reports, and error payloads are replicated data surfaces. Once personal media enters those processors, deletion and processor control become difficult.

Share → resolve item → My Model → VTON → quality gate → Look handles user avatars, product images, and generated results. Encoding those images as base64, attaching them to crash systems, or logging signed or permanent image URLs would leak media or access credentials through observability.

## Decision

Never place raw image bytes, base64 image content, signed image URLs, permanent image URLs, provider request bodies, or equivalent direct media payloads into telemetry.

Use opaque internal identifiers, status and error codes, sizes and dimensions, timings, provider and model metadata, quality results, and cost metadata instead.

## Alternatives considered

- Full request logging followed by later redaction
- Image attachments in crash systems
- Analytics events containing media URLs

## Consequences

- Some debugging workflows are less convenient
- Personal media does not proliferate through monitoring processors
- Systems remain diagnosable without exposing media

## Migration strategy

Any future diagnostic image-capture facility must be a separate purpose-specific, access-controlled, retention-controlled subsystem. It must not relax this ordinary telemetry ADR.
