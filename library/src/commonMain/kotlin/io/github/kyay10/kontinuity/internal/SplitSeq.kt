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

internal expect fun <T> Stack<T>.copy(rest: Marker<*, *>): Stack<T>

internal abstract class SplitSeq<in T> : Continuation<T>, CoroutineStackFrame {
  final override fun resumeWith(result: Result<T>) {
    if (result.exceptionOrNull() !== SuspendedException) context.trampoline.onErrorResume { resume(result) }
  }

  final override val callerFrame: CoroutineStackFrame?
    get() = stack.frames as? CoroutineStackFrame

  final override fun getStackTraceElement(): StackTraceElement? = null

  protected abstract fun resume(result: Result<T>)

  abstract override val context: SplitCont<*>
  protected abstract val stack: Stack<*>
}

internal sealed interface SplitContOrSegment

@PublishedApi
internal sealed class SplitCont<in T>(val trampoline: Trampoline) :
  CoroutineContext by trampoline, SplitSeq<T>(), SplitContOrSegment {
  final override val context: SplitCont<T>
    get() = this
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
    operator fun <T> invoke(frames: Continuation<T>): Stack<T> = Frames<_, Any?>(frames)
  }
}

internal class Under<T, R>(
  val captured: Segment<T, R>,
  public override val stack: Stack<R>,
  override val context: SplitCont<*>,
) : SplitSeq<T>() {
  override fun resume(result: Result<T>) = captured.prependToFinal(stack, context).frames.resumeWith(result)
}

internal sealed class Marker<T, S>(trampoline: Trampoline) : SplitCont<T>(trampoline) {
  // TODO make final
  abstract val rest: SplitContOrSegment?
  abstract override val stack: Stack<T>

  open fun underflow(): Stack<T> = stack

  final override fun resume(result: Result<T>): Unit = underflow().frames.resumeWith(result)

  abstract fun onSuspend(): S

  abstract fun onResume(state: S, rest: Marker<*, *>, isFinal: Boolean)
}

internal class Prompt<T>(public override var stack: Stack<T>, rest: SplitCont<*>) :
  Marker<T, Continuation<T>>(rest.trampoline) {
  override var rest: SplitContOrSegment? = rest

  override fun underflow(): Stack<T> = stack.also { rest = null }

  override fun onSuspend() = stack.frames

  override fun onResume(state: Continuation<T>, rest: Marker<*, *>, isFinal: Boolean) {
    stack = if (isFinal) Stack(state) else Stack(state).copy(rest)
  }

  inline fun underflow(block: Trampoline.(Stack<T>) -> Unit) = trampoline.block(underflow())
}

internal abstract class Finalizer<T, S>(override val stack: Stack<T>, override val rest: Marker<*, *>) :
  Marker<T, S>(rest.trampoline)

internal val SEGMENT_USED = arrayOfNulls<Any?>(0)

internal class Segment<in T, out R>(val delimiter: Prompt<out R>, val start: Stack<T>, val startRest: Marker<*, *>) :
  SplitContOrSegment {
  var values: Array<Any?>? = null

  init {
    delimiter.rest = this
  }
}

internal expect fun <T, R> Segment<T, R>.prependToFinal(stack: Stack<R>, rest: SplitCont<*>): Stack<T>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING") internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException") internal data object SuspendedException : NoTrace()
