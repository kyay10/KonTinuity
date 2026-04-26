@file:Suppress("INVISIBLE_REFERENCE") // we can always replace this with JS calls if needed

package io.github.kyay10.kontinuity.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineImpl

internal fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any? {
  this as CoroutineImpl

  result.fold(
    { this.result = it },
    {
      state = exceptionState
      exception = it
    },
  )

  return doResume()
  // releaseIntercepted() // this state machine instance is terminating
}
