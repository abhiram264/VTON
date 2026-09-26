# AI Fashion Vault — Technical Implementation Plan

> **Status:** Historical design input, not the current implementation or execution plan. Current scope and source contracts are in `docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md`, `docs/ARCHITECTURE.md`, and `docs/CONTRACT_INVENTORY.md`; where this document disagrees, those sources win. Do not execute this plan automatically.

> **Product direction:** A share-first consumer mobile app that lets users share a fashion item from anywhere, try it on using a persistent “My Model” avatar, save the item to a personal Vault, combine saved items in a Studio, generate complete Looks, and reuse those Looks over time.

---

## 0. Executive Summary

The product should be built around one core loop:

```text
SHARE → RESOLVE ITEM → TRY ON → QUALITY CHECK → LOOK
                           ↓
                         VAULT
                           ↓
                         STUDIO
                           ↓
                      MULTI-ITEM LOOK
```

The most important implementation rule is:

> **Do not build the entire future product at once. First prove the vertical slice: Share → product resolution → My Model → VTON → quality gate → Look.**

Only after that slice is reliable should engineering proceed to Vault, Collections, Studio, multi-item composition, natural-language commands, advanced personalization, and other product layers.

The recommended architecture is a **modular monolith with asynchronous workers**, not microservices.

Recommended initial stack:

```text
MOBILE
- React Native 0.86
- TypeScript
- Native Kotlin Android share target
- Native Swift iOS Share Extension

BACKEND
- Spring Boot 4.1
- Java 21
- Maven
- Spring Web
- Spring Security
- Spring Data JPA
- Validation
- Flyway
- Actuator
- OpenTelemetry / Micrometer

AUTH
- Firebase Authentication

DATABASE
- PostgreSQL

ASYNC / JOBS
- Redis

OBJECT STORAGE
- Local: LocalStack S3
- Production: AWS S3

VTON
- Provider abstraction
- First provider: FASHN
- Fast path: FASHN Try-On v1.6
- High-fidelity path: FASHN Try-On Max

PUSH
- Firebase Cloud Messaging
- APNs through FCM/native iOS integration

PRODUCTION INFRA
- AWS ECS/Fargate
- RDS PostgreSQL
- ElastiCache Redis
- S3
- Secrets Manager
- CloudWatch / OpenTelemetry backend

CI/CD
- GitHub Actions
- Android → Google Play
- iOS → TestFlight / App Store Connect
```

---



# 1. Product Boundaries



## 1.1 V1 Product

V1 includes:

- Account creation and authentication
- My Model / persistent avatar
- Android Share Target
- iOS Share Extension
- URL/image share ingestion
- Import resolver
- Graceful product-image fallback
- Item normalization
- Vault
- System categories
- User collections
- Studio
- Outfit slots
- Single-item virtual try-on
- Practical multi-item generation
- Quality gating
- Generated Looks
- Look history
- Save/share Look
- Product source link
- Push notifications
- Deep links
- Privacy controls
- User-data deletion
- Basic natural-language Studio controls
- Analytics
- Cost tracking
- Provider abstraction



## 1.2 Explicit V1 Non-Goals

Do **not** implement in V1:

- Myntra catalog clone
- AJIO catalog clone
- Business-critical web scraping
- Merchant dashboard
- Shopify merchant SaaS
- Marketplace
- Price-comparison engine
- Fit prediction
- Size prediction
- Body-measurement AI
- Social feed
- Public wardrobes
- Video generation
- AI influencer features
- Arbitrary attractiveness scores
- “8.7/10 style” scoring
- Complex personal stylist
- Kafka
- Kubernetes
- Service mesh
- CQRS
- Event sourcing
- Dozens of microservices
- Proprietary VTON training

---



# 2. Engineering Principles

The following rules should be treated as non-negotiable architecture constraints.

## Rule 1 — Mobile never talks directly to a VTON provider

Bad:

```text
React Native → FASHN
```

Correct:

```text
React Native
    ↓
Backend
    ↓
VTON Orchestrator
    ↓
Provider Adapter
    ↓
FASHN
```

This allows:

- provider replacement
- centralized rate limiting
- privacy enforcement
- cost tracking
- routing
- retries
- quality gating
- abuse prevention

---



## Rule 2 — Provider-specific types stay inside provider infrastructure

Never allow:

```java
FashnPredictionResponse
```

to become part of:

```text
Studio
Look
Vault
Mobile DTOs
Domain models
```

Use internal domain types instead.

---



## Rule 3 — Raw user images never enter logs, traces, or analytics

Forbidden:

```text
avatar_url
signed_s3_url
base64_image
raw_image_bytes
FASHN request body
```

inside:

- application logs
- analytics events
- tracing span attributes
- error messages
- crash reports

---



## Rule 4 — Studio state is structured

The LLM must never become the source of truth.

Bad:

```text
Conversation:
“User added a white sneaker earlier.”
```

Correct:

```json
{
  "head": null,
  "upperBody": "vault-item-1",
  "lowerBody": "vault-item-7",
  "feet": "vault-item-9"
}
```

---



## Rule 5 — Provider success does not equal product success

A returned HTTP 200 does not mean the image is acceptable.

Every generated output must pass:

```text
GENERATION
    ↓
QUALITY GATE
    ↓
PASS / RETRY / FAIL
```

---



# 3. Repository Initialization

Use a simple monorepo.

```text
ai-fashion-vault/
│
├── mobile/
│   ├── android/
│   ├── ios/
│   ├── src/
│   ├── package.json
│   └── tsconfig.json
│
├── backend/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
│
├── infra/
│   ├── docker/
│   ├── terraform/
│   └── scripts/
│
├── docs/
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── API_CONTRACTS.md
│   ├── PRIVACY.md
│   ├── AI_EVALUATION.md
│   └── ADR/
│
├── .github/
│   └── workflows/
│
├── docker-compose.yml
├── .gitignore
└── README.md
```

---



# 4. Architecture Decision Records

Create the ADRs before implementation.

Suggested initial ADRs:

```text
ADR-001 Modular monolith instead of microservices
ADR-002 React Native with native share extensions
ADR-003 Spring Boot 4 + Java 21
ADR-004 PostgreSQL as source of truth
ADR-005 Redis for async work
ADR-006 S3-compatible object storage
ADR-007 Firebase authentication
ADR-008 VTON provider abstraction
ADR-009 Persistent My Model
ADR-010 Ephemeral provider image transfer
ADR-011 Deterministic Studio state
ADR-012 Retailer scraping is optional enhancement only
ADR-013 Quality gate before user exposure
ADR-014 Provider cost stored per generation
ADR-015 Raw images excluded from telemetry
```

