type LookNavigate = (name: 'Look', params: { lookId: string }) => void;

// Look deep-link destination (notification taps): fashionvault://looks/{lookId}.
// lookId is the backend LookId — a lowercase canonical UUID — so the same
// strictness as the share handoff URI applies: query strings, extra segments,
// uppercase hex, and other hosts are rejected silently.
const LOOK_LINK_URI_PATTERN =
  /^fashionvault:\/\/looks\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;

export function extractLookId(uri: string): string | null {
  const match = LOOK_LINK_URI_PATTERN.exec(uri);
  return match ? match[1] : null;
}

// Handled look ids are remembered for the process lifetime so a replayed link
// (or concurrent duplicate delivery of the same id) navigates at most once,
// mirroring the share handler's dedupe pattern.
const handledLookIds = new Set<string>();
const inFlightLookIds = new Set<string>();

export function handleLookLink(uri: string, navigate: LookNavigate): void {
  const lookId = extractLookId(uri);
  if (lookId === null || handledLookIds.has(lookId)) return;
  if (inFlightLookIds.has(lookId)) return;
  inFlightLookIds.add(lookId);
  try {
    // The id is final once navigation is attempted, so a later replay cannot
    // navigate twice.
    handledLookIds.add(lookId);
    navigate('Look', { lookId });
  } catch {
    // Silent per ADR-015: never log URIs, ids, or error detail.
  } finally {
    inFlightLookIds.delete(lookId);
  }
}
