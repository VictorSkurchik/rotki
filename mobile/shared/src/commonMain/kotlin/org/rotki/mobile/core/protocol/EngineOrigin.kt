package org.rotki.mobile.core.protocol

import io.ktor.http.Url
import io.ktor.http.URLParserException

public class EngineOrigin private constructor(
    public val canonical: String,
) {
    public val restApiBase: String
        get() = "$canonical/api/1"

    public val webSocketEndpoint: String
        get() = "wss://${canonical.removePrefix(HTTPS_PREFIX)}/ws"

    public override fun equals(other: Any?): Boolean =
        other is EngineOrigin && canonical == other.canonical

    public override fun hashCode(): Int = canonical.hashCode()

    public override fun toString(): String = "EngineOrigin(redacted)"

    public companion object {
        private const val HTTPS_PREFIX: String = "https://"
        private const val MAX_ORIGIN_LENGTH: Int = 2_048

        public fun parse(candidate: String): EngineOriginParseOutcome {
            if (candidate.isEmpty() || candidate.length > MAX_ORIGIN_LENGTH) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.MALFORMED)
            }
            if (candidate.any { character -> character.code !in 0x21..0x7e }) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.ASCII_REQUIRED)
            }
            if (!candidate.startsWith(HTTPS_PREFIX)) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.HTTPS_REQUIRED)
            }
            if (candidate != candidate.lowercase()) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.NON_CANONICAL)
            }
            val authorityCandidate = candidate.removePrefix(HTTPS_PREFIX)
            if (authorityCandidate.any { character -> character in "/?#@" }) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.ORIGIN_ONLY)
            }
            when (val explicitPort = candidate.explicitPort()) {
                ExplicitPort.Absent -> Unit
                ExplicitPort.Malformed ->
                    return EngineOriginParseOutcome.Rejected(EngineOriginRejection.MALFORMED)
                is ExplicitPort.Present -> {
                    if (explicitPort.value == 443) {
                        return EngineOriginParseOutcome.Rejected(
                            EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
                        )
                    }
                    if (explicitPort.value !in 1..65_535 ||
                        explicitPort.source != explicitPort.value.toString()
                    ) {
                        return EngineOriginParseOutcome.Rejected(EngineOriginRejection.NON_CANONICAL)
                    }
                }
            }

            val parsed = try {
                Url(candidate)
            } catch (_: URLParserException) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.MALFORMED)
            }
            if (parsed.protocol.name != "https") {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.HTTPS_REQUIRED)
            }
            if (parsed.host.isEmpty()) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.HOST_REQUIRED)
            }
            if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.ORIGIN_ONLY)
            }
            if (parsed.encodedPath.isNotEmpty() && parsed.encodedPath != "/") {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.ORIGIN_ONLY)
            }
            if (parsed.encodedQuery.isNotEmpty() || parsed.trailingQuery || parsed.encodedFragment.isNotEmpty()) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.ORIGIN_ONLY)
            }
            if (parsed.specifiedPort == 443) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.DEFAULT_PORT_FORBIDDEN)
            }

            val ktorCanonical = parsed.toString().removeSuffix("/")
            if (ktorCanonical != candidate) {
                return EngineOriginParseOutcome.Rejected(EngineOriginRejection.NON_CANONICAL)
            }
            return EngineOriginParseOutcome.Accepted(EngineOrigin(candidate))
        }

        private fun String.explicitPort(): ExplicitPort {
            val authority = removePrefix(HTTPS_PREFIX)
            val portSource = if (authority.startsWith('[')) {
                val closingBracket = authority.indexOf(']')
                if (closingBracket < 0) {
                    return ExplicitPort.Malformed
                }
                val suffix = authority.substring(closingBracket + 1)
                when {
                    suffix.isEmpty() -> return ExplicitPort.Absent
                    suffix.startsWith(':') -> suffix.drop(1)
                    else -> return ExplicitPort.Malformed
                }
            } else {
                when (authority.count { character -> character == ':' }) {
                    0 -> return ExplicitPort.Absent
                    1 -> authority.substringAfterLast(':')
                    else -> return ExplicitPort.Malformed
                }
            }
            val port = portSource.toIntOrNull() ?: return ExplicitPort.Malformed
            return ExplicitPort.Present(port, portSource)
        }

        private sealed interface ExplicitPort {
            data object Absent : ExplicitPort
            data object Malformed : ExplicitPort
            data class Present(val value: Int, val source: String) : ExplicitPort
        }
    }
}

public sealed interface EngineOriginParseOutcome {
    public data class Accepted(
        public val origin: EngineOrigin,
    ) : EngineOriginParseOutcome

    public data class Rejected(
        public val reason: EngineOriginRejection,
    ) : EngineOriginParseOutcome
}

public enum class EngineOriginRejection {
    MALFORMED,
    ASCII_REQUIRED,
    HTTPS_REQUIRED,
    HOST_REQUIRED,
    ORIGIN_ONLY,
    DEFAULT_PORT_FORBIDDEN,
    NON_CANONICAL,
}
