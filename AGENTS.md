# Coding Rules

### Terminology
- Required / Prohibited: this is must. In other words, you absolutely have to do so.
- Should be done / Should be avoided: this is should. In other words, it is preferable to do so, but edge cases ought to be allowed.
- Desirable / Better not to do: this is idealism. In other words, it means you should aim for it.

## Design

As a base, we follow the principles of Clean Architecture. That is:

- ### Encapsulation, Abstraction, and Dependency Inversion:
    1. `infrastructure.dao.*`, which directly accesses DB/File (IO), must be hidden behind `domain.repository.*` (interfaces).
       The same applies to other external services (HTTP APIs, messaging APIs, LLM APIs, etc.): they must be accessed via repositories or dedicated gateway interfaces.
    2. `domain.usecase.*` must not access dao / external clients directly. If you want to access them in a use case, you need to go through the repository (or gateway) interfaces.
    3. Implementations of repositories must be placed in `infrastructure.repository.*`.
    4. Data passed between classes/instances/functions must be defined as data class / value object under `domain.[entity/vo]`.
       However, as long as the passing occurs within a single class, it should be permissible to define it within the class.
    5. In `presentation.*`, **Command/EventListener** (e.g., framework command handler, event listener) and **Controller** must be separated.
       The Command/EventListener should:
        - Focus on early returns and event decomposition.
        - Extract fields from the framework Event/Context.
        - Convert (“un-framework-fy”) them into domain-/application-friendly values.
        - Pass only what is necessary to the Controller’s functions.
    6. The principle of the least privilege must be strictly observed.
        - Controllers and UseCases must not receive full framework Event/Context objects.
        - Unless the Controller needs a specific value, passing it is prohibited.
        - Passing the entire Event/Context itself is prohibited.
        - Framework-specific types (IDs, locale types, message objects, etc.) should be converted at the Command/EventListener boundary into simple primitives or domain/value types.
    7. Controller bloat should be avoided.
        - Break responsibilities down properly into use cases.
        - The Controller’s business should be restricted to orchestrating use cases and mapping their results into simple response models for the outer layers (e.g., what kind of message to show), not doing complex business logic itself.
    8. `usecase` should, when necessary (for example, when multiple errors are involved behind it), return `sealed interface [UseCase].Result.[Success(val data; if necessary) / Failure.Reason(val data; if necessary)]` as its result.
    9. Anything related to dependency injection must be placed under `.di.*`.
    10. Static, side-effect-free code that may be used from all layers (e.g., algorithms, helpers, extensions) is desirable to place under `.utils.*`.
    11. However, as with all of these, if the project becomes too large, you should separate the modules. If utils becomes a garbage can, it's time to separate the modules.

- ### Injection:
    1. Instances such as Controllers and Usecases must be injected via KSP + Dagger.
    2. When appropriate, being a Singleton is desirable.

## Implementation

- ### Style:
    1. As a rule, do not write code comments; it is desirable to write code whose intent and behavior can be understood just by reading the code, variable names, and function names. However, non-obvious trade-offs, constraints, and the intent of special algorithms should be left as comments.
    2. Do not merely follow instructions; if there is something you do not understand, ask questions, propose better solutions, and code with responsibility and initiative not just as an agent but as a colleague.
    3. Variable names should not be abbreviated. For example, use `event: InputEvent` instead of `e: InputEvent` (at least when what `event` refers to is unique within the function. If not, use something like `inputEvent`). However, common abbreviations such as `url`, `io`, and `id` are permitted.
    4. Coding conventions must follow the official Kotlin guidelines.

- ### Principle:
    1. Reduce side effects as much as possible. [Should be done]
       This also ties into system design; mixing business logic and I/O is Better not to do.
       For example, delegate validation to the entity data class’s companion object or `@JvmInline value class` so that a single function does not become bloated.
    2. Be mindful of scope and extract private functions. [Should be done]
       Keep variable scopes small.
       Functions with a narrowly bounded scope are easier to understand and simpler to modify.
    3. DRY. [Required]
       Unless there is a justified reason, duplicate code must be reduced or eliminated. But watch the classic trap: over-abstraction. Duplication is acceptable until the third repetition makes the abstraction obvious.

- ### Niche:
    1. In implementations that involve blocking I/O, `withContext(Dispatchers.IO)` should be done by the RepositoryImpl.
    2. You must always run detekt before committing. (Hmm, but it's probably impossible to fully comply in the jda environment, so consider it only as assistance for formatting and such.)