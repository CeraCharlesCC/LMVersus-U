package io.github.ceracharlescc.lmversusu.internal

internal object TestConfigFactory {

    /**
     * 16-byte test encryption key (32 hex chars) for AES-128.
     * DO NOT use in production!
     */
    private const val TEST_ENCRYPTION_KEY_HEX =
        "0123456789abcdef0123456789abcdef"

    /**
     * 32-byte test signing key (64 hex chars).
     * DO NOT use in production!
     */
    private const val TEST_SIGN_KEY_HEX =
        "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    fun createTestConfig(
        llmApiKey: String = "test-api-key",
        llmProvider: String = "openai",
    ): AppConfig = AppConfig(
        llmConfig = AppConfig.LlmConfig(
            primary = AppConfig.LlmProviderConfig(
                apiKey = llmApiKey,
                provider = llmProvider,
            ),
        ),
        sessionCrypto = AppConfig.SessionCryptoConfig(
            enableSecureCookie = false,
            encryptionKeyHex = TEST_ENCRYPTION_KEY_HEX,
            signKeyHex = TEST_SIGN_KEY_HEX,
        ),
    )
}
