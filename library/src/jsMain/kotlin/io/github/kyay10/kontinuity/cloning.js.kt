package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

internal actual const val SUPPORTS_MULTISHOT = true

internal actual val Continuation<*>.completion: Continuation<*>? get() = this.asDynamic().resultContinuation_1

internal actual fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>,
): Any? {
  val cont = this
  val copy: Continuation<T> = js("Object.create(Object.getPrototypeOf(cont))")
  // add all properties, which includes resultContinuation, intercepted, and _context
  js("Object.defineProperties(copy, Object.getOwnPropertyDescriptors(cont))")
  // call constructor, which resets the coroutine internal state
  copy.initialize(completion)
  // thus, we copy the internal state (advancing the coroutine)
  copy.copyState(from = cont)
  return copy.invokeSuspend(result)
}