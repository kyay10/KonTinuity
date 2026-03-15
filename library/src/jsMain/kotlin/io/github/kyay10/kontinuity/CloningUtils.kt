@file:Suppress("INVISIBLE_REFERENCE") // we can always replace this with JS calls if needed
package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineImpl

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

internal fun Continuation<*>.initialize(completion: Continuation<*>) {
  CoroutineImpl::class.js.asDynamic().call(this, completion)
}

internal fun <T> Continuation<T>.copyState(from: Continuation<T>) {
  this as CoroutineImpl
  from as CoroutineImpl
  state = from.state
  exceptionState = from.exceptionState
  result = from.result
  exception = from.exception
  finallyPath = from.finallyPath
}