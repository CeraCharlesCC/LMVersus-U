package io.github.ceracharlescc.lmversusu.internal.di

import dagger.BindsInstance
import dagger.Component
import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.ApiController
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.GameController
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.GameEventFrameMapper
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        AppModule::class,
        InfrastructureModule::class,
    ]
)
internal interface AppComponent {
    fun apiController(): ApiController
    fun gameController(): GameController
    fun gameEventBus(): GameEventBus
    fun gameEventFrameMapper(): GameEventFrameMapper

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun appConfig(appConfig: AppConfig): Builder

        fun build(): AppComponent
    }
}
