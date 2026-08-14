package org.rotki.mobile.feature.pairing.presentation

import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PairingReducerTest {
    @Test
    fun `public vocabularies are complete`() {
        assertEquals(
            setOf(
                PairingScreenState.INTRO,
                PairingScreenState.SCANNING,
                PairingScreenState.CAMERA_DENIED,
                PairingScreenState.SCANNER_UNAVAILABLE,
                PairingScreenState.INVALID_QR,
                PairingScreenState.EXPIRED_QR,
                PairingScreenState.CONNECTING,
            ),
            PairingScreenState.entries.toSet(),
        )
        assertEquals(
            setOf(
                PairingFailureCategory.MALFORMED,
                PairingFailureCategory.UNSUPPORTED,
                PairingFailureCategory.EXPIRED,
            ),
            PairingFailureCategory.entries.toSet(),
        )
    }

    @Test
    fun `initial state reflects only an active connection attempt`() {
        assertEquals(state(PairingScreenState.INTRO), PairingReducer.initial(sessionIsConnecting = false))
        assertEquals(state(PairingScreenState.CONNECTING), PairingReducer.initial(sessionIsConnecting = true))
    }

    @Test
    fun `start scanning changes every state except connecting`() {
        ALL_STATES.forEach { current ->
            val expected =
                if (current.screen == PairingScreenState.CONNECTING) {
                    current
                } else {
                    state(PairingScreenState.SCANNING)
                }

            assertEquals(expected, PairingReducer.reduce(current, PairingAction.StartScanning), current.screen.name)
        }
    }

    @Test
    fun `scanner failures change only scanning`() {
        listOf(
            PairingAction.CameraPermissionDenied to PairingScreenState.CAMERA_DENIED,
            PairingAction.ScannerUnavailable to PairingScreenState.SCANNER_UNAVAILABLE,
        ).forEach { (action, expectedScreen) ->
            ALL_STATES.forEach { current ->
                val expected =
                    if (current.screen == PairingScreenState.SCANNING) {
                        state(expectedScreen)
                    } else {
                        current
                    }

                assertEquals(expected, PairingReducer.reduce(current, action), "${action::class} ${current.screen}")
            }
        }
    }

    @Test
    fun `authorized submission outcomes remain deterministic after an intermediate action`() {
        val cases =
            listOf(
                PairingSubmissionOutcome.Accepted to state(PairingScreenState.CONNECTING),
                rejected(PairingSubmissionRejection.MALFORMED) to
                    state(PairingScreenState.INVALID_QR, PairingFailureCategory.MALFORMED),
                rejected(PairingSubmissionRejection.UNSUPPORTED) to
                    state(PairingScreenState.INVALID_QR, PairingFailureCategory.UNSUPPORTED),
                rejected(PairingSubmissionRejection.EXPIRED) to
                    state(PairingScreenState.EXPIRED_QR, PairingFailureCategory.EXPIRED),
                PairingSubmissionOutcome.Ignored to state(PairingScreenState.SCANNING),
            )

        cases.forEach { (outcome, expectedFromScanning) ->
            ALL_STATES.forEach { current ->
                val expected =
                    if (outcome == PairingSubmissionOutcome.Ignored) current else expectedFromScanning
                val action = PairingAction.SubmissionCompleted(outcome)

                assertEquals(expected, PairingReducer.reduce(current, action), "$outcome ${current.screen}")
            }
        }
    }

    @Test
    fun `retry returns precisely recoverable failures to scanning`() {
        ALL_STATES.forEach { current ->
            val expected =
                if (
                    current.screen == PairingScreenState.CAMERA_DENIED ||
                    current.screen == PairingScreenState.EXPIRED_QR ||
                    current.screen == PairingScreenState.INVALID_QR ||
                    current.screen == PairingScreenState.SCANNER_UNAVAILABLE
                ) {
                    state(PairingScreenState.SCANNING)
                } else {
                    current
                }

            assertEquals(expected, PairingReducer.reduce(current, PairingAction.RetryScanning), current.screen.name)
        }
    }

    @Test
    fun `reset preserves only a still-owned connecting attempt`() {
        listOf(false, true).forEach { sessionIsUnpaired ->
            ALL_STATES.forEach { current ->
                val expected =
                    if (current.screen == PairingScreenState.CONNECTING && !sessionIsUnpaired) {
                        current
                    } else {
                        state(PairingScreenState.INTRO)
                    }

                assertEquals(
                    expected,
                    PairingReducer.reduce(current, PairingAction.Reset(sessionIsUnpaired)),
                    "${current.screen} unpaired=$sessionIsUnpaired",
                )
            }
        }
    }

    @Test
    fun `submission eligibility is exact`() {
        ALL_STATES.forEach { current ->
            assertEquals(
                current.screen == PairingScreenState.SCANNING,
                PairingReducer.acceptsSubmission(current),
                current.screen.name,
            )
        }
    }

    @Test
    fun `state constructor enforces the complete screen and failure cross product`() {
        PairingScreenState.entries.forEach { screen ->
            (listOf(null) + PairingFailureCategory.entries).forEach { failure ->
                val valid =
                    when (screen) {
                        PairingScreenState.INVALID_QR -> {
                            failure == PairingFailureCategory.MALFORMED ||
                                failure == PairingFailureCategory.UNSUPPORTED
                        }

                        PairingScreenState.EXPIRED_QR -> {
                            failure == PairingFailureCategory.EXPIRED
                        }

                        else -> {
                            failure == null
                        }
                    }

                if (valid) {
                    assertEquals(screen, PairingState(screen, failure).screen)
                } else {
                    assertFailsWith<IllegalArgumentException> { PairingState(screen, failure) }
                }
            }
        }
    }

    @Test
    fun `state and actions never represent QR authority`() {
        val representations =
            (
                ALL_STATES +
                    listOf(
                        PairingAction.StartScanning,
                        PairingAction.CameraPermissionDenied,
                        PairingAction.ScannerUnavailable,
                        PairingAction.SubmissionCompleted(
                            rejected(PairingSubmissionRejection.MALFORMED),
                        ),
                        PairingAction.RetryScanning,
                        PairingAction.Reset(sessionIsUnpaired = true),
                    )
            ).joinToString()

        SECRET_MARKERS.forEach { marker ->
            assertFalse(marker in representations, marker)
        }
    }
}

private fun rejected(reason: PairingSubmissionRejection): PairingSubmissionOutcome =
    PairingSubmissionOutcome.Rejected(reason)

private fun state(
    screen: PairingScreenState,
    failure: PairingFailureCategory? = null,
): PairingState = PairingState(screen, failure)

private val ALL_STATES: List<PairingState> =
    listOf(
        state(PairingScreenState.INTRO),
        state(PairingScreenState.SCANNING),
        state(PairingScreenState.CAMERA_DENIED),
        state(PairingScreenState.SCANNER_UNAVAILABLE),
        state(PairingScreenState.INVALID_QR, PairingFailureCategory.MALFORMED),
        state(PairingScreenState.INVALID_QR, PairingFailureCategory.UNSUPPORTED),
        state(PairingScreenState.EXPIRED_QR, PairingFailureCategory.EXPIRED),
        state(PairingScreenState.CONNECTING),
    )
private val SECRET_MARKERS: List<String> =
    listOf(
        "rotki.example",
        "pairing-id",
        "pairing-credential",
    )