Every ADR should contain:

```text
Status
Context
Decision
Alternatives considered
Consequences
Migration strategy
```

---



# 5. Mobile Project Initialization

Create the React Native project.

Recommended baseline:

```text
React Native 0.86
TypeScript
New Architecture enabled
```

Initial dependencies should remain intentionally small.

Add only what is necessary for:

- navigation
- HTTP
- auth
- state
- image selection
- camera
- secure storage
- push
- deep links

Suggested project layout:

```text
mobile/src/
│
├── app/
│   ├── navigation/
│   ├── providers/
│   └── bootstrap/
│
├── features/
│   ├── auth/
│   ├── avatar/
│   ├── import/
│   ├── vault/
│   ├── collections/
│   ├── studio/
│   ├── generation/
│   ├── looks/
│   └── settings/
│
├── components/
├── design-system/
├── api/
├── hooks/
├── state/
├── analytics/
├── native/
└── utils/
```

Enable:

```text
TypeScript strict mode
ESLint
Prettier
Jest
React Native Testing Library
```

---



# 6. Backend Project Initialization

Use Spring Initializr with:

```text
Spring Boot 4.1
Java 21
Maven
```

Dependencies:

- Spring Web
- Spring Security
- Spring Data JPA
- Validation
- PostgreSQL Driver
- Spring Data Redis
- Flyway
- Actuator
- Micrometer
- OpenTelemetry
- Testcontainers

Package by feature, not by technical layer.

Bad:

```text
controller/
service/
repository/
entity/
```

Preferred:

```text
com.company.fashionvault/
│
├── auth/
├── user/
├── avatar/
├── importer/
├── vault/
├── collection/
├── studio/
├── generation/
├── quality/
├── look/
├── notification/
├── privacy/
├── storage/
└── shared/
```

Inside a module:

```text
vault/
├── api/
├── application/
├── domain/
└── infrastructure/
```

---



# 7. Local Development Environment

Create:

```text
docker-compose.yml
```

Services:

```text
postgres
redis
localstack
```

Suggested ports:

```text
PostgreSQL → 5432
Redis      → 6379
LocalStack → 4566
```

Local object-storage buckets:

```text
fashion-avatar-private
fashion-vault-assets
fashion-look-private
fashion-generation-temp
```

Never use one unrestricted uploads bucket.

---



# 8. Environment Strategy

Support:

```text
development
staging
production
```

All secrets/config via environment or secret manager.

Examples:

```text
APP_ENV

DB_URL
DB_USERNAME
DB_PASSWORD

REDIS_URL

S3_REGION
S3_AVATAR_BUCKET
S3_VAULT_BUCKET
S3_LOOK_BUCKET
S3_TEMP_BUCKET

FASHN_API_KEY

FIREBASE_PROJECT_ID

OTEL_EXPORTER_OTLP_ENDPOINT
```

Never commit production secrets.

---



# 9. Database Foundation

Use Flyway from the first commit that introduces persistence.

Suggested migrations:

```text
V001__create_users.sql
V002__create_avatar_profiles.sql
V003__create_share_imports.sql
V004__create_vault.sql
V005__create_collections.sql
V006__create_studio.sql
V007__create_generations.sql
V008__create_looks.sql
V009__create_notifications.sql
V010__create_consents.sql
```

Use UUID identifiers.

---



# 10. Core Domain Model

```text
USER
 │
 ├── AVATAR_PROFILE
 │       └── AVATAR_IMAGE
 │
 ├── SHARE_IMPORT
 │
 ├── VAULT_ITEM
 │       └── SOURCE_REFERENCE
 │
 ├── COLLECTION
 │       └── COLLECTION_ITEM
 │
 ├── STUDIO_SESSION
 │       └── STUDIO_ITEM
 │
 ├── GENERATION_JOB
 │       └── GENERATION_ATTEMPT
 │              └── QUALITY_RESULT
 │
 └── LOOK
         └── LOOK_ITEM
```

---



# 11. Authentication

Use Firebase Authentication.

Flow:

```text
React Native
    ↓
Firebase Authentication
    ↓
Firebase ID Token
    ↓
Spring Security
    ↓
Verify token
    ↓
Map Firebase UID → internal user UUID
```

Initial providers:

```text
Google
Apple
```

Optional later:

```text
email/password
phone
```

Create account-deletion API from the beginning:

```http
DELETE /me
```

---



# 12. Object Storage

Do not proxy large user-image uploads through Spring memory.

Correct flow:

```text
Mobile
   ↓
POST /uploads
   ↓
Backend returns presigned upload URL
   ↓
Mobile uploads directly to S3
   ↓
POST /uploads/{id}/complete
```

Suggested object keys:

```text
users/{userId}/avatars/{uuid}.jpg

users/{userId}/vault/{uuid}.jpg

users/{userId}/looks/{uuid}.jpg

temp/{userId}/{jobId}/{uuid}.jpg
```

Storage classes:

```text
Avatar assets → private / persistent
Vault assets → private / persistent
Saved Looks → private / persistent
Temporary generation files → private / TTL
```

---



# 13. My Model

The first real user-facing feature should be **My Model**.

Flow:

```text
Create My Model
      ↓
Photo guidelines
      ↓
Camera / Gallery
      ↓
Preflight
      ↓
Upload
      ↓
Create AvatarProfile
      ↓
Set primary
```

V1:

```text
One primary image only
```

Do not implement 3D body reconstruction yet.

---



# 14. Avatar Data Model

Example:

```text
avatar_profile
--------------
id
user_id
primary_image_id
status
created_at
updated_at
```

```text
avatar_image
------------
id
profile_id
storage_key
width
height
pose
quality_status
created_at
```

Potential future fields:

```text
front
three_quarter
side
```

but do not require them in V1.

---



# 15. Avatar Preflight

Before accepting the user photo, validate:

```text
image readable
supported format
resolution sufficient
one obvious person
body sufficiently visible
face visible
not excessively blurred
not extremely dark
reasonable aspect ratio
```

Return explicit errors.

Example:

