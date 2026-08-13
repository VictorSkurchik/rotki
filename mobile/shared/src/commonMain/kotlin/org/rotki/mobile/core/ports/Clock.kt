package org.rotki.mobile.core.ports

public fun interface Clock {
    public fun nowEpochSeconds(): Long
}
