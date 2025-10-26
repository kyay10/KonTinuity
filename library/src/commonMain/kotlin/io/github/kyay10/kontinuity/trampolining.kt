package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.FramesCont.Companion.resumeWithImpl
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField

@PublishedApi
internal fun <T> (suspend () -> T).startCoroutineIntercepted(seq: FramesCont<T, *>) {
  seq.rest.realContext.trampoline.nextStep = SequenceBodyStep(this, seq)
}

private class SequenceBodyStep<T>(private val body: suspend () -> T, override val seq: FramesCont<T, *>) : Step {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(seq) }
}

@PublishedApi
internal fun <R, T> (suspend R.() -> T).startCoroutineIntercepted(
  receiver: R,
  seq: FramesCont<T, *>,
) {
  seq.rest.realContext.trampoline.nextStep = SequenceBodyReceiverStep(this, receiver, seq)
}

private class SequenceBodyReceiverStep<T, R>(
  private val body: suspend R.() -> T,
  private val receiver: R,
  override val seq: FramesCont<T, *>,
) : Step {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(receiver, seq) }
}

@PublishedApi
internal fun <Start> FramesCont<Start, *>.resumeWithIntercepted(result: Result<Start>) {
  if (result.exceptionOrNull() !== SuspendedException) {
    rest.realContext.trampoline.nextStep = SequenceResumeStep(this, result)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: FramesCont<Start, *>,
  private val result: Result<Start>
) : Step {
  override fun stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): CoroutineContext {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline) it.interceptor else it
  }
  return if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor) else Trampoline(interceptor)
}

@InternalCoroutinesApi
private class TrampolineWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
  Trampoline(interceptor), Delay by delay

internal interface Step {
  fun stepOrReturn(): Result<Any?>
  val seq: FramesCont<*, *>
}

@Suppress("UNCHECKED_CAST")
private fun Step.step() {
  val result = stepOrReturn()
  if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
    seq.resumeWithImpl(result as Result<Nothing>)
  }
}

internal open class Trampoline(val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  var nextStep: Step? = null

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