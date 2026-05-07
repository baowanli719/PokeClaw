// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.utils.XLog

/**
 * Routes [BridgeLogger] calls to [XLog] with a "Bridge." tag prefix.
 */
class XLogBridgeLogger : BridgeLogger {

    private fun prefixed(tag: String): String = "Bridge.$tag"

    override fun d(tag: String, message: String) {
        XLog.d(prefixed(tag), message)
    }

    override fun i(tag: String, message: String) {
        XLog.i(prefixed(tag), message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            XLog.w(prefixed(tag), message, throwable)
        } else {
            XLog.w(prefixed(tag), message)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            XLog.e(prefixed(tag), message, throwable)
        } else {
            XLog.e(prefixed(tag), message)
        }
    }
}
