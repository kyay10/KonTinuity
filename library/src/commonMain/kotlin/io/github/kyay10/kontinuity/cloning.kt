package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

@JvmInline
internal value class Frame<in A, B>(val cont: Continuation<A>) {
  fun resumeWith(value: Result<A>, into: Continuation<B>) = cont.copy(into).resumeWith(value)
  fun invokeSuspend(value: Result<A>, into: Continuation<B>) = cont.copy(into).invokeSuspend(value)

  // B only exists existentially, so it's fine to have it refer to the completion's type
  @Suppress("UNCHECKED_CAST")
  val completion: Frame<B, B> get() = Frame(cont.completion as Continuation<B>? ?: error("Not a compiler generated continuation $cont"))
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val frame: Frame<Start, First>) {
  val head get() = frame
  val tail: FrameList<First, First, End>?
    get() {
      val completion = frame.completion
      return if (completion.cont is SplitSeq<*>) null
      else FrameList(completion)
    }
}

public sealed interface SplitSeq<in Start> : CoroutineStackFrame, CoroutineContext, Continuation<Start> {
  public val realContext: CoroutineContext

  override val context: CoroutineContext get() = this

  override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      resumeWithImpl(result)
    }
  }

  override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext[key]
}

internal sealed interface FrameCont<in Start> : SplitSeq<Start>

internal tailrec fun <Start> SplitSeq<Start>.resumeWithImpl(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *, *> if !copied -> resumeAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is FramesCont<Start, *, *> -> resumeCopiedAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is Prompt<Start> -> rest.resumeWithImpl(result)
  is ReaderT<*, Start> -> rest.resumeWithImpl(result)
  is UnderCont<*, Start> -> (captured prependTo rest).resumeWithImpl(result)
}

@PublishedApi
internal fun <Start, P> SplitSeq<Start>.splitAt(p: Prompt<P>) =
  SingleUseSegment(p, this) to p.rest

internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) : FrameCont<Start> {
  override val realContext: CoroutineContext = underlying.context
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
}

// frame :: frames ::: rest
internal class FramesCont<Start, First, Last>(
  private var frames: FrameList<Start, First, Last>, override val rest: SplitSeq<Last>,
  override var copied: Boolean = false
) : FrameCont<Start>, Segmentable<Start, Last>(rest.realContext) {
  val head get() = frames.head

  @Suppress("UNCHECKED_CAST")
  inline fun resumeCopiedAndCollectResult(result: Result<Start>, resumer: (SplitSeq<First>, Result<First>) -> Unit) {
    val head = head
    val t = frames.tail
    val tail: SplitSeq<First> = if (t != null) {
      this as FramesCont<First, First, Last>
      frames = t
      this
    } else rest as SplitSeq<First>
    val result = runCatching {
      head.invokeSuspend(result, tail).also {
        if (it === COROUTINE_SUSPENDED) return
      }
    } as Result<First>
    if (result.exceptionOrNull() === SuspendedException) return
    resumer(tail, result)
  }

  @Suppress("UNCHECKED_CAST")
  inline fun resumeAndCollectResult(result: Result<Start>, resumer: (SplitSeq<Last>, Result<Last>) -> Unit) {
    // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
    var current: Continuation<*> = head.cont
    var param: Result<Any?> = result
    while (true) {
      with(current as Continuation<Any?>) {
        val completion = completion ?: return this.resumeWith(param)// fail fast when trying to resume continuation without completion
        val outcome: Result<Any?> =
          try {
            val outcome = invokeSuspend(param)
            if (outcome === COROUTINE_SUSPENDED) return
            Result.success(outcome)
          } catch (exception: Throwable) {
            if (exception === SuspendedException) return
            Result.failure(exception)
          }
        //releaseIntercepted() // this state machine instance is terminating
        if (completion !is SplitSeq<*>) {
          // unrolling recursion via loop
          current = completion
          param = outcome
        } else {
          // top-level completion reached -- invoke and return
          return resumer(completion as SplitSeq<Last>, outcome as Result<Last>)
        }
      }
    }
  }

  override val callerFrame: CoroutineStackFrame? get() = head.cont as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
  override var value: Any?
    get() = frames.frame.cont
    set(value) {
      @Suppress("UNCHECKED_CAST")
      frames = FrameList(Frame(value as Continuation<Start>))
    }
}

