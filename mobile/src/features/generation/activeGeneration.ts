// ponytail: same-process fast path only. This variable does not survive a
// device restart, and it is deliberately NOT the recovery source of truth:
// recovery reads the owner-scoped server list (see generationLifecycle.ts
// recoverActiveGeneration), so a restart resumes from durable state instead
// of losing an already-charged job. Keeping the id here avoids a list
// round-trip when the app already knows which job it is polling.
let activeGenerationId: string | null = null;
let pollPhaseFailed = false;

export function setActiveGeneration(
  id: string,
  pollFailed: boolean = true,
): void {
  activeGenerationId = id;
  pollPhaseFailed = pollFailed;
}

export function getActiveGeneration(): string | null {
  return activeGenerationId;
}

export function clearActiveGeneration(): void {
  activeGenerationId = null;
  pollPhaseFailed = false;
}

export function markPollPhaseFailure(): void {
  if (activeGenerationId != null) {
    pollPhaseFailed = true;
  }
}

export function clearPollPhaseFailure(): void {
  pollPhaseFailed = false;
}

export function isPollPhaseFailure(): boolean {
  return pollPhaseFailed && activeGenerationId != null;
}
