import {
  OUTAGE_FAILURE_CODES,
  PROVIDER_OUTAGE_TRIGGER_CODES,
  classifyGenerationFailure,
  isProviderOutage,
} from '../src/features/generation/failureContract';

// The server emits GENERATION_MEDIA_UNAVAILABLE (GenerationCreationService.java
// throws problem(503, GENERATION_MEDIA_UNAVAILABLE) on the create and preflight
// paths). Before this literal was registered it fell through to GENERIC and the
// screen lost the outage verdict.
const CODE = 'GENERATION_MEDIA_UNAVAILABLE';

describe('GENERATION_MEDIA_UNAVAILABLE is an outage, not a generic failure', () => {
  it('is registered in OUTAGE_FAILURE_CODES so the failure classifies as OUTAGE', () => {
    expect(OUTAGE_FAILURE_CODES).toContain(CODE);
    expect(classifyGenerationFailure(CODE, false)).toBe('OUTAGE');
  });

  it('is registered in PROVIDER_OUTAGE_TRIGGER_CODES so the outage notice is shown', () => {
    expect(PROVIDER_OUTAGE_TRIGGER_CODES).toContain(CODE);
    expect(isProviderOutage(CODE, false)).toBe(true);
  });

  it('is not misclassified as a rate limit or quality rejection', () => {
    expect(classifyGenerationFailure(CODE, false)).not.toBe('GENERIC');
    expect(classifyGenerationFailure(CODE, false)).not.toBe('RATE_LIMITED');
    expect(classifyGenerationFailure(CODE, false)).not.toBe('QUALITY');
  });
});
