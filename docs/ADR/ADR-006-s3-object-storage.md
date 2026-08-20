# ADR-006: S3 object storage

## Status

Accepted

## Context

Avatar, product, temporary, and generated images are large private binaries with distinct retention requirements. They must not live on application-server filesystems or as relational BLOBs.

The first product proof is Share → resolve item → My Model → VTON → quality gate → Look. Media for that slice must remain private and revocable. Permanent public URLs must not become domain truth.

## Decision

Use an S3 API storage boundary. AWS S3 is the production target. LocalStack S3 is the local-development target.

Keep objects private. Persist object keys or logical asset identifiers in application records, not permanent public URLs.

Where external access is required, use short-lived signed access. Signed URLs are capabilities, not stored identity.

## Alternatives considered

- Relational BLOB storage
- Application-server filesystem storage
- Permanent public object URLs
- Another object-storage provider without an S3-compatible seam

## Consequences

- Media scales independently from structured data
- Access remains revocable because domain records store keys or asset IDs, not permanent public URLs
- Signed URLs must be handled as sensitive, time-bounded capabilities
- Local and production share a compatible storage contract

## Migration strategy

Keep storage behind an application-owned storage port.

During a storage-provider migration, copy objects and repoint logical assets without changing domain identity. User, generation, and Look entities keep their identifiers; only the storage adapter and object location change.
