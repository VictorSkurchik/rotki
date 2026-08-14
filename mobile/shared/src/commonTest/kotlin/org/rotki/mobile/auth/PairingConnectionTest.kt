package org.rotki.mobile.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.core.network.RetryPolicy
import org.rotki.mobile.core.network.createCompanionHttpClient
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.ports.IdempotencyKeyGenerator
import org.rotki.mobile.core.ports.PairingCleanupJournal
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.generated.ProtocolHeaders
import org.rotki.mobile.core.state.CompanionRootState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingConnectionTest {
    @Test
    fun `discovery precedes key creation and registration persists before commit`(): Unit =
        runTest {
            val fixture = Fixture()
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/1/companion/protocol" -> {
                            fixture.events += "discovery"
                            respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                        }

                        "/api/1/companion/device-sessions" -> {
                            fixture.events += "registration"
                            respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                        }

                        else -> {
                            error("Unexpected request")
                        }
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(PairingConnectionOutcome.REGISTERED, connection.connectPendingPairing())
                assertEquals(
                    listOf(
                        "read",
                        "discovery",
                        "mark_cleanup",
                        "create_key",
                        "registration",
                        "write",
                        "clear_cleanup",
                    ),
                    fixture.events,
                )
                assertNotNull(fixture.store.record)
                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
                assertNull(fixture.facade.takePendingPairingForConnection())
                assertEquals(0, fixture.signer.deleteCalls)
                assertEquals(0, fixture.store.deleteCalls)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `registration retries once with identical bearer idempotency key and body`(): Unit =
        runTest {
            val fixture = Fixture()
            var registrationCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        registrationCalls += 1
                        if (registrationCalls == 1) {
                            respond(content = "gateway", status = HttpStatusCode.BadGateway)
                        } else {
                            respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                        }
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(PairingConnectionOutcome.REGISTERED, connection.connectPendingPairing())
                val registrations =
                    engine.requestHistory.filter { request ->
                        request.url.encodedPath.endsWith("/device-sessions")
                    }
                assertEquals(2, registrations.size)
                assertEquals(
                    registrations[0].headers[HttpHeaders.Authorization],
                    registrations[1].headers[HttpHeaders.Authorization],
                )
                assertEquals(
                    registrations[0].headers[ProtocolHeaders.IdempotencyKey],
                    registrations[1].headers[ProtocolHeaders.IdempotencyKey],
                )
                assertEquals(registrations[0].bodyText(), registrations[1].bodyText())
                assertEquals(1, fixture.idempotencyGenerator.calls)
                assertEquals(listOf(0L), fixture.delays)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `safe discovery retries before creating the device key`(): Unit =
        runTest {
            val fixture = Fixture()
            var discoveryCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        discoveryCalls += 1
                        if (discoveryCalls == 1) {
                            throw IOException("redacted transport failure")
                        }
                        fixture.events += "discovery_success"
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(PairingConnectionOutcome.REGISTERED, connection.connectPendingPairing())
                assertEquals(2, discoveryCalls)
                assertEquals(1, fixture.signer.createCalls)
                assertTrue(fixture.events.indexOf("discovery_success") < fixture.events.indexOf("create_key"))
            } finally {
                connection.close()
            }
        }

    @Test
    fun `incompatible discovery aborts only ephemeral QR without deleting durable stores`(): Unit =
        runTest {
            val fixture = Fixture()
            val engine =
                MockEngine {
                    respondJson(INCOMPATIBLE_FAILURE, HttpStatusCode.UpgradeRequired)
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(PairingConnectionOutcome.INCOMPATIBLE, connection.connectPendingPairing())
                assertEquals(0, fixture.signer.createCalls)
                assertEquals(0, fixture.signer.deleteCalls)
                assertEquals(0, fixture.store.deleteCalls)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `pairing unavailable is typed and rolls back the newly created key`(): Unit =
        runTest {
            val fixture = Fixture()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respondJson(PAIRING_UNAVAILABLE_FAILURE, HttpStatusCode.Gone)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(
                    PairingConnectionOutcome.PAIRING_UNAVAILABLE,
                    connection.connectPendingPairing(),
                )
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `expiry during registration backoff stops without a second post and rolls back`(): Unit =
        runTest {
            val fixture = Fixture()
            var registrationCalls = 0
            fixture.retryDelay =
                PairingRetryDelay {
                    fixture.delays += it
                    fixture.clock.now = QR_EXPIRY
                }
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        registrationCalls += 1
                        respond(content = "gateway", status = HttpStatusCode.BadGateway)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(
                    PairingConnectionOutcome.PAIRING_EXPIRED,
                    connection.connectPendingPairing(),
                )
                assertEquals(1, registrationCalls)
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertNull(fixture.store.record)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `silent post is bounded by qr expiry rolls back and releases facade ownership`(): Unit =
        runTest {
            val fixture = Fixture(qrExpiry = NOW + 1)
            val postStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        postStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val result = CompletableDeferred<PairingConnectionOutcome>()
                launch { result.complete(connection.connectPendingPairing()) }
                postStarted.await()
                assertEquals(PairingConnectionOutcome.PAIRING_EXPIRED, result.await())
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.connectPendingPairing(),
                )
            } finally {
                connection.close()
            }
        }

    @Test
    fun `background cancels an in-flight post and noncancellable rollback clears local material`(): Unit =
        runTest {
            val fixture = Fixture()
            val postStarted = CompletableDeferred<Unit>()
            val postCancelled = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        postStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            postCancelled.complete(Unit)
                        }
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val result = CompletableDeferred<PairingConnectionOutcome>()
                val job = launch { result.complete(connection.connectPendingPairing()) }
                postStarted.await()

                fixture.visibility.set(ApplicationVisibilityState.BACKGROUND_OR_LOCKED)

                assertEquals(PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND, result.await())
                postCancelled.await()
                job.cancelAndJoin()
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertNull(fixture.store.record)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `inactive during post resumes identical request without native relaunch`(): Unit =
        runTest {
            val fixture = Fixture()
            val firstPostStarted = CompletableDeferred<Unit>()
            val firstPostCancelled = CompletableDeferred<Unit>()
            var registrationCalls = 0
            val bearerValues = mutableListOf<String?>()
            val idempotencyValues = mutableListOf<String?>()
            val bodyValues = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        registrationCalls += 1
                        bearerValues += request.headers[HttpHeaders.Authorization]
                        idempotencyValues += request.headers[ProtocolHeaders.IdempotencyKey]
                        bodyValues += request.bodyText()
                        if (registrationCalls == 1) {
                            firstPostStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstPostCancelled.complete(Unit)
                            }
                        } else {
                            respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                        }
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val result = CompletableDeferred<PairingConnectionOutcome>()
                launch { result.complete(connection.connectPendingPairing()) }
                firstPostStarted.await()
                fixture.visibility.set(ApplicationVisibilityState.INACTIVE)
                firstPostCancelled.await()
                yield()
                assertEquals(1, registrationCalls)
                assertEquals(1, fixture.signer.createCalls)

                fixture.visibility.set(ApplicationVisibilityState.ACTIVE_FOREGROUND)

                assertEquals(PairingConnectionOutcome.REGISTERED, result.await())
                assertEquals(2, registrationCalls)
                assertEquals(bearerValues.first(), bearerValues.last())
                assertEquals(idempotencyValues.first(), idempotencyValues.last())
                assertEquals(bodyValues.first(), bodyValues.last())
                assertEquals(1, fixture.idempotencyGenerator.calls)
                assertEquals(0, fixture.signer.deleteCalls)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `retry after above policy bound surfaces rate limited and rolls back`(): Unit =
        runTest {
            val fixture = Fixture()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respond(
                            content = RATE_LIMIT_FAILURE,
                            status = HttpStatusCode.TooManyRequests,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                    HttpHeaders.RetryAfter to listOf("6"),
                                ),
                        )
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(PairingConnectionOutcome.RATE_LIMITED, connection.connectPendingPairing())
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `cleanup failure stays fail closed rejects fresh qr and retry clears ownership`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.store.deleteOutcome = PairingRecordDeleteOutcome.Unavailable
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respondJson(PAIRING_UNAVAILABLE_FAILURE, HttpStatusCode.Gone)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    connection.connectPendingPairing(),
                )
                assertTrue(fixture.cleanupJournal.cleanupRequired)
                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
                val replacement = fixture.facade.pairingFlow(fixture.clock)
                replacement.startScanning()
                replacement.submitQr(validQr())
                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
                assertNull(fixture.facade.takePendingPairingForConnection())

                fixture.store.deleteOutcome = PairingRecordDeleteOutcome.Deleted
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.retryIncompleteCleanup(),
                )
                assertFalse(fixture.cleanupJournal.cleanupRequired)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `journal must be durable before key inspection and failure creates no material`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.cleanupJournal.markOutcome = PairingCleanupJournalWriteOutcome.Unavailable
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        error("Registration must not start")
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    connection.connectPendingPairing(),
                )
                assertEquals(0, fixture.signer.currentCalls)
                assertEquals(0, fixture.signer.createCalls)
                assertEquals(0, fixture.signer.deleteCalls)
                assertEquals(0, fixture.store.deleteCalls)
                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `journal clear failure after record write rolls all material back`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.cleanupJournal.clearOutcome = PairingCleanupJournalClearOutcome.Unavailable
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    connection.connectPendingPairing(),
                )
                assertNull(fixture.store.record)
                assertEquals(1, fixture.signer.deleteCalls)
                assertTrue(fixture.cleanupJournal.cleanupRequired)
                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `transient journal preflight failure then clear retry discards untouched qr`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.cleanupJournal.readOutcomeOverride =
                PairingCleanupJournalReadOutcome.Unavailable
            val connection = fixture.connection(MockEngine { error("No network expected") })
            try {
                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    connection.connectPendingPairing(),
                )
                assertEquals(0, fixture.signer.createCalls)
                fixture.cleanupJournal.readOutcomeOverride = PairingCleanupJournalReadOutcome.Clear
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.retryIncompleteCleanup(),
                )
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
                assertNull(fixture.facade.takePendingPairingForConnection())
            } finally {
                connection.close()
            }
        }

    @Test
    fun `preflight cleanup recovery clears stale accepted qr and permits a fresh scan`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.cleanupJournal.cleanupRequired = true
            val connection = fixture.connection(MockEngine { error("No network expected") })
            try {
                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    connection.connectPendingPairing(),
                )
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.retryIncompleteCleanup(),
                )
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)

                val fresh = fixture.facade.pairingFlow(fixture.clock)
                fresh.startScanning()
                fresh.submitQr(validQr())

                assertEquals(CompanionRootState.Connecting, fixture.facade.status.value.rootState)
                assertNotNull(fixture.facade.takePendingPairingForConnection())
            } finally {
                connection.close()
            }
        }

    @Test
    fun `background lock retains secret free cleanup ownership until retry succeeds`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.signer.deleteOutcome = DeviceProofKeyDeleteOutcome.Unavailable
            val postStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        postStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val result = CompletableDeferred<PairingConnectionOutcome>()
                launch { result.complete(connection.connectPendingPairing()) }
                postStarted.await()

                fixture.visibility.set(ApplicationVisibilityState.BACKGROUND_OR_LOCKED)
                fixture.facade.lock()

                assertEquals(
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE,
                    result.await(),
                )
                assertEquals(CompanionRootState.DeviceLocked, fixture.facade.status.value.rootState)
                fixture.signer.deleteOutcome = DeviceProofKeyDeleteOutcome.Deleted
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.retryIncompleteCleanup(),
                )
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `durable journal recovers cleanup after process restart without qr lease`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.cleanupJournal.cleanupRequired = true
            val connection = fixture.connection(MockEngine { error("No network expected") })
            try {
                // Simulates a new facade where the QR/token died with the previous process.
                fixture.facade.lock()
                assertEquals(
                    PairingConnectionOutcome.NO_PENDING_PAIRING,
                    connection.retryIncompleteCleanup(),
                )
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertFalse(fixture.cleanupJournal.cleanupRequired)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `inactive start waits without network or key work then resumes and stays redacted`(): Unit =
        runTest {
            val fixture = Fixture()
            fixture.visibility.set(ApplicationVisibilityState.INACTIVE)
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val result = CompletableDeferred<PairingConnectionOutcome>()
                launch { result.complete(connection.connectPendingPairing()) }
                yield()
                assertTrue(engine.requestHistory.isEmpty())
                assertEquals(0, fixture.signer.createCalls)
                fixture.visibility.set(ApplicationVisibilityState.ACTIVE_FOREGROUND)
                assertEquals(PairingConnectionOutcome.REGISTERED, result.await())
                val representations =
                    listOf(
                        connection.toString(),
                        fixture.configuration().toString(),
                        PairingConnectionOutcome.NETWORK_UNAVAILABLE.toString(),
                    ).joinToString()
                listOf(ORIGIN, PAIRING_CREDENTIAL, PAIRING_ID, "Pixel 10 Pro").forEach { secret ->
                    assertFalse(secret in representations, secret)
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `owner cancellation is rethrown after noncancellable rollback`(): Unit =
        runTest {
            val fixture = Fixture()
            val postStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/protocol")) {
                        respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
                    } else {
                        postStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            val connection = fixture.connection(engine)
            try {
                val job = launch { connection.connectPendingPairing() }
                postStarted.await()

                job.cancelAndJoin()

                assertTrue(job.isCancelled)
                assertEquals(1, fixture.signer.deleteCalls)
                assertEquals(1, fixture.store.deleteCalls)
                assertEquals(CompanionRootState.Unpaired, fixture.facade.status.value.rootState)
            } finally {
                connection.close()
            }
        }

    private class Fixture(
        qrExpiry: Long = QR_EXPIRY,
    ) {
        val events: MutableList<String> = mutableListOf()
        val facade: CompanionFacade = CompanionFacade()
        val clock: MutableClock = MutableClock(NOW)
        val visibility: MutableVisibility = MutableVisibility()
        val signer: RecordingSigner = RecordingSigner(events)
        val store: RecordingPairingRecordStore = RecordingPairingRecordStore(events)
        val cleanupJournal: RecordingCleanupJournal = RecordingCleanupJournal(events)
        val idempotencyGenerator: RecordingIdempotencyGenerator =
            RecordingIdempotencyGenerator()
        val delays: MutableList<Long> = mutableListOf()
        var retryDelay: PairingRetryDelay = PairingRetryDelay { delays += it }

        init {
            val flow = facade.pairingFlow(clock)
            flow.startScanning()
            flow.submitQr(validQr(qrExpiry))
            assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
        }

        fun connection(engine: MockEngine): PairingConnection =
            PairingConnection(
                facade = facade,
                configuration = configuration(),
                protocolClient = PairingProtocolClient(createCompanionHttpClient(engine)),
                retryPolicy = RetryPolicy { 0L },
                retryDelay = retryDelay,
            )

        fun configuration(): PairingConnectionConfiguration =
            PairingConnectionConfiguration(
                deviceLabel = "Pixel 10 Pro",
                platform = PairingDevicePlatform.ANDROID,
                deviceProofSigner = signer,
                pairingRecordStore = store,
                pairingCleanupJournal = cleanupJournal,
                idempotencyKeyGenerator = idempotencyGenerator,
                applicationVisibility = visibility,
                clock = clock,
            )
    }

    private class MutableClock(
        var now: Long,
    ) : Clock {
        override fun nowEpochSeconds(): Long = now
    }

    private class MutableVisibility : ApplicationVisibility {
        private val mutable = MutableStateFlow(ApplicationVisibilityState.ACTIVE_FOREGROUND)
        override val state: StateFlow<ApplicationVisibilityState> = mutable.asStateFlow()

        fun set(value: ApplicationVisibilityState) {
            mutable.value = value
        }
    }

    private class RecordingSigner(
        private val events: MutableList<String>,
    ) : DeviceProofSigner {
        var createCalls: Int = 0
        var currentCalls: Int = 0
        var deleteCalls: Int = 0
        var deleteOutcome: DeviceProofKeyDeleteOutcome = DeviceProofKeyDeleteOutcome.Deleted

        override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome {
            createCalls += 1
            events += "create_key"
            return DeviceProofPublicKeyOutcome.PublicKey(parsed(X963PublicKey.parse(PUBLIC_KEY)))
        }

        override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome {
            currentCalls += 1
            return DeviceProofPublicKeyOutcome.PairingRequired
        }

        override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome =
            DeviceProofSigningOutcome.UnexpectedFailure

        override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome {
            deleteCalls += 1
            return deleteOutcome
        }
    }

    private class RecordingPairingRecordStore(
        private val events: MutableList<String>,
    ) : PairingRecordStore {
        var record: PairingRecord? = null
        var deleteCalls: Int = 0
        var deleteOutcome: PairingRecordDeleteOutcome = PairingRecordDeleteOutcome.Deleted

        override suspend fun read(): PairingRecordReadOutcome {
            events += "read"
            return record?.let(PairingRecordReadOutcome::Present) ?: PairingRecordReadOutcome.Missing
        }

        override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome {
            events += "write"
            this.record = record
            return PairingRecordWriteOutcome.Stored
        }

        override suspend fun delete(): PairingRecordDeleteOutcome {
            deleteCalls += 1
            if (deleteOutcome == PairingRecordDeleteOutcome.Deleted) record = null
            return deleteOutcome
        }
    }

    private class RecordingIdempotencyGenerator : IdempotencyKeyGenerator {
        var calls: Int = 0

        override fun generate(): IdempotencyKey {
            calls += 1
            return parsed(IdempotencyKey.parse(IDEMPOTENCY_KEY))
        }
    }

    private class RecordingCleanupJournal(
        private val events: MutableList<String>,
    ) : PairingCleanupJournal {
        var cleanupRequired: Boolean = false
        var readOutcomeOverride: PairingCleanupJournalReadOutcome? = null
        var markOutcome: PairingCleanupJournalWriteOutcome =
            PairingCleanupJournalWriteOutcome.Stored
        var clearOutcome: PairingCleanupJournalClearOutcome =
            PairingCleanupJournalClearOutcome.Cleared

        override suspend fun read(): PairingCleanupJournalReadOutcome =
            readOutcomeOverride ?: if (cleanupRequired) {
                PairingCleanupJournalReadOutcome.CleanupRequired
            } else {
                PairingCleanupJournalReadOutcome.Clear
            }

        override suspend fun markCleanupRequired(): PairingCleanupJournalWriteOutcome {
            events += "mark_cleanup"
            if (markOutcome == PairingCleanupJournalWriteOutcome.Stored) cleanupRequired = true
            return markOutcome
        }

        override suspend fun clear(): PairingCleanupJournalClearOutcome {
            events += "clear_cleanup"
            if (clearOutcome == PairingCleanupJournalClearOutcome.Cleared) {
                cleanupRequired = false
            }
            return clearOutcome
        }
    }
}

private fun OutgoingContent.bodyText(): String =
    assertIs<OutgoingContent.ByteArrayContent>(this).bytes().decodeToString()

private fun io.ktor.client.request.HttpRequestData.bodyText(): String = body.bodyText()

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode,
) = respond(
    content = body,
    status = status,
    headers =
        if (status == HttpStatusCode.Created) {
            headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                HttpHeaders.CacheControl to listOf("no-store"),
            )
        } else {
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        },
)

