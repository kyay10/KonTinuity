package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation

@Suppress("INVISIBLE_REFERENCE")
internal object Resumer : kotlin.coroutines.CoroutineImpl(null) {
  override fun doResume() = error("Should not be called")

  fun <T> magic(current: Continuation<T>, result: Result<Any?>): Any? {
    current as kotlin.coroutines.CoroutineImpl

    // Set result and exception fields in the current continuation
    result.fold({
      current.result = it
    }, {
      current.state = current.exceptionState
      current.exception = it
    })

    return current.doResume()
    // releaseIntercepted() // this state machine instance is terminating
  }
}