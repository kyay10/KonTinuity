package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
  receiver: R,
  param: P,
  completion: Continuation<T>
): Any? = (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

private class SequenceBodyStep<Region, T>(
  private val body: suspend MultishotToken<Region>.() -> T,
  override val seq: SplitSeq<T, Region>
) : Step<Region>() {
  override fun stepOrReturn() = runCatching {
    body.startCoroutineUninterceptedOrReturn(seq, seq)
  }
}

private class SequenceBodyReceiverStep<T, R, Region>(
  private val body: suspend MultishotToken<Region>.(R) -> T,
  private val receiver: R,
  override val seq: SplitSeq<T, Region>
) : Step<Region>() {
  override fun stepOrReturn() = runCatching {
    body.startCoroutineUninterceptedOrReturn(seq, receiver, seq)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: SplitSeq<Start, *>,
  private val result: Result<Start>
) : Step<Any?>() {
  override fun stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): Trampoline {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline) it.interceptor else it
  }
  return if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor, this) else Trampoline(interceptor, this)
}
@InternalCoroutinesApi
private class TrampolineWithDelay(interceptor: ContinuationInterceptor?, delay: Delay, originalContext: CoroutineContext) :
  Trampoline(interceptor, originalContext), Delay by delay

internal abstract class Step<Region> {
  abstract fun stepOrReturn(): Result<Any?>
  abstract val seq: SplitSeq<*, Region>
}

@Suppress("UNCHECKED_CAST")
private fun Step<*>.step() {
  val result = stepOrReturn()
  if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
    seq.resumeWithImpl(result as Result<Nothing>)
  }
}

public open class Trampoline(@JvmField internal val interceptor: ContinuationInterceptor?, originalContext: CoroutineContext) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  internal var nextStep: Step<*>? = null

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
}

@RestrictsSuspension
public sealed class MultishotScope<out Region> {
  @PublishedApi
  internal abstract val token: MultishotToken<Region>
  public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = suspendCoroutineUninterceptedOrReturn {
    block.startCoroutineUninterceptedOrReturn(token.trampoline.TrampolineContinuation(it))
  }

  public suspend fun <OtherScope: MultishotScope<@UnsafeVariance Region>, R> scoped(scope: OtherScope, block: suspend OtherScope.() -> R): R {
    check(this@MultishotScope.token === token) {
      "Cannot use a MultishotScope from another scope: ${this@MultishotScope} vs $token"
    }
    return suspendCoroutineUninterceptedOrReturn {
      block.startCoroutineUninterceptedOrReturn(scope, it)
    }
  }

  @ResetDsl
  public suspend inline fun <T, R> SubCont<T, R, Region>.resumeWith(value: Result<T>): R =
    suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  public suspend operator fun <T, R> SubCont<T, R, Region>.invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun <T, R> SubCont<T, R, Region>.resumeWithException(exception: Throwable): R =
    resumeWith(Result.failure(exception))

  public suspend inline fun <T, R> runReader(
    value: T,
    noinline fork: T.() -> T = { this },
    noinline body: suspend context(Reader<T>) MultishotScope<Region>.() -> R
  ): R = suspendCoroutineAndTrampoline { stack ->
    val reader = ReaderCont(stack, value, fork)
    body.startCoroutineUninterceptedOrReturn(reader, reader, reader)
  }

  @PublishedApi
  internal suspend inline fun <T> suspendCoroutineToTrampoline(
    crossinline block: (SplitSeq<T, Region>) -> Unit
  ): T = suspendCoroutineUninterceptedOrReturn {
    block(token.collectStack(it))
    COROUTINE_SUSPENDED
  }

  @PublishedApi
  internal suspend inline fun <T> suspendCoroutineAndTrampoline(
    crossinline block: (SplitSeq<T, Region>) -> Any?
  ): T = suspendCoroutineUninterceptedOrReturn {
    val stack = token.collectStack(it)
    stack.handleTrampolining(runCatching { block(stack) })
  }

  @PublishedApi
  internal suspend inline fun suspendCoroutineForever(
    crossinline block: () -> Unit
  ): Nothing = suspendCoroutineUninterceptedOrReturn {
    block()
    COROUTINE_SUSPENDED
  }
}

