# ADR-005: Durable generation work queue

## Status

Accepted

## Context

VTON work is long-running, expensive, retryable, and unsuitable for an open HTTP request. Canonical workflow state already lives in PostgreSQL (`GenerationJob` / `GenerationAttempt`).

The implementation plan suggested Redis as the async work queue. That suggestion was challenged and rejected: Redis is not otherwise required as foundational V1 infrastructure, and the target AWS environment already provides a purpose-built managed queue. Rejecting Redis as the generation queue is not a permanent ban on Redis for later cache, rate-limit, or coordination needs if a measured requirement appears.

## Decision

Use Amazon SQS Standard as the production generation-work queue and LocalStack SQS locally.

Queue messages contain opaque generation-job identifiers only. PostgreSQL owns `GenerationJob` and `GenerationAttempt` state. The queue delivers work; it never owns canonical workflow truth.

Workers must be idempotent and tolerate duplicate or reordered delivery (at-least-once semantics).

## Alternatives considered

- Redis Streams (rejected as the generation queue)
- PostgreSQL polling / outbox-style workers
- Kafka
- SQS FIFO

## Consequences

- Redis is not required V1 infrastructure unless another measured need appears
- Worker logic must handle at-least-once delivery; idempotency is a consequence of this ADR, not a separate record
- The queue remains a replaceable transport
- Queue replacement cannot corrupt canonical generation state if PostgreSQL remains authoritative

## Migration strategy

Preserve PostgreSQL generation records and identifiers. Drain or reconcile in-flight messages, switch the queue adapter, and never migrate business truth into the queue.

If an existing repository already implemented Redis as a queue, preserve that work until a controlled migration rather than deleting it.
