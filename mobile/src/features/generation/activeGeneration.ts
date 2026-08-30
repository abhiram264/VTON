// ponytail: in-memory only; a device restart loses in-flight generation
// tracking. Add a storage dependency (e.g. AsyncStorage) when restart
// recovery matters.
let activeGenerationId: string | null = null;

export function setActiveGeneration(id: string): void {
  activeGenerationId = id;
}

export function getActiveGeneration(): string | null {
  return activeGenerationId;
}

export function clearActiveGeneration(): void {
  activeGenerationId = null;
}
