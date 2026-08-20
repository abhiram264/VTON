# ADR-012: Retailer scraping optional

## Status

Accepted

## Context

Earlier PRD language placed known-retailer scraping in MVP. The later implementation plan explicitly made business-critical scraping a V1 non-goal and required fallback import behavior. The implementation plan wins over PRD scraping-in-MVP language.

The first product proof is Share → resolve item → My Model → VTON → quality gate → Look. Retailer-site changes or restrictions must not be able to break that journey.

## Decision

Retailer-specific scraping is optional enhancement behavior only, not a foundational import dependency.

The foundational Import Resolver accepts user-shared URL and/or image data and always retains a non-scraping fallback. Generic shared URL and image ingestion remain the foundational import path.

No supported V1 Share → Try-On journey may depend on a retailer scraper succeeding.

## Alternatives considered

- Scraper-first import
- Retailer-specific catalog clones
- Supporting only retailers with custom parsers

## Consequences

- Some imports may provide less metadata
- Retailer site changes or restrictions cannot take down the core product flow
- A non-scraping image fallback remains part of the product architecture

## Migration strategy

Add retailer-specific resolvers independently after technical, contractual/legal, and reliability review. Any resolver can later be disabled without breaking generic share ingestion.
