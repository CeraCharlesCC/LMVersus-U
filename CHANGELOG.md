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

