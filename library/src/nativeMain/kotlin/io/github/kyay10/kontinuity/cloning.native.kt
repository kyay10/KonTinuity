package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation

internal actual val Continuation<*>.completion: Continuation<*>
  get() = TODO("Not yet implemented")

internal actual fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T> {
  TODO("Not yet implemented")
}