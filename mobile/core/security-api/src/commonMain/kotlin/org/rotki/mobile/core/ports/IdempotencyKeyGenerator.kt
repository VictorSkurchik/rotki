package org.rotki.mobile.core.ports

import org.rotki.mobile.core.protocol.IdempotencyKey

public fun interface IdempotencyKeyGenerator {
    public fun generate(): IdempotencyKey
}
