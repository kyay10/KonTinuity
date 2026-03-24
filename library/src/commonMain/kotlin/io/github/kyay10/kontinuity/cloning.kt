package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

internal const val REENTRANT_NOT_SUPPORTED = "Reentrant resumptions are not supported"
internal const val SEGMENT_ALREADY_USED = "Segment was already used once, but is being reused again"

internal expect class StackTraceElement
internal expect interface CoroutineStackFrame {
  val callerFrame: CoroutineStackFrame?
  fun getStackTraceElement(): StackTraceElement?
}

internal abstract class SplitSeq<in Start> : Continuation<Start>, CoroutineStackFrame {
  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) context.onErrorResume { resume(result) }
  }

  final override fun getStackTraceElement(): StackTraceElement? = null

  protected abstract fun resume(result: Result<Start>)
  abstract override val context: SplitCont<*>
}

internal interface SplitContOrSegment

internal inline fun SplitContOrSegment?.ifSegment(block: (Segment<*, *>?) -> Nothing): SplitCont<*> {
  contract {
    returns() implies (this@ifSegment is SplitCont<*>)
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
  }
  return if (this is Segment<*, *>?) block(this) else this as SplitCont<*>
}

@PublishedApi
internal sealed class SplitCont<in Start>(@JvmField internal val trampoline: Trampoline) :
  CoroutineContext by trampoline, SplitSeq<Start>(), SplitContOrSegment {
  final override val context: SplitCont<Start> get() = this
}

internal fun <Start> SplitCont<Start>.errorIfEmptyCont(): Marker<Start> =
  this as? Marker<Start> ?: error(REENTRANT_NOT_SUPPORTED)

context(p: Prompt<P>)
@PublishedApi
internal inline fun <Start, P, R> Stack<Start>.splitAt(
  rest: SplitCont<*>,
  block: (Stack<P>, Segment<Start, P>) -> R
): R = block(p.stack, makeSegment(rest))

@PublishedApi
internal val <Start> Prompt<Start>.stack: Stack<Start> get() = prompt.frames

context(p: Prompt<P>)
@PublishedApi
internal fun <Start, P> Stack<Start>.makeSegment(rest: SplitCont<*>): Segment<Start, P> =
  Segment(p.prompt, this, rest.errorIfEmptyCont())

internal class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>, trampoline: Trampoline) :
  SplitCont<Start>(trampoline) {
  init {
    trampoline.emptyCont = this
  }

  override val callerFrame: CoroutineStackFrame? get() = underlying as? CoroutineStackFrame

  override fun resume(result: Result<Start>) = underlying.resumeWith(result)
}

internal typealias Stack<Start> = Frames<Start, *>

@Suppress("unused")
@PublishedApi
@JvmInline
internal value class Frames<in Start, Next> private constructor(val frames: Continuation<Start>) {
  fun resumeWith(result: Result<Start>) = frames.resumeWith(result)

  internal class Under<Start, End>(val captured: Segment<Start, End>, val frames: Stack<End>, val rest: SplitCont<*>) :
    SplitSeq<Start>() {
    val wrapped: Stack<Start> get() = Companion(this)
    override val context get() = rest

    override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame

    override fun resume(result: Result<Start>) = captured.prependToFinal(frames, rest).resumeWith(result)
  }

  companion object {
    @PublishedApi
    internal operator fun <Start> invoke(frames: Continuation<Start>): Stack<Start> = Frames<_, Any?>(frames)
  }
}

@JvmInline
public value class Prompt<Start> internal constructor(internal val prompt: PromptCont<Start>)

internal class PromptCont<Start> internal constructor(
  override var frames: Stack<Start>,
  rest: SplitCont<*>,
) : Marker<Start>(rest.trampoline) {
  @JvmField
  internal var rest: SplitContOrSegment? = rest

  override fun underflow(): Stack<Start> = super.underflow().also { rest = null }
}

@JvmInline
public value class Reader<out S> internal constructor(internal val reader: ReaderCont<*, out S>) {
  public val value: S get() = reader.value
  public val unsafeValue: S get() = reader.unsafeValue
}

internal class ReaderCont<Start, S>(
  private val fork: S.() -> S,
  @JvmField internal var state: S,
  override val frames: Stack<Start>,
  @JvmField internal val rest: SplitCont<*>,
) : Marker<Start>(rest.trampoline) {
  internal class ForkOnFirstRead(state: Any?) {
    @JvmField
    val state: Any? = if (state is ForkOnFirstRead) state.state else state
  }

  @Suppress("UNCHECKED_CAST")
  val value: S
    get() {
      val state = state
      return if (state is ForkOnFirstRead) fork(state.state as S).also { this.state = it } else state
    }

  @Suppress("UNCHECKED_CAST")
  val unsafeValue: S
    get() {
      val state = state
      return if (state is ForkOnFirstRead) state.state as S else state
    }
}

internal sealed class Marker<in Start>(trampoline: Trampoline) : SplitCont<Start>(trampoline) {
  internal abstract val frames: Stack<Start>
  final override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame

  internal open fun underflow(): Stack<Start> = frames

  final override fun resume(result: Result<Start>): Unit = underflow().resumeWith(result)
}

@PublishedApi
internal expect class Segment<Start, End> internal constructor(
  delimiter: PromptCont<End>,
  start: Stack<Start>,
  startRest: Marker<*>
) : SplitContOrSegment {
  val trampoline: Trampoline
  fun prependToFinal(stack: Stack<End>, stackRest: SplitCont<*>): Stack<Start>
}