```json
{
  "accepted": false,
  "problems": [
    "FULL_BODY_NOT_VISIBLE",
    "LOW_LIGHT"
  ]
}
```

---



# 16. VTON Provider Abstraction

Create this before integrating any provider.

Example:

```java
public interface VtonProvider {

    GenerationSubmission generate(VtonRequest request);

    GenerationStatus getStatus(String externalJobId);

    ProviderCapabilities capabilities();
}
```

Domain request:

```text
VtonRequest
-----------
avatarAsset
productAssets[]
instructions
mode
outputResolution
privacyMode
```

Provider capabilities:

```text
supportsClothing
supportsFootwear
supportsHeadwear
supportsJewellery
supportsMultiItem
supportsBase64
supportedResolutions
maxImageSize
```

---



# *

Infrastructure structure:

```text
generation/
└── infrastructure/
    └── fashn/
        ├── FashnVtonProvider.java
        ├── FashnClient.java
        ├── FashnMapper.java
        └── FashnConfig.java
```

Routing strategy:

```text
FAST PREVIEW
→ Try-On v1.6

STANDARD
→ Try-On Max fast / 1K

HD
→ Try-On Max balanced / 2K
```

Do not use maximum-quality 4K as the default mobile workflow.

---



# 18. Privacy-Preserving VTON Calls

Preferred:

```text
avatar
→ Base64 or very short-lived signed URL

product
→ short-lived/private URL

output
→ base64 or temporary provider output
```

Backend immediately stores an accepted result in private application storage.

Never permanently expose avatar URLs publicly.

---



# 19. Generation Job System

VTON is asynchronous.

Do not hold an HTTP request open while generation completes.

Flow:

```text
Mobile
   ↓
POST /generations
   ↓
GenerationJob created
   ↓
Redis queue
   ↓
Worker
   ↓
Provider
   ↓
Polling/webhook
   ↓
Quality Gate
   ↓
Look
   ↓
Push notification
```

States:

```text
QUEUED
PREFLIGHT
SUBMITTED
GENERATING
QUALITY_CHECK
RETRYING
COMPLETED
FAILED
```

---



# 20. Generation Data

Track:

```text
generation_job
--------------
id
user_id
studio_session_id
status
requested_mode
created_at
completed_at
```

```text
generation_attempt
------------------
id
generation_job_id
provider
model
provider_job_id
attempt_number
started_at
completed_at
latency_ms
credits_used
estimated_cost
failure_code
```

This data is required for production economics.

---



# 21. Retry Strategy

Classify errors.

```text
INPUT_ERROR
→ never retry

PROVIDER_TIMEOUT
→ retry

PROVIDER_RATE_LIMIT
→ delayed retry

PROVIDER_INTERNAL_ERROR
→ retry / fallback

QUALITY_FAILURE
→ regenerate once

UNSUPPORTED_ITEM
→ fail gracefully
```

Suggested limits:

```text
provider retries: max 2
quality regeneration: max 1
total attempts: max 3
```

Never implement unlimited autonomous retries.

---



# 22. Mandatory Quality Gate

Pipeline:

```text
Generated Image
      ↓
Image Validation
      ↓
Person Preservation
      ↓
Garment/Product Fidelity
      ↓
Color/Pattern Fidelity
      ↓
Anatomy Check
      ↓
Identity Check
      ↓
PASS / RETRY / REJECT
```

Initial implementation can combine:

```text
deterministic CV checks
+
multimodal vision evaluator
```

Internal output:

```json
{
  "identityPreservation": 0.94,
  "garmentFidelity": 0.91,
  "colorFidelity": 0.97,
  "anatomyValid": true,
  "artifactDetected": false,
  "decision": "PASS"
}
```

Never expose these numbers as attractiveness scores.

---



# 23. First Vertical Slice

Before Vault or Studio:

```text
USER
 ↓
My Model
 ↓
Manual product image
 ↓
Try On
 ↓
VTON
 ↓
Quality Gate
 ↓
Look
```

This is the first real engineering gate.

Suggested internal targets:

```text
Generation technical success > 90%
Quality-approved results     > 85%
Sensitive image log leaks    = 0
P50 successful Look          < 15 sec
P95 successful Look          < 30 sec
```

If the core slice is unreliable, stop feature expansion.

---



# 24. AI Evaluation Benchmark

Before scaling product development, create an offline test harness.

Curate:

```text
50 representative avatars
100 fashion items
```

Categories:

```text
tops
bottoms
dresses
kurtas
lehenga sets
footwear
headwear
```

Track:

```text
provider
model
category
latency
cost
human rating
identity fidelity
garment fidelity
color fidelity
artifacts
quality-gate result
```

Build a provider matrix:

```text
                Fidelity   P50   Fail   Cost/success
Provider A         92       9s    7%         ...
Provider B         89       5s   11%         ...
Provider C         95      24s    4%         ...
```

---



# 25. Android Share Target

Implement Android sharing natively.

Create:

```text
android/
└── ShareImportActivity.kt
```

Support:

```text
ACTION_SEND
text/plain
image/*
```

Later:

```text
ACTION_SEND_MULTIPLE
```

Android flow:

```text
External App
    ↓
Android Sharesheet
    ↓
ShareImportActivity
    ↓
Parse Intent
    ↓
Validate MIME / file
    ↓
Copy content URI safely
    ↓
Construct SharePayload
    ↓
Open React Native import route
```

Normalized payload:

```ts
type SharePayload = {
  kind: 'url' | 'image' | 'image_url';
  url?: string;
  localImageUri?: string;
  sourcePackage?: string;
};
```

Never trust MIME type or file size blindly.

---



# 26. iOS Share Extension

Create:

```text
FashionVaultShare
```

Native language:

```text
Swift
```

Configure an App Group.

Example:

```text
group.com.company.fashionvault
```

Flow:

```text
External iOS App
    ↓
Share Sheet
    ↓
FashionVault Share Extension
    ↓
Read NSExtensionContext
    ↓
Extract URL/image/text
    ↓
Persist small handoff metadata
    ↓
Backend or shared App Group
```

Keep the extension lightweight.

Do not place the full React Native Studio inside the Share Extension.

Minimal extension actions:

```text
Try with My Model
Add to Vault
```

When generation finishes:

```text
Push notification
→ Deep link to Look
```

---



# 27. Share Import Resolver

The operating system may provide:

```text
URL
IMAGE
IMAGE + URL
TEXT + URL
```

