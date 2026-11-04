// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import org.junit.Assert.assertEquals
import org.junit.Test

class StunAssessmentPolicyTest {

    @Test
    fun assessmentRequiresConsistentCompleteResults() {
        val favorable = result(
            mapping = StunProtocolCodes.BEHAVIOR_ENDPOINT,
            filtering = StunProtocolCodes.BEHAVIOR_ENDPOINT,
        )
        assertEquals(
            StunAssessmentKind.FAVORABLE,
            StunAssessmentPolicy.assess(listOf(favorable)).kind,
        )

        val restrictive = result(
            mapping = StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT,
            filtering = StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT,
        )
        assertEquals(
            StunAssessmentKind.INCONSISTENT,
            StunAssessmentPolicy.assess(listOf(favorable, restrictive)).kind,
        )
    }

    @Test
    fun assessmentDistinguishesPartialAndFailedRuns() {
        assertEquals(
            StunAssessmentKind.BASIC_ONLY,
            StunAssessmentPolicy.assess(
                listOf(result(behaviorComplete = false, bindingSuccess = true)),
            ).kind,
        )
        assertEquals(
            StunAssessmentKind.FAILED,
            StunAssessmentPolicy.assess(
                listOf(result(behaviorComplete = false, bindingSuccess = false)),
            ).kind,
        )
    }

    @Test
    fun warningDoesNotDowngradeAnOtherwiseCompleteResult() {
        assertEquals(
            StunAssessmentKind.FAVORABLE,
            StunAssessmentPolicy.assess(
                listOf(result(warningCode = "response_address_mismatch")),
            ).kind,
        )
    }

    @Test
    fun assessmentMapsEveryUserImpactTier() {
        assertEquals(
            StunAssessmentKind.MODERATE,
            StunAssessmentPolicy.assess(
                listOf(result(filtering = StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT)),
            ).kind,
        )
        assertEquals(
            StunAssessmentKind.RESTRICTIVE,
            StunAssessmentPolicy.assess(
                listOf(result(mapping = StunProtocolCodes.BEHAVIOR_ADDRESS)),
            ).kind,
        )
        assertEquals(
            StunAssessmentKind.OPEN,
            StunAssessmentPolicy.assess(
                listOf(result(natType = StunProtocolCodes.NAT_NONE)),
            ).kind,
        )
        assertEquals(
            StunAssessmentKind.CANCELLED,
            StunAssessmentPolicy.assess(
                listOf(result(behaviorComplete = false, errorCode = "cancelled")),
            ).kind,
        )
    }

    private fun result(
        behaviorComplete: Boolean = true,
        bindingSuccess: Boolean = true,
        mapping: Int = StunProtocolCodes.BEHAVIOR_ENDPOINT,
        filtering: Int = StunProtocolCodes.BEHAVIOR_ENDPOINT,
        natType: Int = StunProtocolCodes.NAT_FULL,
        errorCode: String = "",
        warningCode: String = "",
    ) = StunServerUiResult(
        server = "stun.example.com:3478",
        bindingSuccess = bindingSuccess,
        behaviorSupported = behaviorComplete,
        behaviorComplete = behaviorComplete,
        natType = natType,
        mappingBehavior = mapping,
        filteringBehavior = filtering,
        externalAddress = "192.0.2.1",
        externalPort = 40000,
        ipFamily = 1,
        durationMilliseconds = 100,
        errorCode = errorCode,
        errorMessage = "",
        warningCode = warningCode,
    )
}
