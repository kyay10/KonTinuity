package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

internal actual const val SUPPORTS_MULTISHOT = true

internal actual val Continuation<*>.completion: Continuation<*>? get() = resultContinuation

internal actual fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>,
): Any? {
  val cont = this
  val copy: Continuation<T> = js("Object.create(Object.getPrototypeOf(cont))")
  // add all properties, which includes resultContinuation, intercepted, and _context
  js("Object.assign(copy, cont)")
  // thus we set them, mimicking the constructor
  copy.initialize(completion, context)
  return copy.invokeSuspend(result)
}