Normalize all of them.

```text
SharePayload
    ↓
ImportResolver
```

Cases:

```text
IMAGE
→ use directly

IMAGE + URL
→ image + source metadata

URL
→ attempt metadata/product resolution

resolution failure
→ request screenshot or image
```

---



# 28. Source Resolver Architecture

Define:

```java
public interface SourceResolver {

    boolean supports(SourceReference source);

    ResolutionResult resolve(SourceReference source);
}
```

Initial resolvers:

```text
SharedImageResolver
GenericOpenGraphResolver
GenericUrlResolver
```

Possible future resolvers:

```text
AuthorizedRetailerResolver
AffiliateFeedResolver
PartnerCatalogResolver
```

Do not make brittle scraping the foundation.

---



# 29. Product Normalizer

Convert imported content to structured fashion data.

Example:

```json
{
  "category": "TOP",
  "subcategory": "SHIRT",
  "brand": "Rare Rabbit",
  "primaryColor": "BLACK",
  "pattern": "SOLID",
  "confidence": 0.91
}
```

System categories:

```text
TOP
BOTTOM
ONE_PIECE
ETHNIC_FULL_BODY
SAREE
LEHENGA
KURTA
SHERWANI
FOOTWEAR
HEADWEAR
BAG
JEWELLERY
ACCESSORY
UNKNOWN
```

If confidence is low:

```text
Ask user to correct category.
```

Store correction events for future classifier improvement.

---



# 30. Vault

The Vault is the persistent personal fashion library.

Endpoints:

```http
POST   /vault/items
GET    /vault/items
GET    /vault/items/{id}
PATCH  /vault/items/{id}
DELETE /vault/items/{id}
```

Filters:

```text
category
collection
brand
color
search
source
createdDate
```

Vault item fields:

```text
id
user_id
image_asset_id
source_reference_id
name
brand
category
subcategory
color
pattern
attributes
created_at
```

---



# 31. System Categories vs User Collections

Keep them separate.

Example:

```text
Item:
Nike White Sneaker

System category:
FOOTWEAR

Collections:
Goa
Casual
Wishlist
```

Collections schema:

```text
collection
collection_item
```

One item can belong to many collections.

---



# 32. Collection APIs

```http
POST   /collections
GET    /collections
GET    /collections/{id}
PATCH  /collections/{id}
DELETE /collections/{id}

POST   /collections/{id}/items
DELETE /collections/{id}/items/{vaultItemId}
```

---



# 33. Studio State

Build Studio manually before adding an LLM.

Slots:

```text
HEAD
UPPER_BODY
LOWER_BODY
FULL_BODY
FEET
BAG
JEWELLERY[]
ACCESSORY[]
```

Example state:

```json
{
  "avatarId": "avatar-1",
  "head": null,
  "upperBody": "vault-1",
  "lowerBody": "vault-7",
  "fullBody": null,
  "feet": "vault-9",
  "bag": null,
  "jewellery": []
}
```

---



# 34. Outfit Compatibility Engine

Rules:

```text
FULL_BODY conflicts with:
UPPER_BODY
LOWER_BODY
```

But:

```text
FULL_BODY
+
HEAD
+
FEET
+
BAG
+
JEWELLERY
```

may coexist.

Add domain methods:

```text
addItem
removeItem
replaceItem
clearSlot
clearOutfit
setAvatar
```

These methods enforce compatibility.

---



# 35. Render Plan

Never pass raw Studio state to the provider.

Convert:

```text
StudioState
    ↓
RenderPlanner
    ↓
RenderPlan
```

Example:

```json
{
  "avatar": "avatar-1",
  "steps": [
    {
      "slot": "UPPER_BODY",
      "item": "vault-43"
    },
    {
      "slot": "LOWER_BODY",
      "item": "vault-81"
    },
    {
      "slot": "FEET",
      "item": "vault-92"
    }
  ]
}
```

Planner decides between:

```text
provider-native multi-item rendering
```

or:

```text
progressive sequential rendering
```

---



# 36. Multi-Item Rendering

Only implement after single-item reliability is good.

Example progressive generation:

```text
Original Avatar
      ↓
shirt
      ↓
Result A
      ↓
quality gate
      ↓
pants
      ↓
Result B
      ↓
quality gate
      ↓
shoes
      ↓
Result C
      ↓
final quality gate
```

Every step checks:

```text
person identity preserved
previous items preserved
new item preserved
anatomy valid
```

If one step fails, retry that step rather than restarting blindly.

---



# 37. Studio Natural-Language Agent

Add the agent only after deterministic Studio commands exist.

User:

```text
“Add my white Nike shoes and remove the cap.”
```

Model output:

```json
[
  {
    "tool": "searchVault",
    "arguments": {
      "query": "white Nike shoes",
      "category": "FOOTWEAR"
    }
  },
  {
    "tool": "removeItem",
    "arguments": {
      "slot": "HEAD"
    }
  }
]
```

Allowed tools:

```text
searchVault
getVaultItem
listCollection
addItem
removeItem
replaceItem
setAvatar
getStudioState
generateLook
saveLook
```

Never allow:

```text
LLM → direct SQL
```

Correct:

```text
LLM
 ↓
Tool Call
 ↓
Application Service
 ↓
Authorization
 ↓
Domain Validation
 ↓
Database
```

---



# 38. Looks

A Look is a persisted successful generation.

Fields:

```text
id
user_id
avatar_id
generated_asset_id
studio_session_id
render_plan
provider
provider_model
quality_status
created_at
```

`look_item` stores constituent Vault items.

Endpoints:

```http
GET    /looks
GET    /looks/{id}
DELETE /looks/{id}

POST   /looks/{id}/regenerate
POST   /looks/{id}/share-link
```

---



# 39. Push Notifications

Use push when:

```text
generation completed
generation failed
iOS share-extension generation finished
```

Payload:

```json
{
  "type": "LOOK_READY",
  "lookId": "..."
}
```

Deep link:

```text
fashionvault://looks/{lookId}
```

Never place sensitive image URLs inside notification payloads.

---



# 40. Mobile Navigation

Primary bottom navigation:

```text
HOME
VAULT
STUDIO
LOOKS
```

Profile/settings accessible separately.

## Home

Show:

```text
Import Item
Recent shares
Recent Looks
Quick Try
```



## Vault

Show:

