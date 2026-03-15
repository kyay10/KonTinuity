package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.*

internal actual const val SUPPORTS_MULTISHOT = true

private val resultContinuationName: String = run {
  lateinit var cont: Continuation<*>
  val completion = Continuation<Unit>(EmptyCoroutineContext) {}
  suspend { suspendCoroutineUninterceptedOrReturn<Unit> { cont = it } }.asDynamic()(completion)
  js("Object.keys(cont).find((name) => cont[name] === completion)")
}

internal actual val Continuation<*>.completion: Continuation<*>? get() = asDynamic()[resultContinuationName]

internal actual fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>,
): Any? {
  val cont = this
  val copy: Continuation<T> = js("Object.create(Object.getPrototypeOf(cont))")
  // add all properties, which includes resultContinuation, intercepted, and _context
  js("Object.assign(copy, cont)")
  // call constructor, which resets the coroutine internal state
  copy.initialize(completion)
  // thus, we copy the internal state (advancing the coroutine)
  copy.copyState(from = cont)
  return copy.invokeSuspend(result)
}