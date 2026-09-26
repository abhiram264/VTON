// ponytail: in-memory only; a device restart loses in-flight generation
// tracking. Add a storage dependency (e.g. AsyncStorage) when restart
// recovery matters.
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