```text
All
Tops
Bottoms
One Pieces
Ethnic
Footwear
Headwear
Accessories
Collections
```



## Studio

Show:

```text
Avatar
Active outfit
Slots
Add from Vault
Natural-language command box
Generate
```



## Looks

Show:

```text
Saved Looks
Recent generations
Regenerate
Modify
Share
```

---



# 41. Analytics Event Taxonomy

Implement event names before beta.

```text
account_created

avatar_creation_started
avatar_created
avatar_rejected

share_received
share_resolved
share_resolution_failed

vault_item_created
vault_item_reused
collection_created

studio_started
studio_item_added
studio_item_removed

generation_requested
generation_completed
generation_failed
generation_quality_rejected

look_viewed
look_saved
look_shared

source_link_opened
```

Never place raw image data in event properties.

---



# 42. Product Metrics

Track:

```text
Share import success %
Share → Try On %
Time to first Look
Generation success %
Quality rejection %
P50 generation latency
P95 generation latency
Cost per successful Look
Cost per quality-approved Look
Vault items per active user
Vault item reuse %
Studio usage %
Multi-item usage %
Look save %
Look share %
D1 retention
D7 retention
D30 retention
```

Recommended early core metric:

> **Quality-approved Looks per activated user per week**

---



# 43. Cost Metrics

Track actual provider cost.

```text
generation_id
provider
model
credits
cost
quality_passed
```

Calculate:

```text
cost/request
cost/provider-success
cost/quality-approved Look
cost/active user
cost/retained user
```

The most important AI cost metric is:

> **Cost per quality-approved Look**

---



# 44. Rate Limiting

Generation is expensive.

Rate limit by:

```text
user
device
IP
day
```

Potential V1 limit:

```text
5 free generations/day
```

until economics are known.

Use stricter limits for:

```text
anonymous accounts
new devices
suspicious IPs
failed attestation
```

---



# 45. Abuse Controls

Before public launch:

```text
content-type validation
magic-byte verification
max upload size
max dimensions
image decoding limits
malware scanning where appropriate
signed URLs
short URL expiry
provider budgets
per-user quotas
global cost circuit breaker
```

---



# 46. App Integrity

Add before or during beta:

```text
Android → Play Integrity
iOS → App Attest
```

Use it on high-cost operations:

```text
generation creation
avatar upload
promo-credit redemption
```

---



# 47. Privacy Service

Create a dedicated PrivacyService.

Methods:

```text
deleteAvatar
deleteVaultItem
deleteLook
exportUserData
deleteAccount
```

Account deletion:

```text
request
 ↓
disable account
 ↓
delete avatars
 ↓
delete Vault
 ↓
delete Looks
 ↓
delete temporary generations
 ↓
revoke auth
 ↓
remove push tokens
 ↓
anonymize permitted operational data
 ↓
complete
```

---



# 48. Data Isolation

Separate object-storage domains:

```text
AVATAR
VAULT
LOOK
TEMP
```

Separate semantic data classes:

```text
identity
media
fashion metadata
behavioral analytics
provider operational metadata
```

Do not expose raw photo data to analytics systems.

---



# 49. Data Retention

Suggested initial lifecycle:

```text
temporary generation inputs → delete within 24h
failed intermediates         → delete within 24h
import staging               → delete within 24h
provider temp results        → copy or discard immediately
```

User-controlled persistence:

```text
My Model
Vault
Saved Looks
```

These remain until deletion by user/account policy.

---



# 50. Backend Testing



## Unit Tests

```text
Outfit compatibility
Render planning
Retry decisions
Quality decisions
Authorization
Normalizer rules
Source resolution
```



## Integration Tests

Use Testcontainers:

```text
PostgreSQL
Redis
S3-compatible storage
```



## Provider Contract Tests

Mock and sandbox:

```text
FASHN responses
timeouts
rate limits
invalid input
provider failures
```



## End-to-End

```text
Share → Resolve → Try → Quality Gate → Look
```

---



# 51. Mobile Testing

Test:

```text
components
navigation
auth
avatar upload
share import
Vault
Studio state
Looks
push/deep link
```

Native platform tests:

```text
Android share target
iOS share extension
```

---



# 52. AI Regression Testing

Maintain a fixed benchmark set.

Whenever:

```text
provider changes
provider version changes
prompt changes
quality evaluator changes
routing changes
```

run old vs new.

Measure:

```text
fidelity
identity
latency
cost
failure rate
quality pass rate
```

Do not deploy a model solely because one demo looks better.

---



# 53. Observability

Instrument:

```text
share.resolve
vault.create
generation.queue_wait
generation.provider
generation.poll
generation.quality
generation.store
notification.send
```

Metrics:

```text
generation.count
generation.success
generation.failure
generation.latency
generation.provider_latency
generation.quality_reject
generation.cost
queue.depth
queue.oldest_age
provider.error_rate
provider.credit_balance
```

---



# 54. Logging

Use structured JSON logs.

Log:

```text
request ID
user ID hash/UUID
generation ID
provider
status
latency
error code
```

Never log:

```text
image bytes
base64
signed URL
auth token
provider secret
```

---



# 55. Production Infrastructure

Recommended first production topology:

```text
                      INTERNET
                         │
                         ▼
                    API Endpoint
                         │
                         ▼
                  ECS/Fargate API
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
   RDS Postgres     ElastiCache          S3
                        Redis
                         │
                         ▼
                   Generation Queue
                         │
                         ▼
                  Fargate Worker
                         │
                         ▼
                     VTON APIs
```

Start with:

```text
1 API service
1 generation worker service
managed PostgreSQL
managed Redis
private S3
```

---



# 56. Do Not Use Kubernetes Initially

Avoid:

```text
Kubernetes
service mesh
Kafka
20 microservices
separate database per module
distributed transactions
```

Scale only after measured need.

---



# 57. CI Pipeline

Every pull request:

```text
backend compile
backend unit tests
backend integration tests
mobile TypeScript check
lint
mobile unit tests
Android debug build
```

Optional later:

```text
iOS build on macOS runner
security scan
dependency scan
```

---



# 58. Staging Deployment

On merge to main:

```text
build backend container
run tests
run security scan
push image
deploy staging
run migrations
run smoke tests
```

Production:

```text
manual approval
→ promote tested build
```

---



# 59. Database Deployment Strategy

Use Flyway.

For safe schema changes:

