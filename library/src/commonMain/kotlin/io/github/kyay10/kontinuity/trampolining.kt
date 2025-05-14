package io.github.kyay10.kontinuity

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
  receiver: R,
  param: P,
  completion: Continuation<T>
): Any? = (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

private class SequenceBodyStep<T>(private val body: suspend MultishotScope.() -> T, override val seq: SplitSeq<T>) : Step() {
  override fun MultishotScope.stepOrReturn() = runCatching {
    rest = seq
    body.startCoroutineUninterceptedOrReturn(this, seq)
  }
}

private class SequenceBodyReceiverStep<T, R>(
  private val body: suspend MultishotScope.(R) -> T,
  private val receiver: R,
  override val seq: SplitSeq<T>
) : Step() {
  override fun MultishotScope.stepOrReturn() = runCatching {
    rest = seq
    body.startCoroutineUninterceptedOrReturn(this, receiver, seq)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: SplitSeq<Start>,
  private val result: Result<Start>
) : Step() {
  override fun MultishotScope.stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeMultishotScope(): MultishotScope {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is MultishotScope) it.interceptor else it
  }
  return if (interceptor is Delay) MultishotScopeWithDelay(interceptor, interceptor, this) else MultishotScope(interceptor, this)
}
@InternalCoroutinesApi
private class MultishotScopeWithDelay(interceptor: ContinuationInterceptor?, delay: Delay, originalContext: CoroutineContext) :
  MultishotScope(interceptor, originalContext), Delay by delay

internal abstract class Step {
  abstract fun MultishotScope.stepOrReturn(): Result<Any?>
  abstract val seq: SplitSeq<*>
}

@RestrictsSuspension
public open class MultishotScope(@JvmField internal val interceptor: ContinuationInterceptor?, originalContext: CoroutineContext) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  internal var nextStep: Step? = null
  @JvmField
  @PublishedApi
  internal var rest: SplitSeq<*>? = null

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
  internal inner class TrampolineContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = coroutineContext

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) {
        (nextStep ?: return).also { nextStep = null }.step()
      }
    }
  }
  public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = suspendCoroutineUninterceptedOrReturn {
    block.startCoroutineUninterceptedOrReturn(TrampolineContinuation(it, ))
  }

  @PublishedApi
  internal fun <T> (suspend MultishotScope.() -> T).startCoroutineIntercepted(seq: SplitSeq<T>) {
    nextStep = SequenceBodyStep(this, seq)
  }

  @PublishedApi
  internal fun <R, T> (suspend MultishotScope.(R) -> T).startCoroutineIntercepted(
    receiver: R,
    seq: SplitSeq<T>,
  ) {
    nextStep = SequenceBodyReceiverStep(this, receiver, seq)
  }

  @PublishedApi
  internal fun <Start> SplitSeq<Start>.resumeWithIntercepted(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      nextStep = SequenceResumeStep(this, result)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun Step.step() {
    val result = stepOrReturn()
    if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
      seq.resumeWithImpl(result as Result<Nothing>)
    }
  }

  @ResetDsl
  public suspend inline fun <T, R> SubCont<T, R>.resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend inline fun <T, R> SubCont<T, R>.locally(noinline value: suspend MultishotScope.() -> T): R = suspendCoroutineToTrampoline { stack ->
    value.startCoroutineIntercepted(composedWith(stack))
  }

  public suspend operator fun <T, R> SubCont<T, R>.invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun <T, R> SubCont<T, R>.resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))


  @ResetDsl
  public suspend inline fun <R> newReset(noinline body: suspend context(Prompt<R>) MultishotScope.() -> R): R =
    suspendCoroutineAndTrampoline { stack ->
      val prompt = Prompt(stack)
      rest = prompt
      body.startCoroutineUninterceptedOrReturn(prompt, this, prompt)
    }

  public suspend inline fun <T, R> runReader(
    value: T,
    noinline fork: T.() -> T = { this },
    noinline body: suspend context(Reader<T>) MultishotScope.() -> R
  ): R = suspendCoroutineAndTrampoline { stack ->
    val reader = ReaderT(stack, value, fork)
    rest = reader
    body.startCoroutineUninterceptedOrReturn(reader, this, reader)
  }

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shift(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContContext")
  public suspend inline fun <T, R> shift(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shift(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftOnce(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContOnceContext")
  public suspend inline fun <T, R> shiftOnce(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shiftOnce(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
    noinline body: suspend MultishotScope.(Pair<SubCont<T, R>, SubCont<T, R>>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE) to SubCont(init), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContWithFinalContext")
  public suspend inline fun <T, R> shiftWithFinal(
    noinline body: suspend MultishotScope.(Pair<SubCont<T, R>, SubCont<T, R>>) -> R
  ): T = p.shiftWithFinal(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftRepushing(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("shiftRepushingContext")
  public suspend inline fun <T, R> shiftRepushing(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shiftRepushing(body)

  // Acts like shift0/control { it(body()) }
  @ResetDsl
  public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
    noinline body: suspend MultishotScope.(SubCont<T, P>) -> T
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
  }

  @ResetDsl
  public suspend inline fun <T, P> Prompt<P>.inHandlingContextTwice(
    noinline body: suspend MultishotScope.(SubCont<T, P>) -> T
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(this)
    body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
  }

  public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
    rest.resumeWithIntercepted(value)
    throw SuspendedException
  }

  public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
    suspendCoroutineUninterceptedOrReturn {
      rest.resumeWithIntercepted(value)
      COROUTINE_SUSPENDED
    }

  public fun <R> Prompt<R>.abortS(value: suspend MultishotScope.() -> R): Nothing {
    value.startCoroutineIntercepted(rest)
    throw SuspendedException
  }

  @PublishedApi
  internal suspend inline fun <T> suspendCoroutineToTrampoline(
    crossinline block: (SplitSeq<T>) -> Unit
  ): T = suspendCoroutineUninterceptedOrReturn {
    block(collectStack(it))
    COROUTINE_SUSPENDED
  }

  @PublishedApi
  internal suspend inline fun <T> suspendCoroutineAndTrampoline(
    crossinline block: (SplitSeq<T>) -> Any?
  ): T = suspendCoroutineUninterceptedOrReturn {
    val stack = collectStack(it)
    stack.handleTrampolining(runCatching { block(stack) })
  }

  @PublishedApi
  internal tailrec fun FramesCont<*, *>.handleTrampolining(
    result: Result<Any?>,
  ): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
    val step = nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
    nextStep = null
    handleTrampolining(with(step) { stepOrReturn() })
  } else {
    this@MultishotScope.rest = rest
    result.getOrThrow()
  }
}