@file:Suppress("INVISIBLE_REFERENCE") // we can always replace this with JS calls if needed
package io.github.kyay10.kontinuity

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private object MyInterceptor : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = Wrapper(continuation)
  class Wrapper<T>(val cont: Continuation<T>) : Continuation<T> by cont
}

private object MyContinuation : Continuation<Unit> {
  override val context: CoroutineContext
    get() = MyInterceptor

  override fun resumeWith(result: Result<Unit>) {}
}

private val names: Triple<String, String, String> = run {
  lateinit var cont: Continuation<*>
  val completion = MyContinuation
  val context = MyInterceptor
  suspend { suspendCoroutineUninterceptedOrReturn<Unit> { cont = it } }.asDynamic()(completion)
  Triple(
    js("Object.keys(cont).find((name) => cont[name] === completion)"),
    js("Object.keys(cont).find((name) => cont[name] === context)"),
    js("Object.keys(cont).find((name) => cont[name] && cont[name].cont === completion)")
  )
}
private val resultContinuationName = names.first
private val contextName = names.second
private val interceptedName = names.third

internal val Continuation<*>.resultContinuation: Continuation<*>? get() = asDynamic()[resultContinuationName]

internal fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any? {
  this as CoroutineImpl

  result.fold({
    this.result = it
  }, {
    state = this.exceptionState
    exception = it
  })

  return doResume()
  // releaseIntercepted() // this state machine instance is terminating
}

internal fun Continuation<*>.initialize(completion: Continuation<*>, context: CoroutineContext) {
  asDynamic()[resultContinuationName] = completion
  asDynamic()[contextName] = context
  asDynamic()[interceptedName] = null
}