```text
EXPAND
↓
DEPLOY
↓
MIGRATE
↓
CONTRACT
```

Never combine destructive DB change with same-release code that depends on it.

---



# 60. Android Release Preparation

Configure:

```text
API 36 target
AAB
release signing
Play App Signing
privacy disclosures
Data Safety
screenshots
store listing
deep links
share target
push notifications
```

Test real shares from:

```text
Myntra
AJIO
Amazon
Chrome
Instagram
Gallery
Google Photos
```

---



# 61. iOS Release Preparation

Configure:

```text
main bundle ID
share-extension bundle ID
App Group
APNs
Sign in with Apple
privacy information
deep links
associated domains if needed
```

Use TestFlight before production.

---



# 62. Internal Alpha

Target:

```text
10–20 users
```

Focus on technical variability.

Test:

```text
Myntra
AJIO
Amazon
browser
Instagram
screenshots
gallery
URL only
image only
bad links
large images
bad avatars
different garments
```

Build compatibility matrix:

```text
Source        Android   iOS
--------------------------------
Myntra           ?       ?
AJIO             ?       ?
Chrome           ?       ?
Instagram        ?       ?
Gallery          ?       ?
```

Do not assume share payload behavior.

Measure it.

---



# 63. Alpha Exit Criteria

Required:

```text
Core app stable
No P0 crashes
No sensitive data leaks
Single-item VTON acceptable
Import fallback works
Account deletion works
Provider failure handled
```

---



# 64. Closed Beta

Target:

```text
100–500 users
```

Features enabled:

```text
Share
My Model
Vault
Collections
Studio
Single-item try-on
limited multi-item
Looks
Notifications
```

Apply generation quotas.

---



# 65. Beta Exit Gates

Suggested:

```text
Crash-free sessions        > 99.5%
Import success             > 90%
Technical generation       > 95%
Quality rejection          < 10%
P95 normal API latency     < 500ms
P95 generation             < 30s
Deletion flows             fully tested
No P0/P1 security issues
AI cost                    understood
```

Retention targets should be derived from real beta data.

---



# 66. Feature Flags

Create:

```text
MULTI_ITEM
NATURAL_LANGUAGE_STUDIO
TRYON_MAX
HD_GENERATION
SHARE_IMPORT_ANDROID
SHARE_IMPORT_IOS
NEW_QUALITY_GATE
```

Any risky AI feature must be remotely disableable.

---



# 67. Production Rollout

Use staged rollout.

```text
internal
 ↓
1%
 ↓
5%
 ↓
10%
 ↓
25%
 ↓
50%
 ↓
100%
```

At each stage inspect:

```text
crashes
generation failures
provider errors
quality rejects
latency
queue depth
cost/user
```

---



# 68. Alerting



## P0

```text
API down
database down
authentication broken
all generations failing
S3 unavailable
```



## P1

```text
provider failures > threshold
queue delay too high
quality failures spike
push failure spike
generation cost spike
provider credits low
```



## P2

```text
one retailer/source import degraded
one garment category quality degraded
```

---



# 69. AI-Specific Production Alert

A unique critical signal:

```text
Provider HTTP success = normal
BUT
Quality Gate pass rate collapses
```

This must alert.

AI products can technically “work” while the output quality is broken.

---



# 70. Daily Post-Production Operations

Every day inspect:

```text
crashes
import failures
generation failures
quality rejects
provider latency
queue latency
cost per Look
problematic categories
problematic source apps
```

---



# 71. Weekly Operations

Every week:

```text
AI regression benchmark
review failed generations
review source-import compatibility
review Vault reuse
review Studio usage
review retention
review generation cost
```

---



# 72. Monthly Operations

Every month:

```text
provider benchmark
cost optimization
dependency updates
privacy audit
security audit
data deletion test
backup restore drill
```

---



# 73. Provider Evolution

Maintain:

```text
ProviderRegistry
```

Store real production performance:

```text
            TOPS   ETHNIC   SHOES
v1.6          92      81      -
Try-On Max    96      91     89
Provider B    94      95     83
```

Metrics should include:

```text
quality
latency
cost
failure rate
category
```

---



# 74. Provider Routing

Future ProviderRouter:

```text
simple top
→ fast provider

ethnic garment
→ fidelity provider

shoe
→ provider optimized for footwear
```

The routing layer should optimize:

```text
quality
cost
latency
```

not just model popularity.

---



# 75. Cost Circuit Breakers

Create global safety thresholds.

Example:

```text
daily provider spend limit
per-user daily spend limit
per-device daily generation limit
unexpected cost-spike detection
```

On breach:

```text
disable HD generation
restrict multi-item
switch to cheaper provider
temporarily queue requests
```

---



# 76. Disaster Recovery

Production requirements:

```text
RDS automated backups
point-in-time recovery
S3 lifecycle/versioning where appropriate
Terraform
secret rotation procedure
provider outage procedure
```

Run actual restore tests.

Example:

```text
restore staging DB
↓
boot app
↓
run smoke tests
↓
verify data
```

---



# 77. Security Review Checklist

Before launch verify:

```text
[ ] auth tokens verified server-side
[ ] authorization on all user-owned entities
[ ] signed upload URLs expire quickly
[ ] upload validation enabled
[ ] storage buckets private
[ ] DB not publicly exposed
[ ] Redis not publicly exposed
[ ] provider key in secret manager
[ ] secrets excluded from logs
[ ] generation rate limiting
[ ] abuse detection
[ ] account deletion tested
[ ] raw images excluded from telemetry
```

---



# 78. Privacy Review Checklist

```text
[ ] explicit avatar-use consent
[ ] third-party inference disclosure
[ ] no training by default
[ ] deletion available
[ ] user can delete My Model
[ ] user can delete Vault item
[ ] user can delete Look
[ ] account deletion supported
[ ] temp image retention enforced
[ ] provider privacy documented
[ ] analytics excludes raw media
```

---



# 79. Release Gates



## Gate 1 — AI Core

Question:

> Can we reliably generate a good single-item Look?

If NO:

```text
STOP.
Do not build Vault complexity.
```

---



## Gate 2 — Share Flow

Question:

> Can Share → Try-On work reliably across real shopping apps?

If NO:

```text
STOP.
Fix import flow.
```

---



## Gate 3 — Product Retention

Question:

> Do beta users repeatedly use Share + Vault + Try-On?

If NO:

