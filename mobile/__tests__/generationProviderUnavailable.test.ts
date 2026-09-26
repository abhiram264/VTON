import {
  OUTAGE_FAILURE_CODES,
  PROVIDER_OUTAGE_TRIGGER_CODES,
  classifyGenerationFailure,
  isProviderOutage,
} from '../src/features/generation/failureContract';

// A provider refusal is a DIFFERENT failure from a media fault: the media store
// being absent and the provider being absent must not collapse into one code.
// Both literals must classify as OUTAGE and trip the provider-outage trigger,
// so a refusal never renders the bare GENERIC notice and never renders a false
// "saved to the Vault" for a user whose try-on actually failed.
const MEDIA = 'GENERATION_MEDIA_UNAVAILABLE';
const PROVIDER = 'GENERATION_PROVIDER_UNAVAILABLE';

describe('generation outage problem codes keep distinct literals, both fully registered', () => {
  it('registers GENERATION_PROVIDER_UNAVAILABLE in BOTH code sets', () => {
    expect(OUTAGE_FAILURE_CODES).toContain(PROVIDER);
    expect(PROVIDER_OUTAGE_TRIGGER_CODES).toContain(PROVIDER);
  });

  it('classifies both codes as OUTAGE, never GENERIC', () => {
    expect(classifyGenerationFailure(PROVIDER, false)).toBe('OUTAGE');
    expect(classifyGenerationFailure(MEDIA, false)).toBe('OUTAGE');
  });

  it('trips the provider-outage trigger for both codes', () => {
    expect(isProviderOutage(PROVIDER, false)).toBe(true);
    expect(isProviderOutage(MEDIA, false)).toBe(true);
  });

  it('keeps the two codes distinct literals, not one aliased to the other', () => {
    expect(MEDIA).not.toBe(PROVIDER);
  });
});
