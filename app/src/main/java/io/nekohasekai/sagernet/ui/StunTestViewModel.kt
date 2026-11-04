// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.utils.stun.DefaultStunSocketFactory
import io.nekohasekai.sagernet.utils.stun.StunAssessment
import io.nekohasekai.sagernet.utils.stun.StunAssessmentKind
import io.nekohasekai.sagernet.utils.stun.StunAssessmentPolicy
import io.nekohasekai.sagernet.utils.stun.StunNatTester
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
        ipv6Mode: Int,
        socketFactory: StunSocketFactory = DefaultStunSocketFactory(),
    ) {
        cancel()
        val natTester = StunNatTester(socketFactory)
        tester = natTester

        _uiState.update {
            it.copy(
                isRunning = true,
                results = emptyList(),
                assessment = null,
                totalServers = servers.size,
            )
        }

        testJob = viewModelScope.launch {
            val results = natTester.test(servers, ipv6Mode)
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
            _uiState.update {
                it.copy(
                    isRunning = false,
                    assessment = StunAssessment(StunAssessmentKind.CANCELLED),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancel()
    }
}