```text
STOP.
Do not build stylist/social/fit features.
```

---



# 80. Implementation Sequence

This is the preferred engineering order.

```text
01. Repository initialization
02. Backend skeleton
03. Mobile skeleton
04. Docker dev environment
05. PostgreSQL + Flyway
06. Authentication
07. Object storage
08. My Model
09. Avatar preflight

10. VtonProvider abstraction
11. FASHN integration
12. Async generation
13. Quality gate
14. Single-item Try On

──────── FIRST MAJOR GATE ────────

15. AI benchmark suite
16. Android Share Target
17. iOS Share Extension
18. Import Resolver
19. Product Normalizer

──────── SECOND MAJOR GATE ───────

20. Vault
21. Categories
22. Collections

23. Studio domain
24. Outfit compatibility
25. Render planner
26. Multi-item generation

──────── THIRD MAJOR GATE ────────

27. Studio AI tool calling
28. Looks
29. Push/deep links

30. Analytics
31. Privacy controls
32. Rate limiting
33. App integrity
34. Security hardening
35. Observability

──────── RELEASE GATE ────────────

36. Internal alpha
37. Import compatibility fixes
38. Closed beta
39. AI quality tuning
40. Load/cost testing
41. Store compliance
42. Staged production rollout

──────── POST PRODUCTION ─────────

43. Alerting
44. Quality monitoring
45. Cost monitoring
46. Retention analysis
47. Provider benchmarking
48. Privacy/deletion audits
49. Backup restore drills
50. Product iteration from measured usage
```

---



# 81. Phase-by-Phase Definition of Done



## Phase A — Foundation

Done when:

```text
repository works
mobile app boots
backend boots
Postgres works
Redis works
LocalStack works
auth works
CI passes
```

---



## Phase B — AI Core

Done when:

```text
avatar upload works
manual product image works
generation async pipeline works
provider abstraction works
quality gate works
Look displays
cost recorded
```

---



## Phase C — Share

Done when:

```text
Android share accepted
iOS share accepted
URL/image normalized
fallback works
share → Try Now works
```

---



## Phase D — Vault

Done when:

```text
add item
delete item
filter
category
collection
reuse item
source URL retained
```

---



## Phase E — Studio

Done when:

```text
structured slots
compatibility rules
add/replace/remove
render plan
single + multi-item render
```

---



## Phase F — Assistant

Done when:

```text
natural language maps to tools
no direct DB writes
ambiguous queries ask user
tool failures handled
Studio remains source of truth
```

---



## Phase G — Production

Done when:

```text
monitoring
alerts
rate limiting
privacy
security
push
store compliance
beta gates passed
```

---



# 82. Suggested API Surface



## Authentication/User

```http
GET    /me
DELETE /me
```



## Uploads

```http
POST   /uploads
POST   /uploads/{id}/complete
DELETE /uploads/{id}
```



## Avatar

```http
POST   /avatars
GET    /avatars
GET    /avatars/{id}
PATCH  /avatars/{id}
DELETE /avatars/{id}
POST   /avatars/{id}/set-primary
```



## Imports

```http
POST   /imports
GET    /imports/{id}
POST   /imports/{id}/resolve
POST   /imports/{id}/confirm
```



## Vault

```http
POST   /vault/items
GET    /vault/items
GET    /vault/items/{id}
PATCH  /vault/items/{id}
DELETE /vault/items/{id}
```



## Collections

```http
POST   /collections
GET    /collections
PATCH  /collections/{id}
DELETE /collections/{id}
POST   /collections/{id}/items
DELETE /collections/{id}/items/{itemId}
```



## Studio

```http
POST   /studio/sessions
GET    /studio/sessions/{id}
PATCH  /studio/sessions/{id}
POST   /studio/sessions/{id}/items
DELETE /studio/sessions/{id}/items/{slot}
POST   /studio/sessions/{id}/commands
```



## Generation

```http
POST   /generations
GET    /generations/{id}
POST   /generations/{id}/cancel
POST   /generations/{id}/retry
```



## Looks

```http
GET    /looks
GET    /looks/{id}
POST   /looks/{id}/regenerate
DELETE /looks/{id}
```



## Privacy

```http
POST   /privacy/export
POST   /privacy/delete-account
GET    /privacy/consents
PATCH  /privacy/consents
```

---



# 83. Example Generation Request

```json
{
  "avatarId": "avatar-uuid",
  "studioSessionId": "studio-uuid",
  "mode": "STANDARD"
}
```

Response:

```json
{
  "generationId": "generation-uuid",
  "status": "QUEUED"
}
```

Client polls or receives push/websocket update later.

---



# 84. Example Studio Command

```json
{
  "text": "Add my white Nike shoes and remove the cap"
}
```

Assistant returns an executable plan internally:

```json
{
  "actions": [
    {
      "type": "ADD_FROM_VAULT",
      "query": "white Nike shoes",
      "slot": "FEET"
    },
    {
      "type": "REMOVE_SLOT",
      "slot": "HEAD"
    }
  ]
}
```

Backend validates each action before mutation.

---



# 85. Failure UX

The product must fail gracefully.

## Product extraction failure

```text
We found the product link, but couldn't reliably extract the item image.

[Choose Screenshot]
[Upload Image]
```



## Avatar failure

```text
We need a clearer full-body photo to create an accurate preview.
```



## VTON quality failure

```text
We couldn't create an accurate preview for this item.

[Try Again]
[Choose Another Photo]
```



## Provider outage

```text
Try-on is temporarily unavailable.

Your item has been saved to the Vault.
```

---



# 86. Graceful Degradation Order

When systems fail:

```text
HD unavailable
→ use standard

preferred provider unavailable
→ fallback provider

generation unavailable
→ save Vault item

URL resolver unavailable
→ ask for screenshot

AI command unavailable
→ manual Studio controls
```

Core user-owned data should remain accessible even when AI is unavailable.

---



# 87. Post-Launch Product Decisions

Only after retention exists consider:

```text
“What should I wear?”
occasion recommendations
preference learning
multiple avatars
affiliate links
price tracking
merchant partnerships
fit prediction
body measurements
video try-on
social features
```

Every expansion must be justified by observed behavior.

---



# 88. Engineering Kill Criteria

Do not continue blindly if:

### AI quality

```text
Quality-approved result rate remains poor
despite provider changes
```



### Latency

```text
Mobile wait time makes repeat use negligible
```



