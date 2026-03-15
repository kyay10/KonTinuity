package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

internal actual const val SUPPORTS_MULTISHOT = false

internal actual val Continuation<*>.completion: Continuation<*>?
  get() = error(COPYING_NOT_SUPPORTED)

internal actual fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>,
): Any? = error(COPYING_NOT_SUPPORTED)