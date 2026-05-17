package com.nuvio.app.core.coroutines

actual fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