private fun validQr(expiresAt: Long = QR_EXPIRY): String =
    """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"$ORIGIN","pairing_id":"$PAIRING_ID","pairing_credential":"$PAIRING_CREDENTIAL","expires_at":$expiresAt}"""

private fun <T> parsed(outcome: ProtocolValueParseOutcome<T>): T =
    assertIs<ProtocolValueParseOutcome.Accepted<T>>(outcome).value

private const val NOW: Long = 1_786_550_300L
private const val QR_EXPIRY: Long = NOW + 100L
private const val ORIGIN: String = "https://rotki.example"
private const val PAIRING_ID: String = "AAECAwQFBgcICQoLDA0ODw"
private const val PAIRING_CREDENTIAL: String =
    "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8"
private const val IDEMPOTENCY_KEY: String = "cHFyc3R1dnd4eXp7fH1-fw"
private const val PUBLIC_KEY: String =
    "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
private const val DISCOVERY_SUCCESS: String =
    """{"result":{"supported_protocol_versions":[1],"capabilities":{"device_sessions":1}},"message":""}"""
private const val REGISTRATION_SUCCESS: String =
    """{"result":{"device_session":{"device_session_id":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","device_label":"Pixel 10 Pro","platform":"android","state":"authorized","paired_at":1786550300,"last_seen_at":null,"revoked_at":null}},"message":""}"""
private const val INCOMPATIBLE_FAILURE: String =
    """{"result":null,"message":"redacted","error":{"code":"incompatible_protocol","retryable":false,"action":"upgrade_engine"}}"""
private const val PAIRING_UNAVAILABLE_FAILURE: String =
    """{"result":null,"message":"redacted","error":{"code":"pairing_unavailable","retryable":false,"action":"pair_again"}}"""
private const val RATE_LIMIT_FAILURE: String =
    """{"result":null,"message":"redacted","error":{"code":"rate_limited","retryable":true,"action":"retry_after"}}"""
