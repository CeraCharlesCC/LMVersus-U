package io.github.ceracharlescc.lmversusu.internal.domain.repository

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec

/**
 * Repository for loading opponent specifications from modelspec files.
 */
internal interface OpponentSpecRepository {
    /**
     * Returns all available opponent specifications.
     *
     * @return List of specs, or null if loading fails.
     */
    fun getAllSpecs(): List<OpponentSpec>?
}
