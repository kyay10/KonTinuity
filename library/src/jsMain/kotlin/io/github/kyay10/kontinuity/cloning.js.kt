package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation

internal actual val Continuation<*>.completion: Continuation<*> get() = asDynamic().resultContinuation_1

internal actual fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T> {
  val cont = this
  val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
  descriptors.resultContinuation_1.value = completion
  descriptors._context_1.value = completion.context
  descriptors._intercepted_1.value = null
  return js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
}