package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
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

internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

@JvmInline
internal value class Frame<in A, B>(val cont: Continuation<A>) {
  fun resumeWith(value: Result<A>, into: Continuation<B>) = cont.copy(into).resumeWith(value)
  fun invokeSuspend(value: Result<A>, into: Continuation<B>) = cont.copy(into).invokeSuspend(value)

  // B only exists existentially, so it's fine to have it refer to the completion's type
  @Suppress("UNCHECKED_CAST")
  val completion: Frame<B, B> get() = Frame(cont.completion as Continuation<B>)
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val frame: Frame<Start, First>) {
  val head get() = frame
  val tail: FrameList<First, First, End>?
    get() {
      val completion = frame.completion
      return if (completion.cont is WrapperCont) null
      else FrameList(completion)
    }
}

public sealed interface SplitSeq<in Start> : CoroutineStackFrame {
  public val context: CoroutineContext
}

internal sealed interface FrameCont<in Start> : SplitSeq<Start>

internal tailrec fun <Start> SplitSeq<Start>.resumeWith(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *, *> if !copied -> with(resumeAndCollectResult(result)) {
    usingResult { return seq.resumeWith(it) }
  }

  is FramesCont<Start, *, *> -> resumeCopiedAndCollectResult(result) { seq, res -> return seq.resumeWith(res) }

  is Prompt<Start> -> rest.resumeWith(result)
  is ReaderT<*, Start> -> rest.resumeWith(result)
}

@PublishedApi
internal fun <Start, P> SplitSeq<Start>.splitAt(p: Prompt<P>) =
  SingleUseSegment(p, this) to p.rest

internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) : FrameCont<Start> {
  override val context: CoroutineContext = underlying.context
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
}

// frame :: frames ::: rest
internal class FramesCont<Start, First, Last>(
  var frames: FrameList<Start, First, Last>, override val rest: SplitSeq<Last>,
  private val wrapperCont: WrapperCont<Last>, override var copied: Boolean = false
) : FrameCont<Start>, Segmentable<Start, Last>(rest.context) {
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
    val wrapper = wrapperCont as WrapperCont<First>
    wrapper.seq = tail
    val result = runCatching {
      head.invokeSuspend(result, wrapper).also {
        if (it === COROUTINE_SUSPENDED) return
      }
    } as Result<First>
    if (result.exceptionOrNull() === SuspendedException) return
    resumer(tail, result)
  }

  @Suppress("UNCHECKED_CAST")
  fun resumeAndCollectResult(result: Result<Start>): WrapperCont<*> {
    wrapperCont.seq = rest
    wrapperCont.beginWaitingForValue()
    head.cont.resumeWith(result)
    return wrapperCont
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

internal fun CoroutineContext.unwrap(): CoroutineContext =
  if (this is WrapperCont<*>) realContext else this

@PublishedApi
internal class WrapperCont<T>(@JvmField var seq: SplitSeq<T>) : Continuation<T>,
  CoroutineContext, CoroutineStackFrame {
  @Suppress("UNCHECKED_CAST")
  private var result: Result<T> = hasBeenIntercepted as Result<T>

  @Suppress("UNCHECKED_CAST")
  fun beginWaitingForValue() {
    result = waitingForValue as Result<T>
  }

  @Suppress("UNCHECKED_CAST")
  fun endWaitingForValue() {
    result = hasBeenIntercepted as Result<T>
  }

  internal inline fun usingResult(block: (Result<T>) -> Unit) {
    contract {
      callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    val result = result
    val resultOrNull = result.getOrNull()
    endWaitingForValue()
    if (Sentinel.WaitingForValue !== resultOrNull && Sentinel.HasBeenIntercepted !== resultOrNull && SuspendedException !== result.exceptionOrNull()) {
      block(result)
    }
  }

  var realContext = seq.context.unwrap()
    set(value) {
      field = value.unwrap()
    }

  override val context: CoroutineContext get() = this

  override fun resumeWith(result: Result<T>) {
    if (this.result.getOrNull() === Sentinel.WaitingForValue) {
      this.result = result
    } else {
      seq.resumeWith(result)
    }
  }

  override val callerFrame: CoroutineStackFrame? get() = seq

  override fun getStackTraceElement(): StackTraceElement? = null

  // TODO improve these implementations
  override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext[key]
}

internal enum class Sentinel {
  WaitingForValue, HasBeenIntercepted
}

private val waitingForValue: Result<*> = Result.success(Sentinel.WaitingForValue)

private val hasBeenIntercepted = Result.success(Sentinel.HasBeenIntercepted)

public class Prompt<Start> @PublishedApi internal constructor(
  @PublishedApi override var rest: SplitSeq<Start>
): Segmentable<Start, Start>(rest.context) {
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
): Segmentable<Start, Start>(rest.context) {
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
  @JvmField var values: Array<Any?> = emptyArray(),
  @JvmField val copying: Boolean = false
) {
  fun makeReusable(): SingleUseSegment<Start, End> {
    values = ArrayList<Any?>(10).apply {
      cont.collectValues(delimiter, this)
    }.toTypedArray()
    return SingleUseSegment(delimiter, cont, values, true)
  }
  fun repushValues() {
    val values = values
    if (values.isEmpty()) return
    cont.repushValues(delimiter, values, copying, 0)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.collectValues(
  delimiter: Prompt<End>,
  values: MutableList<Any?>
): Unit = when (this) {
  is EmptyCont<*> -> error("Delimiter not found $delimiter in $this")
  is Prompt<*> if this === delimiter -> {}
  is Segmentable<*, *> -> {
    values.add(value)
    values.add(copied)
    rest.collectValues(delimiter, values)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.repushValues(
  delimiter: Prompt<End>,
  values: Array<Any?>,
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

public sealed class Segmentable<Start, Rest>(override val context: CoroutineContext) : SplitSeq<Start> {
  internal abstract val rest: SplitSeq<Rest>
  override val callerFrame: CoroutineStackFrame? get() = rest
  override fun getStackTraceElement(): StackTraceElement? = null
  internal abstract var value: Any?
  internal abstract var copied: Boolean
}

@PublishedApi
internal fun <R> collectStack(continuation: Continuation<R>): FramesCont<R, *, *> =
  findNearestWrapperCont(continuation).deattachFrames(continuation)

private fun findNearestWrapperCont(continuation: Continuation<*>): WrapperCont<*> =
  continuation.context as? WrapperCont<*> ?: error("No WrapperCont found in stack")

private fun <R, T> WrapperCont<T>.deattachFrames(
  continuation: Continuation<R>
): FramesCont<R, Any?, T> =
  FramesCont(FrameList(Frame(continuation)), seq, this)