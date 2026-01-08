## [v0.7.0] - 2026-01-08
### Added
- Add bottom panel workspace with answer summary, scratchpad, and calculator (#17)
- Add model badges for question set and difficulty in the selector UI (#17)
- Add model stats display (speed, efficiency) to selector UI (#17)
- Add rich tooltips for Question Set badges on desktop and detail modal on mobile (#17)
- Add clear button state management and improve session recovery flow (#17)
- Add close button and Escape key support to the match end modal (#17)

### Changed
- Redesign model selector with a custom dropdown widget for better UX (#17)
- Refactor frontend from monolithic app.js to modular structure under app/ (#17)
- Refactor OpponentSpec to nest metadata and provider config for better organization (#17)
- Make OpponentMetadata fields non-nullable and add difficulty field (#17)
- Improve WebSocket handling to ensure events affect only current connection (#17)
- Simplify MCQ answer summary to show only the selected number (#17)
- Improve model selector accessibility with ARIA roles and keyboard navigation (#17)

### Fixed
- Fix session end handling: Next button now returns to lobby and resets state (#17)
- Fix answer form display and result details placement in post-round state (#17)

### Documentation
- Update README.md and add Shields.io attribution to license-frontend.html (#17)

### Internal
- Bump version to v0.7.0 and update model specs with new metadata fields (#17)
- Remove unused functions and imports across frontend modules (#17)
- Standardize import formatting and remove obsolete UI elements (#17)

## [v0.6.0] - 2026-01-07
### Added
- Add model descriptions and i18n support for UI and model metadata (#16)
- Add landing popup for first-time visitors with localized text (#16)
- Add close button to toast notifications for manual dismissal (#16)
- Add AGPL-3.0 license and third-party license handling (#15)

### Changed
- Refactor i18n to use external language files (en/ja) for better maintainability (#16)
- Switch logging from Logback to Log4j2 and update rate limiting to Ktor's built-in plugin (#15)
- Remove llmProfile from OpponentSpec serialization to simplify data model (#16)
- Improve 429 error handling in routing (#15)

### Fixed
- Fix casing of descriptionI18nKey in model configs (#16)

### Documentation
- Add license-dataset.html page (#15)

### Internal
- Refactor imports and formatting across Kotlin, JS, and HTML files (#15)
- Consolidate and clean up test imports and formatting (#15)

## [v0.5.0] - 2026-01-06
### Changed
- Refactor player active session handling into ApiController and remove PlayerActiveSessionController (#14)
- Refactor session limit logic into unified SessionCreationGate component (#14)
- Refactor PlayerActiveSessionIndex to repository pattern with PlayerActiveSessionRepository (#14)
- Refactor round handling and add Question utilities to reduce duplication (#14)
- Nest JoinResponse sealed interface inside SessionActor for better encapsulation (#14)

### Internal
- Add GitHub Actions CI and CodeQL workflows (#13)
- Bump CodeQL init action to v4 for improved support (#13)
- Reorder import statements in game modules for consistency (#14)
- Delete unused license-dataset.html resource (#14)

## [v0.4.0] - 2026-01-04
### Added
- Add license reporting for dataset and frontend dependencies, with dedicated UI pages (#12)

### Changed
- Redesign UI with a new warm neutral color palette, gold/amber accents, and Inter font
- Improve component styling, spacing, gradients, and border radii for a modern look
- Update index.html with new meta tags and Google Fonts for better accessibility

### Documentation
- Update README.md

### Internal
- Bump version to v0.4.0
- Integrate dependency-license-report Gradle plugin and include reports in JARs

## [v0.3.1] - 2026-01-04
### Added
- Add LICENSE modal to the frontend UI with keyboard accessibility and dynamic loading.

### Changed
- Refactor LLM answer submission logic in SessionActor into a reusable method.
- Update HandicapPolicy.kt.

### Fixed
- Add fallback to submit a default answer when OpenAI streaming fails, based on question type.
- Introduce OpenAIApiStreamException to distinguish OpenAI-specific errors in OpenAIApiDao.

### Internal
- Bump version.

## [v0.3.0] - 2026-01-03
### Added
- Add session recovery allowing players to rejoin and receive a round snapshot
- Add 'Give Up' button to forfeit an active match
- Add player active session management API and infrastructure
- Add expectedAnswerType to round started event
- Add WsLlmThinking frame for LlmThinking game event
- Add VERY_EASY and VERY_HARD difficulty levels
- Add idempotency to round start and answer submit commands via commandId
- Add replay_generator.py utility script

### Changed
- Refactor session models and commands into separate files for clarity
- Update handicap multipliers and set LIGHTWEIGHT_BASE_HANDICAP to 1 second
- Refine Japanese descriptions and reformat CSS for better readability
- Persist nickname in local storage and improve UI/UX on rejoin

### Fixed
- Fix binding creation on invalid opponent spec and heal missing bindings
- Harden session join and termination security checks to prevent hijacking
- Handle ClosedReceiveChannelException gracefully in SessionActor

### Security
- Enforce single active session per player and add ownership checks

### Documentation
- Update README to reflect app's benchmark feature
- Remove architecture and development sections from README
- Delete PLAN.md

### Internal
- Clean up unused imports and code across multiple files
- Extract ConnectionRateLimiter to a utility file
- Update CI release workflow configuration

