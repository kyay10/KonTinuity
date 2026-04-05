package io.github.kyay10.kontinuity.internal

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

internal expect fun <T> Stack<T>.copy(): Stack<T>

internal abstract class SplitSeq<in T> : Continuation<T>, CoroutineStackFrame {
  final override fun resumeWith(result: Result<T>) {
    if (result.exceptionOrNull() !== SuspendedException) context.onErrorResume { resume(result) }
  }

  final override val callerFrame: CoroutineStackFrame? get() = stack.frames as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null

  protected abstract fun resume(result: Result<T>)
  abstract override val context: SplitCont<*>
  protected abstract val stack: Stack<*>
}

internal sealed interface SplitContOrSegment

@PublishedApi
internal sealed class SplitCont<in T>(val trampoline: Trampoline) : CoroutineContext by trampoline, SplitSeq<T>(),
  SplitContOrSegment {
  final override val context: SplitCont<T> get() = this
}

internal class EmptyCont<T>(override val stack: Stack<T>, trampoline: Trampoline) : SplitCont<T>(trampoline) {
  init {
    trampoline.emptyCont = this
  }

  override fun resume(result: Result<T>) = stack.frames.resumeWith(result)
}

internal typealias Stack<T> = Frames<T, *>

@Suppress("unused")
@PublishedApi
@JvmInline
internal value class Frames<in T, Next> private constructor(val frames: Continuation<T>) {
  companion object {
    operator fun <Start> invoke(frames: Continuation<Start>): Stack<Start> = Frames<_, Any?>(frames)
  }
}

internal class Under<T, R>(val captured: Segment<T, R>, public override val stack: Stack<R>, val rest: SplitCont<*>) :
  SplitSeq<T>() {
  override val context get() = rest
  override fun resume(result: Result<T>) = captured.prependToFinal(stack, rest).frames.resumeWith(result)
}

internal sealed class Marker<T, S>(trampoline: Trampoline) : SplitCont<T>(trampoline) {
  abstract val rest: SplitContOrSegment?
  abstract override val stack: Stack<T>
  open fun underflow(): Stack<T> = stack

  final override fun resume(result: Result<T>): Unit = underflow().frames.resumeWith(result)

  abstract fun onSuspend(): S
  abstract fun onResume(state: S, isFinal: Boolean)
}

internal class Prompt<Start>(public override var stack: Stack<Start>, rest: SplitCont<*>) :
  Marker<Start, Continuation<Start>>(rest.trampoline) {
  override var rest: SplitContOrSegment? = rest
  override fun underflow(): Stack<Start> = stack.also { rest = null }
  override fun onSuspend() = stack.frames
  override fun onResume(state: Continuation<Start>, isFinal: Boolean) {
    stack = if (isFinal) Stack(state) else Stack(state).copy()
  }
}

internal abstract class Finalizer<Start, S>(override val stack: Stack<Start>, override val rest: Marker<*, *>) :
  Marker<Start, S>(rest.trampoline)

internal val SEGMENT_USED = arrayOfNulls<Any?>(0)

internal class Segment<in Start, out End>(
  val delimiter: Prompt<out End>,
  val start: Stack<Start>,
  val startRest: Marker<*, *>,
  var values: Array<Any?>? = null
) : SplitContOrSegment {
  init {
    delimiter.rest = this
  }
}

internal expect fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, rest: SplitCont<*>): Stack<Start>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException")
@PublishedApi
internal data object SuspendedException : NoTrace()