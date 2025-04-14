package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.Suppress
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

internal fun <T> (suspend () -> T).startCoroutineIntercepted(seq: SplitSeq<T, *, *>) {
  seq.context.trampoline.next(SequenceBodyStep(this, seq))
}

private class SequenceBodyStep<T>(private val body: suspend () -> T, override val seq: SplitSeq<T, *, *>) : Step {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(WrapperCont(seq)) }
}

internal fun <R, T> (suspend R.() -> T).startCoroutineIntercepted(
  receiver: R,
  seq: SplitSeq<T, *, *>,
) {
  seq.context.trampoline.next(SequenceBodyReceiverStep(this, receiver, seq))
}

private class SequenceBodyReceiverStep<T, R>(
  private val body: suspend R.() -> T,
  private val receiver: R,
  override val seq: SplitSeq<T, *, *>
) : Step {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(receiver, WrapperCont(seq)) }
}

internal fun <Start, First, End> SplitSeq<Start, First, End>.resumeWithIntercepted(result: Result<Start>) {
  val exception = result.exceptionOrNull()
  if (exception is SeekingStackException) exception.use(this)
  else context.trampoline.next(SequenceResumeStep(this, result))
}

private class SequenceResumeStep<Start, First, End>(
  override val seq: SplitSeq<Start, First, End>,
  private val result: Result<Start>
) : Step {
  override fun stepOrReturn() = result
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

internal sealed interface Step {
  fun stepOrReturn(): Result<Any?>
  val seq: SplitSeq<*, *, *>
}

@Suppress("UNCHECKED_CAST")
private fun Step.step() = when (val result = stepOrReturn()) {
  Result.success(COROUTINE_SUSPENDED) -> Unit
  else -> seq.resumeWith(result as Result<Nothing>)
}

internal open class Trampoline(val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  var nextStep: Step? = null
  fun next(block: Step) {
    check(nextStep == null) { "Already running a block: $nextStep" }
    nextStep = block
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
        (nextStep ?: return).also { nextStep = null }.step()
      }
    }
  }
}

internal val CoroutineContext.trampoline: Trampoline
  get() =
    this[ContinuationInterceptor] as? Trampoline ?: error("No trampoline in context: $this")