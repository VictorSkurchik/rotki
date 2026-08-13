package org.rotki.mobile.core.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompanionTransitionFixtureTest {
    @Test
    fun `every authored source-state transition edge matches the coordinator`() {
        val transitions = ProtocolFixtureData.cases.getValue("root_transitions").jsonArray
        val observedEvents = mutableSetOf<CompanionTransitionEvent>()
        var expandedEdgeCount = 0

        transitions.forEach { element ->
            val case = element.jsonObject
            val id = case.string("id")
            val event = assertNotNull(
                CompanionTransitionEvent.fromCode(case.string("stimulus")),
                id,
            )
            observedEvents += event
            val expectedTarget = rootState(case.string("to"))
            val expectedEffects = CompanionTransitionEffects(
                deviceSession = DeviceSessionEffect.entries.single {
                    effect -> effect.code == case.string("device_session_effect")
                },
                snapshot = SnapshotEffect.entries.single {
                    effect -> effect.code == case.string("snapshot_effect")
                },
                bearer = BearerEffect.entries.single {
                    effect -> effect.code == case.string("bearer_effect")
                },
            )
            assertTrue(case.string("recovery") in RECOVERY_CODES, "$id recovery")

            case.getValue("from").jsonArray.forEach { sourceElement ->
                val source = rootState(sourceElement.jsonPrimitive.content)
                val coordinator = CompanionCoordinator(
                    CompanionStatus(source, initialCoverage(source)),
                )

                val applied = assertIs<CompanionTransitionOutcome.Applied>(
                    coordinator.transition(event),
                    "$id from ${source.code}",
                )

                assertEquals(expectedTarget, applied.status.rootState, id)
                assertEquals(expectedEffects, applied.effects, id)
                assertEquals(applied.status, coordinator.status.value, id)
                expandedEdgeCount += 1
            }
        }

        assertEquals(22, transitions.size)
        assertEquals(58, expandedEdgeCount)
        assertEquals(CompanionTransitionEvent.entries.toSet(), observedEvents)
    }
}

private fun rootState(code: String): CompanionRootState =
    CompanionRootState.entries.single { state -> state.code == code }

private fun initialCoverage(state: CompanionRootState): SnapshotCoverage = when (state) {
    CompanionRootState.Unpaired,
    CompanionRootState.Revoked,
    -> SnapshotCoverage.Absent
    CompanionRootState.Degraded -> SnapshotCoverage.Degraded
    else -> SnapshotCoverage.Complete
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private val RECOVERY_CODES: Set<String> = setOf(
    "authenticate_device",
    "complete_pairing",
    "discover_and_prove",
    "discover_prove_and_reconcile",
    "none",
    "observe_refresh",
    "open_bound_profile",
    "pair_again",
    "prove_device",
    "prove_device_to_classify",
    "refresh_or_use_full_client",
    "renew_and_reconcile",
    "request_challenge",
    "retry_transport",
    "scan_pairing",
    "unlock_full_client",
    "upgrade_engine",
)