### Cost

```text
Cost per retained user is structurally unsustainable
```



### Share compatibility

```text
Important shopping apps consistently provide unusable payloads
and fallback is too cumbersome
```



### Retention

```text
Users try the app once for novelty
but do not reuse Vault/Studio
```

These are product risks, not just engineering bugs.

---



# 89. Source-of-Truth Architecture

Final architecture:

```text
                        EXTERNAL APPS

                Shopping / Browser / Social
                           │
                           │ SHARE
                           ▼
               ┌───────────────────────────┐
               │ PLATFORM SHARE ADAPTERS   │
               │ Android Kotlin            │
               │ iOS Swift Extension       │
               └─────────────┬─────────────┘
                             ▼
                    IMPORT SERVICE
                             │
                   ┌─────────┴─────────┐
                   ▼                   ▼
                IMAGE                 URL
                   │                   │
                   └─────────┬─────────┘
                             ▼
                    PRODUCT NORMALIZER
                             │
                  ┌──────────┴──────────┐
                  ▼                     ▼
               TRY NOW                 VAULT
                  │                     │
                  │                COLLECTIONS
                  │                     │
                  └──────────┬──────────┘
                             ▼
                          STUDIO
                             │
                       structured slots
                             │
                             ▼
                        RENDER PLAN
                             │
                             ▼
                     VTON ORCHESTRATOR
                             │
               ┌─────────────┼──────────────┐
               ▼             ▼              ▼
            FASHN        Provider B      Future Model
               │
               └─────────────┬──────────────┘
                             ▼
                        QUALITY GATE
                             │
                   ┌─────────┴─────────┐
                   ▼                   ▼
                 PASS                 FAIL
                   │                   │
                   ▼                   └─ retry / fail safely
                  LOOK
                   │
              ┌────┴────┐
              ▼         ▼
            SAVE      MODIFY
              │         │
              ▼         └────→ STUDIO
             LOOKS
```

---



# 90. Final Engineering Principle

The first meaningful technical milestone is not:

```text
beautiful Home page
```

It is:

```text
Myntra / AJIO / Browser
          │
          │ Share
          ▼
      Your App
          │
          ▼
 product recognized
          │
          ▼
     Try on me
          │
          ▼
      My Model
          │
          ▼
        VTON
          │
          ▼
    Quality Gate
          │
          ▼
        LOOK
```

Once this works reliably on real devices, the rest of the application becomes a set of product layers around a proven engine.

If it does not work reliably, the correct engineering action is **not to add more features**. The correct action is to improve the core loop.

---



# 91. Recommended Milestone Summary



## Milestone 1 — Foundation

Deliver:

```text
repo
mobile
backend
auth
Postgres
Redis
S3
CI
```



## Milestone 2 — VTON Vertical Slice

Deliver:

```text
My Model
manual product
VTON
quality gate
Look
```



## Milestone 3 — Share

Deliver:

```text
Android Share
iOS Share
resolver
normalizer
fallback
```



## Milestone 4 — Vault

Deliver:

```text
categories
collections
search
source links
```



## Milestone 5 — Studio

Deliver:

```text
slots
compatibility
render plan
multi-item
```



## Milestone 6 — Assistant

Deliver:

```text
natural-language tool calls
deterministic mutations
```



## Milestone 7 — Production Readiness

Deliver:

```text
analytics
cost controls
security
privacy
push
observability
alerts
CI/CD
```



## Milestone 8 — Alpha/Beta

Deliver:

```text
real-device compatibility
quality validation
retention data
cost data
```



## Milestone 9 — Production

Deliver:

```text
staged rollout
monitoring
provider routing
post-production processes
```

---



# 92. Final Go/No-Go Logic

```text
CAN WE GENERATE A FAITHFUL LOOK?
              │
          NO ─┴─ YES
          │       │
          STOP    ▼
             CAN SHARE FLOW WORK
             ACROSS REAL APPS?
                   │
               NO ─┴─ YES
               │       │
               STOP    ▼
                 DO USERS REUSE
                 VAULT + TRY-ON?
                       │
                   NO ─┴─ YES
                   │       │
                   STOP    ▼
                    BUILD THE
                    DEEPER PRODUCT
```

This staged architecture prevents the team from spending months building speculative features before the core user behavior is proven.

---



# 93. Sources / Current Platform References

The implementation direction above was validated against current platform guidance available during planning:

- React Native release documentation: [https://reactnative.dev/](https://reactnative.dev/)
- Spring Boot system requirements and observability: [https://docs.spring.io/spring-boot/](https://docs.spring.io/spring-boot/)
- Android Sharesheet / receiving shared content: [https://developer.android.com/training/sharing/receive](https://developer.android.com/training/sharing/receive)
- Android Play Integrity: [https://developer.android.com/google/play/integrity](https://developer.android.com/google/play/integrity)
- Apple App Extensions / Share Extensions: [https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/)
- Apple App Groups: [https://developer.apple.com/documentation/xcode/configuring-app-groups](https://developer.apple.com/documentation/xcode/configuring-app-groups)
- Firebase Authentication: [https://firebase.google.com/docs/auth](https://firebase.google.com/docs/auth)
- Firebase Cloud Messaging: [https://firebase.google.com/docs/cloud-messaging](https://firebase.google.com/docs/cloud-messaging)
- AWS S3 presigned URLs: [https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- AWS S3 encryption/lifecycle documentation: [https://docs.aws.amazon.com/AmazonS3/](https://docs.aws.amazon.com/AmazonS3/)
- FASHN API documentation: [https://docs.fashn.ai/](https://docs.fashn.ai/)
- FASHN privacy and retention documentation: [https://docs.fashn.ai/api-overview/data-retention-privacy](https://docs.fashn.ai/api-overview/data-retention-privacy)

---



# 94. Final Note for Engineering / Agentic Implementation

If this plan is being executed by an autonomous coding orchestrator such as Aegis, the orchestrator should treat each milestone as a **hard validation boundary**.

The orchestrator should not automatically continue from:

```text
Milestone 2 → Milestone 3
```

simply because implementation completed.

It should first verify:

```text
tests
runtime behavior
device behavior
quality metrics
privacy constraints
latency
cost
```

and explicitly fail the milestone if the defined exit criteria are not met.

The desired implementation philosophy is:

> **Build the smallest working layer, validate it deeply, then earn permission to build the next layer.**

