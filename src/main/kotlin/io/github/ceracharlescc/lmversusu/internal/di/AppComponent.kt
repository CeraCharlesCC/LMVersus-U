package io.github.ceracharlescc.lmversusu.internal.di

import dagger.BindsInstance
import dagger.Component
import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.ApiController
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.GameController
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

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun appConfig(appConfig: AppConfig): Builder

        fun build(): AppComponent
    }
}
