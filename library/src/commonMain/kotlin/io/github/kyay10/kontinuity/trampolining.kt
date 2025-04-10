package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

internal fun <T> (suspend () -> T).startCoroutineHere(completion: Continuation<T>) {
  val result = runCatching { startCoroutineUninterceptedOrReturn(completion) }
  if (result == Result.success(COROUTINE_SUSPENDED)) return
  @Suppress("UNCHECKED_CAST")
  completion.resumeWithIntercepted(result as Result<T>)
}

internal fun <R, T> (suspend R.() -> T).startCoroutineHere(
  receiver: R,
  completion: Continuation<T>
) {
  val result = runCatching { startCoroutineUninterceptedOrReturn(receiver, completion) }
  if (result == Result.success(COROUTINE_SUSPENDED)) return
  @Suppress("UNCHECKED_CAST")
  completion.resumeWithIntercepted(result as Result<T>)
}

internal fun <T> (suspend () -> T).startCoroutineIntercepted(completion: Continuation<T>) {
  completion.context.trampoline.next { startCoroutineHere(completion) }
}

internal fun <R, T> (suspend R.() -> T).startCoroutineIntercepted(
  receiver: R,
  completion: Continuation<T>
) {
  completion.context.trampoline.next { startCoroutineHere(receiver, completion) }
}

internal fun <Start, First, End> SplitSeq<Start, First, End>.resumeWithIntercepted(result: Result<Start>) {
  val exception = result.exceptionOrNull()
  if (exception is SeekingStackException) exception.use(this)
  else {
    context.trampoline.next { resumeWith(result, isIntercepted = false) }
  }
}

internal fun <Start> Continuation<Start>.resumeWithIntercepted(result: Result<Start>) {
  context.trampoline.next { resumeWith(result) }
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.withTrampoline(): CoroutineContext {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline) it.interceptor else it
  }
  return this + if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor) else Trampoline(interceptor)
}

@InternalCoroutinesApi
private class TrampolineWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
  Trampoline(interceptor), Delay by delay

private open class Trampoline(val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
  private var toRun: (() -> Unit)? = null
  fun next(block: () -> Unit) {
    check(toRun == null) { "Already running a block: $toRun" }
    toRun = block
  }

  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    TrampolineContinuation(continuation).let {
      interceptor?.interceptContinuation(it) ?: it
    }

  override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
    interceptor?.releaseInterceptedContinuation(continuation)
  }

  private inner class TrampolineContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) {
        (toRun ?: return).also { toRun = null }.invoke()
      }
    }
  }
}

private val CoroutineContext.trampoline: Trampoline
  get() =
    this[ContinuationInterceptor] as? Trampoline ?: error("No trampoline in context: $this")