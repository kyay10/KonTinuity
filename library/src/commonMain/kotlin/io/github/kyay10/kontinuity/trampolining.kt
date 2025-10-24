package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
  receiver: R,
  param: P,
  completion: Continuation<T>
): Any? = (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

@PublishedApi
internal fun <T, Region> (suspend context(MultishotScope<Region>) () -> T).startCoroutineIntercepted(seq: SplitSeq<T, Region>) {
  seq.trampoline.nextStep = SequenceBodyStep(this, seq)
}

private class SequenceBodyStep<T, Region>(
  private val body: suspend context(MultishotScope<Region>) () -> T,
  override val seq: SplitSeq<T, Region>
) : Step<Region> {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(seq, seq) }
}

@PublishedApi
internal fun <R, T, Region> (suspend context(MultishotScope<Region>) R.() -> T).startCoroutineIntercepted(
  receiver: R,
  seq: SplitSeq<T, Region>,
) {
  seq.trampoline.nextStep = SequenceBodyReceiverStep(this, receiver, seq)
}

private class SequenceBodyReceiverStep<T, R, Region>(
  private val body: suspend context(MultishotScope<Region>) R.() -> T,
  private val receiver: R,
  override val seq: SplitSeq<T, Region>
) : Step<Region> {
  override fun stepOrReturn() = runCatching { body.startCoroutineUninterceptedOrReturn(seq, receiver, seq) }
}

@PublishedApi
internal fun <Start> SplitSeq<Start, *>.resumeWithIntercepted(result: Result<Start>) {
  if (result.exceptionOrNull() !== SuspendedException) {
    trampoline.nextStep = SequenceResumeStep(this, result)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: SplitSeq<Start, *>,
  private val result: Result<Start>
) : Step<Any?> {
  override fun stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): Trampoline = when (val interceptor = this[ContinuationInterceptor].let {
  if (it is Trampoline) it.interceptor else it
}) {
  is Delay -> TrampolineWithDelay(interceptor, interceptor, this)
  else -> Trampoline(interceptor, this)
}

@InternalCoroutinesApi
private class TrampolineWithDelay(
  interceptor: ContinuationInterceptor?,
  delay: Delay,
  originalContext: CoroutineContext
) :
  Trampoline(interceptor, originalContext), Delay by delay

internal interface Step<Region> {
  fun stepOrReturn(): Result<Any?>
  val seq: SplitSeq<*, Region>
}

@Suppress("UNCHECKED_CAST")
private fun Step<*>.step() {
  val result = stepOrReturn()
  if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
    seq.resumeWithImpl(result as Result<Nothing>)
  }
}

@PublishedApi
internal open class Trampoline(
  @JvmField internal val interceptor: ContinuationInterceptor?,
  originalContext: CoroutineContext
) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  var nextStep: Step<*>? = null

  @JvmField
  @PublishedApi
  internal val coroutineContext: CoroutineContext = originalContext + this

  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    TrampolineContinuation(continuation).let {
      interceptor?.interceptContinuation(it) ?: it
    }

  override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
    interceptor?.releaseInterceptedContinuation(continuation)
  }

  @PublishedApi
  internal inner class TrampolineContinuation<T>(val cont: Continuation<T>) :
    Continuation<T> {
    override val context: CoroutineContext = coroutineContext

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) {
        (nextStep ?: return).also { nextStep = null }.step()
      }
    }
  }
}
