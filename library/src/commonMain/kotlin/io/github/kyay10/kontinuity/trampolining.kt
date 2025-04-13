package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted

internal fun <T> (suspend () -> T).startCoroutineIntercepted(seq: SplitSeq<T, *, *>) {
  seq.context.trampoline.next(SequenceBodyStep(this, seq))
}

private class SequenceBodyStep<T>(private val body: suspend () -> T, override val seq: SplitSeq<T, *, *>) : Step {
  override fun step() {
    body.createCoroutineUnintercepted(WrapperCont(seq)).resume(Unit)
  }
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
  override fun step() {
    body.createCoroutineUnintercepted(receiver, WrapperCont(seq)).resume(Unit)
  }
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
  override fun step() {
    seq.resumeWith(result)
  }
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

private sealed interface Step {
  fun step()
  val seq: SplitSeq<*, *, *>
}

private open class Trampoline(val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
  private var nextStep: Step? = null
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

private val CoroutineContext.trampoline: Trampoline
  get() =
    this[ContinuationInterceptor] as? Trampoline ?: error("No trampoline in context: $this")