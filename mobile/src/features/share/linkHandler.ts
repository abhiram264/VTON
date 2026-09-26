import { Linking, Platform } from 'react-native';
import { extractShareId, validateSharePayload } from './types';
import { isFeatureEnabled } from '../../config/featureFlags';
import { handleLookLink } from './lookLinkHandler';
import { getShareHandoff } from './shareHandoff';
import type { SharePayload } from './types';

type LookNavigate = (name: 'Look', params: { lookId: string }) => void;
type Navigate = (
  name: 'ShareImport',
  params: { payload: SharePayload },
) => void;

// Consumed share ids are remembered for the process lifetime so a warm link
// replaying an id we already handled (or concurrent duplicate delivery of the
// same id) consumes and navigates at most once.
const handledShareIds = new Set<string>();
const inFlightShareIds = new Set<string>();

let activeRegistration: {
  generation: number;
  navigate: Navigate;
  lookNavigate: LookNavigate;
  subscription: {remove: () => void} | null;
} | null = null;
let registrationGeneration = 0;

async function handleShareLink(uri: string, navigate: Navigate) {
  // Decline before consuming when the platform's share-import flag is off:
  // no native consume call, no route change (spec: disabled means no data
  // consumption).
  if (
    !isFeatureEnabled(
      Platform.OS === 'ios' ? 'SHARE_IMPORT_IOS' : 'SHARE_IMPORT_ANDROID',
    )
  ) {
    return;
  }
  const shareId = extractShareId(uri);
  if (shareId === null || handledShareIds.has(shareId)) return;
  if (inFlightShareIds.has(shareId)) return;
  inFlightShareIds.add(shareId);
  try {
    const handoff = getShareHandoff();
    if (handoff === null) return;
    const result = await handoff.consumeShare(shareId);
    // The id is final once consumed — success or typed failure — so a later
    // replay cannot double-consume.
    handledShareIds.add(shareId);
    if (!result.ok) return;
    const payload = validateSharePayload(result.payload);
    if (payload === null) return;
    navigate('ShareImport', { payload });
  } catch {
    // Bridge failure: swallow silently. Never log shared text, URLs, URIs,
    // payload JSON, or bytes (ADR-015) — no error output at all here.
  } finally {
    inFlightShareIds.delete(shareId);
  }
}

// Look deep links (fashionvault://looks/{lookId}) share the same 'url'
// subscription as share handoffs: each inbound URL event is offered to both
// handlers; each silently ignores URIs that are not its own scheme.
function handleUrlEvent(
  uri: string,
  navigate: Navigate,
  lookNavigate: LookNavigate,
) {
  handleLookLink(uri, lookNavigate);
  void handleShareLink(uri, navigate);
}

// Registers the warm-start url listener FIRST (so a link arriving during cold
// start is not lost), then resolves the initial URL. A remount updates the
// callbacks on the single live subscription; stale initial-URL callbacks and
// stale disposers cannot affect the current AppShell.
export function startShareLinkHandling(
  navigate: Navigate,
  lookNavigate: LookNavigate,
): () => void {
  const generation = ++registrationGeneration;
  if (activeRegistration !== null) {
    activeRegistration.generation = generation;
    activeRegistration.navigate = navigate;
    activeRegistration.lookNavigate = lookNavigate;
    return () => {
      if (activeRegistration?.generation !== generation) {
        return;
      }
      activeRegistration.subscription?.remove();
      activeRegistration = null;
    };
  }

  const registration = {
    generation,
    navigate,
    lookNavigate,
    subscription: null as {remove: () => void} | null,
  };
  activeRegistration = registration;
  registration.subscription = Linking.addEventListener('url', event => {
    if (activeRegistration !== registration) return;
    handleUrlEvent(event.url, registration.navigate, registration.lookNavigate);
  });
  void (async () => {
    try {
      const initialUri = await Linking.getInitialURL();
      if (
        activeRegistration === registration &&
        typeof initialUri === 'string'
      ) {
        handleUrlEvent(
          initialUri,
          registration.navigate,
          registration.lookNavigate,
        );
      }
    } catch {
      // Same silence policy as above.
    }
  })();
  return () => {
    if (activeRegistration?.generation !== generation) {
      return;
    }
    registration.subscription?.remove();
    activeRegistration = null;
  };
}
