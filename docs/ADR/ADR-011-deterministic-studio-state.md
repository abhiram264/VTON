# ADR-011: Deterministic Studio state

## Status

Accepted

## Context

Studio may later accept natural-language commands, but an LLM conversation transcript cannot safely own outfit truth. Conversation history is incomplete, reorderable, and model-dependent; treating it as canonical state would make Studio untestable and would let prompt or model changes corrupt outfits.

The first product proof remains Share → resolve item → My Model → VTON → quality gate → Look. This ADR records the Studio boundary now so later layers cannot become the source of truth for that slice. Implementation of Studio commands and LLM tooling is deferred until after that proof.

## Decision

Studio state is explicit structured domain state. It is not conversation history, free-form model JSON, or any LLM-owned document.

Add, remove, replace, clear, and avatar-selection actions pass through normal application authorization and compatibility validation. LLMs may propose these application commands only. They may not write persistence directly or treat conversation history as canonical state.

## Alternatives considered

- Conversation history as source of truth
- Directly persisted free-form model JSON
- Direct database writes from an agent

## Consequences

- Studio behavior remains testable and independent of any particular model or prompt
- More explicit domain rules are required for outfit mutations
- Natural-language tools cannot bypass authorization or compatibility invariants
- Studio and LLM work stay off the critical path of the Share-to-Look proof

## Migration strategy

Future LLMs, prompts, or agent frameworks integrate only through the same application commands. Structured Studio state survives model replacement unchanged. Do not introduce a second writable conversation-backed store.
