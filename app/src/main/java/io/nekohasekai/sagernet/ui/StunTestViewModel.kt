// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.utils.stun.DefaultStunSocketFactory
import io.nekohasekai.sagernet.utils.stun.StunAssessment
import io.nekohasekai.sagernet.utils.stun.StunAssessmentKind
import io.nekohasekai.sagernet.utils.stun.StunAssessmentPolicy
import io.nekohasekai.sagernet.utils.stun.StunNatTester
import io.nekohasekai.sagernet.utils.stun.StunServerStatus
import io.nekohasekai.sagernet.utils.stun.StunServerUiResult
import io.nekohasekai.sagernet.utils.stun.StunSocketFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StunUiState(
    val isRunning: Boolean = false,
    val results: List<StunServerUiResult> = emptyList(),
    val assessment: StunAssessment? = null,
    val totalServers: Int = 0,
)

class StunTestViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StunUiState())
    val uiState: StateFlow<StunUiState> = _uiState.asStateFlow()

    @Volatile private var tester: StunNatTester? = null
    private var testJob: Job? = null

    fun start(
        servers: List<String>,
        ipv6Mode: Int = 0,
        socketFactory: StunSocketFactory = DefaultStunSocketFactory(),
    ) {
        cancel()
        val natTester = StunNatTester(socketFactory)
        tester = natTester

        val initialResults = servers.map { server ->
            StunServerUiResult(
                server = server,
                status = StunServerStatus.PENDING,
            )
        }

        _uiState.update {
            it.copy(
                isRunning = true,
                results = initialResults,
                assessment = null,
                totalServers = servers.size,
            )
        }

        testJob = viewModelScope.launch {
            val results = natTester.test(servers, ipv6Mode) { updatedResult ->
                _uiState.update { current ->
                    val updatedList = current.results.map {
                        if (it.server == updatedResult.server) updatedResult else it
                    }
                    current.copy(results = updatedList)
                }
            }
            _uiState.update {
                it.copy(
                    isRunning = false,
                    results = results,
                    assessment = StunAssessmentPolicy.assess(results),
                )
            }
            if (tester === natTester) tester = null
        }
    }

    fun cancel() {
        tester?.cancel()
        tester = null
        testJob?.cancel()
        testJob = null
        if (_uiState.value.isRunning) {
            _uiState.update { current ->
                val cancelledResults = current.results.map {
                    if (it.status == StunServerStatus.RUNNING || it.status == StunServerStatus.PENDING) {
                        it.copy(
                            status = StunServerStatus.CANCELLED,
                            errorCode = "cancelled",
                            errorMessage = "Test cancelled",
                        )
                    } else it
                }
                current.copy(
                    isRunning = false,
                    results = cancelledResults,
                    assessment = StunAssessmentPolicy.assess(cancelledResults),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancel()
    }
}
