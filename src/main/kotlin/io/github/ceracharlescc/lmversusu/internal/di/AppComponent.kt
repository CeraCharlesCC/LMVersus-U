package io.github.ceracharlescc.lmversusu.internal.di

import dagger.BindsInstance
import dagger.Component
import io.github.ceracharlescc.lmversusu.internal.AppConfig
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        AppModule::class
    ]
)
internal interface AppComponent {


    @Component.Builder
    interface Builder {
        @BindsInstance
        fun appConfig(appConfig: AppConfig): Builder

        fun build(): AppComponent
    }
}
