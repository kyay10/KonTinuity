package io.github.kyay10.kontinuity.internal

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmInline

internal const val REENTRANT_NOT_SUPPORTED = "Reentrant resumptions are not supported"
internal const val SEGMENT_ALREADY_USED = "Segment was already used once, but is being reused again"

internal expect class StackTraceElement
internal expect interface CoroutineStackFrame {
  val callerFrame: CoroutineStackFrame?
  fun getStackTraceElement(): StackTraceElement?
}

internal expect fun <Start> Stack<Start>.copy(rest: Marker<*, *>): Stack<Start>

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
internal sealed class SplitCont<in Start>(val trampoline: Trampoline) :
  CoroutineContext by trampoline, SplitSeq<Start>(), SplitContOrSegment {
  final override val context: SplitCont<Start> get() = this
}

internal class EmptyCont<Start>(val underlying: Continuation<Start>, trampoline: Trampoline) :
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

  class Under<Start, End>(val captured: Segment<Start, End>, val stack: Stack<End>, val rest: SplitCont<*>) :
    SplitSeq<Start>() {
    val wrapped: Stack<Start> get() = Companion(this)
    override val context get() = rest

    override val callerFrame: CoroutineStackFrame? get() = stack.frames as? CoroutineStackFrame

    override fun resume(result: Result<Start>) = captured.prependToFinal(stack, rest).resumeWith(result)
  }

  companion object {
    operator fun <Start> invoke(frames: Continuation<Start>): Stack<Start> = Frames<_, Any?>(frames)
  }
}

internal sealed class Marker<in Start, S>(trampoline: Trampoline) : SplitCont<Start>(trampoline) {
  abstract val stack: Stack<Start>
  final override val callerFrame: CoroutineStackFrame? get() = stack.frames as? CoroutineStackFrame

  open fun underflow(): Stack<Start> = stack

  final override fun resume(result: Result<Start>): Unit = underflow().resumeWith(result)

  abstract fun onSuspend(): S
  abstract fun onResume(state: S, rest: Marker<*, *>, isFinal: Boolean)
}

internal class Prompt<Start>(
  override var stack: Stack<Start>,
  rest: SplitCont<*>,
) : Marker<Start, Continuation<Start>>(rest.trampoline) {
  var rest: SplitContOrSegment? = rest

  override fun underflow(): Stack<Start> = stack.also { rest = null }
  override fun onSuspend() = stack.frames
  override fun onResume(state: Continuation<Start>, rest: Marker<*, *>, isFinal: Boolean) {
    stack = if (isFinal) Stack(state) else Stack(state).copy(rest)
  }
}

internal abstract class Finalizer<Start, S>(override val stack: Stack<Start>, val rest: Marker<*, *>) :
  Marker<Start, S>(rest.trampoline)

internal object CompletedContinuation : Continuation<Any?> {
  override val context: CoroutineContext
    get() = error("This continuation is already complete")

  override fun resumeWith(result: Result<Any?>) {
    error("This continuation is already complete")
  }

  override fun toString(): String = "This continuation is already complete"
}

internal val SEGMENT_USED = arrayOfNulls<Any?>(0)

@PublishedApi
internal class Segment<Start, End>(
  val delimiter: Prompt<End>,
  val start: Stack<Start>,
  val startRest: Marker<*, *>,
) : SplitContOrSegment {
  var values: Array<Any?>? = null

  init {
    delimiter.stack = Stack(CompletedContinuation)
    delimiter.rest = this
  }
}

internal expect fun <Start, End> Segment<Start, End>.prependToFinal(
  stack: Stack<End>,
  rest: SplitCont<*>
): Stack<Start>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException")
@PublishedApi
internal data object SuspendedException : NoTrace()