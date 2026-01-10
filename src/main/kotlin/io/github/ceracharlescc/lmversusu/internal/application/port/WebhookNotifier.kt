package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.vo.WebhookEvent

internal interface WebhookNotifier {
    suspend fun notify(event: WebhookEvent)
}
