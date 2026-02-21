package com.lilclaw.app.di

import com.lilclaw.app.data.GatewayClient
import com.lilclaw.app.data.SettingsRepository
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.ui.settings.SettingsViewModel
import com.lilclaw.app.ui.setup.SetupViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { SettingsRepository(androidContext()) }
    single { GatewayManager(androidContext()) }
    single { GatewayClient() }

    viewModel { SetupViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
