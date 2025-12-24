package io.github.ceracharlescc.lmversusu.internal.di

import dagger.BindsInstance
import dagger.Component
import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.ApiController
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        AppModule::class,
        RepositoryModule::class,
    ]
)
internal interface AppComponent {
    fun apiController(): ApiController

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun appConfig(appConfig: AppConfig): Builder

        fun build(): AppComponent
    }
}
