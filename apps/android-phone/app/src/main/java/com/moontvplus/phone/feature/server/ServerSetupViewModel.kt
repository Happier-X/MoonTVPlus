package com.moontvplus.phone.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.BuildConfig
import com.moontvplus.phone.core.datastore.SettingsStore
import com.moontvplus.phone.core.model.ServerConfig
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.data.repository.ServerProbeResult
import com.moontvplus.phone.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val input: String = BuildConfig.BASE_URL,
        val probing: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
        val siteName: String? = null,
        val siteVersion: String? = null,
        val storageType: String? = null,
        val readyAddress: ServerAddress? = null,
    ) {
        val canSave: Boolean get() = readyAddress != null && !saving
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var readyConfig: ServerConfig? = null

    init {
        // 改地址时预填当前地址，首次设置时用打包时的 BASE_URL
        viewModelScope.launch {
            val stored = settingsStore.snapshot().serverAddress
            if (!stored.isNullOrBlank()) {
                _state.value = _state.value.copy(input = ServerAddress.parseStored(stored)?.display() ?: stored)
            }
        }
    }

    fun onInputChange(value: String) {
        _state.value = _state.value.copy(
            input = value,
            error = null,
            readyAddress = null,
            siteName = null,
            siteVersion = null,
            storageType = null,
        )
        readyConfig = null
    }

    fun probe() {
        val input = _state.value.input.trim()
        if (input.isEmpty()) {
            _state.value = _state.value.copy(error = "请填写服务器地址")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(probing = true, error = null)
            when (val result = repository.probe(input)) {
                is ServerProbeResult.Success -> {
                    readyConfig = result.config
                    _state.value = _state.value.copy(
                        probing = false,
                        error = null,
                        readyAddress = result.address,
                        siteName = result.config.SiteName,
                        siteVersion = result.config.Version,
                        storageType = result.config.StorageType,
                    )
                }
                is ServerProbeResult.Failed -> {
                    _state.value = _state.value.copy(
                        probing = false,
                        error = result.message,
                        readyAddress = null,
                    )
                }
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val address = _state.value.readyAddress ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            repository.save(address, readyConfig)
            _state.value = _state.value.copy(saving = false)
            onDone()
        }
    }
}
