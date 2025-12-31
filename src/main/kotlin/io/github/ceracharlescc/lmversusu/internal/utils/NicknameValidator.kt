package io.github.ceracharlescc.lmversusu.internal.utils

import kotlin.text.iterator

/**
 * Validates nicknames to prevent bandwidth/memory griefing attacks.
 *
 * Rules:
 * - Maximum 16 characters (Unicode-aware)
 * - Minimum 1 character (after trimming)
 * - Allowed: letters, digits, spaces, basic punctuation (-_.), emoji
 * - Disallowed: control characters, excessive whitespace
 */
internal object NicknameValidator {
    private const val MAX_LENGTH = 16
    private const val MIN_LENGTH = 1

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val errorCode: String, val message: String) : ValidationResult
    }

    /**
     * Validates a nickname string.
     *
     * @param nickname The nickname to validate (will be trimmed)
     * @return ValidationResult indicating success or specific failure reason
     */
    fun validate(nickname: String): ValidationResult {
        val trimmed = nickname.trim()

        // Check minimum length
        if (trimmed.length < MIN_LENGTH) {
            return ValidationResult.Invalid(
                errorCode = "invalid_nickname",
                message = "nickname is required"
            )
        }

        // Check maximum length (counts Unicode characters correctly)
        if (trimmed.length > MAX_LENGTH) {
            return ValidationResult.Invalid(
                errorCode = "nickname_too_long",
                message = "nickname must be at most $MAX_LENGTH characters"
            )
        }

        // Check for control characters and validate character set
        if (!isValidCharacterSet(trimmed)) {
            return ValidationResult.Invalid(
                errorCode = "nickname_invalid_chars",
                message = "nickname contains invalid characters"
            )
        }

        // Check for excessive whitespace patterns
        if (hasExcessiveWhitespace(trimmed)) {
            return ValidationResult.Invalid(
                errorCode = "nickname_invalid_chars",
                message = "nickname contains excessive whitespace"
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Checks if the nickname contains only allowed characters:
     * - Unicode letters and digits
     * - Spaces
     * - Basic punctuation: hyphen, underscore, period
     * - Emoji and other Unicode symbols (but not control characters)
     */
    private fun isValidCharacterSet(nickname: String): Boolean {
        for (char in nickname) {
            when {
                // Allow Unicode letters and digits
                char.isLetterOrDigit() -> continue
                // Allow space
                char == ' ' -> continue
                // Allow basic punctuation
                char in setOf('-', '_', '.') -> continue
                // Disallow control characters (ASCII 0-31 and 127)
                char.isISOControl() -> return false
                // Allow other Unicode characters (emoji, symbols, etc.)
                else -> continue
            }
        }
        return true
    }

    /**
     * Checks for excessive whitespace patterns (multiple consecutive spaces).
     */
    private fun hasExcessiveWhitespace(nickname: String): Boolean {
        return nickname.contains(Regex("\\s{2,}"))
    }
}