public sealed class MultishotToken<out Region>(@JvmField @PublishedApi internal val trampoline: Trampoline) :
  MultishotScope<Region>() {
  override val token get() = this
}

public abstract class DelegatingMultishotScope<out Region>(override val token: MultishotToken<Region>) :
  MultishotScope<Region>()

@PublishedApi
internal fun <T, Region2> (suspend MultishotToken<Region2>.() -> T).startCoroutineIntercepted(seq: SplitSeq<T, Region2>) {
  seq.trampoline.nextStep = SequenceBodyStep(this, seq)
}

@PublishedApi
internal fun <R, T, Region2> (suspend MultishotToken<Region2>.(R) -> T).startCoroutineIntercepted(
  receiver: R,
  seq: SplitSeq<T, Region2>,
) {
  seq.trampoline.nextStep = SequenceBodyReceiverStep(this, receiver, seq)
}

@PublishedApi
internal fun <Start> SplitSeq<Start, *>.resumeWithIntercepted(result: Result<Start>) {
  if (result.exceptionOrNull() !== SuspendedException) {
    trampoline.nextStep = SequenceResumeStep(this, result)
  }
}

@PublishedApi
internal tailrec fun FramesCont<*, *, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()


@ResetDsl
public suspend inline fun <R, Region> MultishotScope<Region>.newReset(noinline body: suspend PromptCont<R, *, Region>.() -> R): R =
  suspendCoroutineAndTrampoline { stack ->
    val prompt = PromptCont(stack)
    body.startCoroutineUninterceptedOrReturn(prompt, prompt)
  }


context(p: Prompt<R, IR, OR>)
@ResetDsl
public suspend inline fun <T, R, IR : OR, OR> MultishotScope<IR>.shift(
  noinline body: suspend MultishotScope<OR>.(SubCont<T, R, OR>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
}

context(p: Prompt<R, IR, OR>)
@ResetDsl
public suspend inline fun <T, R, IR : OR, OR> MultishotScope<IR>.shiftOnce(
  noinline body: suspend MultishotScope<OR>.(SubCont<T, R, OR>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R, IR, OR>)
@ResetDsl
public suspend inline fun <T, R, IR : OR, OR> MultishotScope<IR>.shiftWithFinal(
  noinline body: suspend MultishotScope<OR>.(Pair<SubCont<T, R, OR>, SubCont<T, R, OR>>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE) to SubCont(init), rest)
}

context(p: Prompt<R, IR, OR>)
@ResetDsl
public suspend inline fun <T, R, IR : OR, OR> MultishotScope<IR>.shiftRepushing(
  noinline body: suspend MultishotScope<OR>.(SubCont<T, R, OR>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
}

// Acts like shift0/control { it(body()) }
context(p: Prompt<P, IR, OR>)
@ResetDsl
public suspend inline fun <T, P, IR : OR, OR> MultishotScope<IR>.inHandlingContext(
  noinline body: suspend MultishotScope<OR>.(SubCont<T, P, OR>) -> T
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
}

context(p: Prompt<P, IR, OR>)
@ResetDsl
public suspend inline fun <T, P, IR : OR, OR> MultishotScope<IR>.inHandlingContextTwice(
  noinline body: suspend MultishotScope<OR>.(SubCont<T, P, OR>) -> T
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p)
  body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
}

// This isn't effect safe, but that's intentional because we want to bridge with `Raise`
context(p: Prompt<R, *, *>)
public fun <R> abortWith(value: Result<R>): Nothing {
  p.rest.resumeWithIntercepted(value)
  throw SuspendedException
}

context(p: Prompt<R, *, *>)
public suspend inline fun <Region, R> MultishotScope<Region>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineForever {
    p.rest.resumeWithIntercepted(value)
  }

// This isn't effect safe, but that's intentional because we want to bridge with `Raise`
context(p: Prompt<R, *, OR>)
public fun <OR, R> abortS(value: suspend MultishotScope<OR>.() -> R): Nothing {
  value.startCoroutineIntercepted(p.rest)
  throw SuspendedException
}