public class Prompt<Start> @PublishedApi internal constructor(
  @PublishedApi override var rest: SplitSeq<Start>
) : Segmentable<Start, Start>(rest.realContext) {
  override var value: Any?
    get() = rest
    set(value) {
      @Suppress("UNCHECKED_CAST")
      rest = value as SplitSeq<Start>
    }
  override var copied: Boolean
    get() = false
    set(value) {}
}

public typealias Reader<S> = ReaderT<S, *>

public class ReaderT<S, Start> @PublishedApi internal constructor(
  override val rest: SplitSeq<Start>,
  @PublishedApi internal var _state: S,
  @PublishedApi internal val fork: S.() -> S,
) : Segmentable<Start, Start>(rest.realContext) {
  @PublishedApi internal var forkOnFirstRead: Boolean = false
  @PublishedApi
  internal val state: S
    get() {
      if (forkOnFirstRead) {
        forkOnFirstRead = false
        _state = fork(_state)
      }
      return _state
    }

  public fun ask(): S = state
  override var value: Any?
    get() = _state
    set(value) {
      @Suppress("UNCHECKED_CAST")
      _state = value as S
    }
  override var copied: Boolean by ::forkOnFirstRead
}

@PublishedApi
internal class UnderCont<Start, RealStart>(
  @JvmField val captured: SingleUseSegment<RealStart, Start>, override val rest: SplitSeq<Start>
) : Segmentable<RealStart, Start>(rest.realContext), FrameCont<RealStart> {
  override var value: Any?
    get() = null
    set(value) {
    }
  override var copied: Boolean
    get() = captured.copying
    set(value) {
      captured.copying = value
    }
}

internal infix fun <Start, End> SingleUseSegment<Start, End>.prependTo(stack: SplitSeq<End>): SplitSeq<Start> {
  repushValues()
  delimiter.rest = stack
  return cont
}

// Expects that cont eventually refers to box
@PublishedApi
internal class SingleUseSegment<Start, End>(
  @JvmField val delimiter: Prompt<End>,
  @JvmField val cont: SplitSeq<Start>,
  @JvmField var values: Array<out Any?>? = null,
  @JvmField var copying: Boolean = false
) {
  fun makeReusable(): SingleUseSegment<Start, End> {
    if (values == null) values = ArrayList<Any?>(10).apply {
      cont.collectValues(delimiter, this)
    }.toTypedArray()
    return SingleUseSegment(delimiter, cont, values, true)
  }

  fun makeCopy(): SingleUseSegment<Start, End> {
    if (values == null) values = ArrayList<Any?>(10).apply {
      cont.collectValues(delimiter, this)
    }.toTypedArray()
    return SingleUseSegment(delimiter, cont, null, true)
  }

  fun repushValues() {
    cont.repushValues(delimiter, values ?: return, copying, 0)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.collectValues(
  delimiter: Prompt<End>,
  values: MutableList<in Any?>
): Unit = when (this) {
  is EmptyCont<*> -> error("Delimiter not found $delimiter in $this")
  is Prompt<*> if this === delimiter -> {}
  is Segmentable<*, *> -> {
    values.add(value)
    values.add(copied)
    copied = true
    rest.collectValues(delimiter, values)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.repushValues(
  delimiter: Prompt<End>,
  values: Array<out Any?>,
  copying: Boolean,
  index: Int
): Unit = when (this) {
  is EmptyCont<*> -> error("Delimiter not found $delimiter in $this")
  is Prompt<*> if this === delimiter -> {}
  is Segmentable<*, *> -> {
    val value = values[index]
    val copied = values[index + 1] as Boolean
    this.value = value
    this.copied = copied || copying
    rest.repushValues(delimiter, values, copying, index + 2)
  }
}

public sealed class Segmentable<Start, Rest>(override val realContext: CoroutineContext) : SplitSeq<Start> {
  internal abstract val rest: SplitSeq<Rest>
  override val callerFrame: CoroutineStackFrame? get() = rest
  override fun getStackTraceElement(): StackTraceElement? = null
  internal abstract var value: Any?
  internal abstract var copied: Boolean
}

@PublishedApi
internal fun <R> collectStack(continuation: Continuation<R>): FramesCont<R, *, *> =
  findNearestSplitSeq(continuation).deattachFrames(continuation)

private fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*> =
  continuation.context as? SplitSeq<*> ?: error("No SplitSeq found in stack")

private fun <R, T> SplitSeq<T>.deattachFrames(
  continuation: Continuation<R>
): FramesCont<R, Any?, T> =
  FramesCont(FrameList(Frame(continuation)), this)