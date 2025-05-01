package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

internal expect class StackTraceElement
internal expect interface CoroutineStackFrame {
  val callerFrame: CoroutineStackFrame?
  fun getStackTraceElement(): StackTraceElement?
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
  val completion: Frame<B, *> get() = Frame<_, Any?>(cont.completion as Continuation<B>)
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val frame: Frame<Start, First>) {
  val head get() = frame
  val tail: FrameList<First, *, End>?
    get() {
      val completion = frame.completion
      return if (completion.cont is WrapperCont) null
      else FrameList(completion)
    }
}

internal sealed interface SplitSeq<in Start> : CoroutineStackFrame {
  val context: CoroutineContext
}

internal sealed interface FrameCont<in Start> : SplitSeq<Start>

internal tailrec fun <Start> SplitSeq<Start>.resumeWith(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *, *> if !copied -> with(resumeAndCollectResult(result)) {
    usingResult { return seq.resumeWith(it) }
  }

  is FramesCont<Start, *, *> -> resumeCopiedAndCollectResult(result) { seq, res -> return seq.resumeWith(res) }

  is UnderCont<*, Start> -> (captured prependTo rest).resumeWith(result)
  is PromptCont<Start> -> rest.resumeWith(result)
  is ReaderCont<*, Start> -> rest.resumeWith(result)
}

@PublishedApi
internal fun <Start, P> SplitSeq<Start>.splitAt(p: Prompt<P>) =
  SingleUseSegment(p.cont, this) to p.cont.rest

@PublishedApi
internal fun <P> SplitSeq<P>.pushPrompt(p: Prompt<P>): PromptCont<P> = PromptCont(p, this).also { p.cont = it }

@PublishedApi
internal fun <S, P> SplitSeq<P>.pushReader(
  p: Reader<S>, value: S, fork: S.() -> S
): ReaderCont<S, P> = ReaderCont(p, value, fork, this).also { p.cont = it }

internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) : FrameCont<Start> {
  override val context: CoroutineContext = underlying.context
  override val callerFrame: CoroutineStackFrame? = (underlying as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (underlying as? CoroutineStackFrame)?.getStackTraceElement()
}

// frame :: frames ::: rest
internal class FramesCont<Start, First, Last>(
  private var frames: FrameList<Start, First, Last>, override val rest: SplitSeq<Last>,
  private val wrapperCont: WrapperCont<Last>, @JvmField val copied: Boolean = false
) : FrameCont<Start>, Segmentable<Start, Last>(rest.context) {
  val head get() = frames.head

  @Suppress("UNCHECKED_CAST")
  inline fun resumeCopiedAndCollectResult(result: Result<Start>, resumer: (SplitSeq<First>, Result<First>) -> Unit) {
    val head = head
    val tail: SplitSeq<First> = frames.tail?.let {
      frames = it as FrameList<Start, First, Last>
      this as FramesCont<First, *, Last>
    } ?: rest as SplitSeq<First>
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

  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = FramesSegment(frames, seg, wrapperCont)

  override val callerFrame: CoroutineStackFrame? get() = (head.cont as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (head.cont as? CoroutineStackFrame)?.getStackTraceElement()
}

// frame :: frames ::: rest
@PublishedApi
internal class UnderCont<Start, RealStart>(
  @JvmField val captured: Segment<RealStart, Start>, override val rest: SplitSeq<Start>
) : Segmentable<RealStart, Start>(rest.context), FrameCont<RealStart> {
  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, RealStart>): Segment<FurtherStart, Start> =
    UnderSegment(
      when (captured) {
        is SingleUseSegment -> captured.makeReusable()
        else -> captured
      }, seg
    )
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

  override val callerFrame: CoroutineStackFrame? get() = seq.callerFrame

  override fun getStackTraceElement(): StackTraceElement? = seq.getStackTraceElement()

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

@PublishedApi
internal class PromptCont<Start>(
  @JvmField val p: Prompt<Start>, override var rest: SplitSeq<Start>
) : Segmentable<Start, Start>(rest.context) {
  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = PromptSegment(this.p, seg)
}

internal class ReaderCont<State, Start>(
  @JvmField val p: Reader<State>,
  private var _state: State,
  @JvmField val fork: State.() -> State,
  override val rest: SplitSeq<Start>,
  @JvmField var forkOnFirstRead: Boolean = false
) : Segmentable<Start, Start>(rest.context) {
  val state: State
    get() {
      if (forkOnFirstRead) {
        forkOnFirstRead = false
        _state = fork(_state)
      }
      return _state
    }

  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = ReaderSegment(p, _state, fork, seg)
}

// sub continuations / stack segments
// mirrors the stack, and so is in reverse order. allows easy access to the state
// stored in the current prompt
internal sealed interface Segment<in Start, out End>
internal sealed interface Contable<in Start, Middle, out End> : Segment<Start, End> {
  fun toCont(stack: SplitSeq<End>): SplitSeq<Middle>
  val init: Segment<Start, Middle>
}

internal tailrec infix fun <Start, End> Segment<Start, End>.prependTo(stack: SplitSeq<End>): SplitSeq<Start> =
  when (this) {
    is EmptySegment -> {
      // Start == End
      @Suppress("UNCHECKED_CAST")
      stack as SplitSeq<Start>
    }

    is SingleUseSegment -> {
      repushValues()
      delimiter.rest = stack
      cont
    }

    is Contable<Start, *, End> -> init prependTo toCont(stack)
  }

internal data object EmptySegment : Segment<Any?, Nothing>

internal data class FramesSegment<FurtherStart, Start, First, End>(
  val frames: FrameList<Start, First, End>,
  override val init: Segment<FurtherStart, Start>,
  private val wrapperCont: WrapperCont<End>
) : Contable<FurtherStart, Start, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<Start> = FramesCont(frames, stack, wrapperCont, true)
}

internal data class UnderSegment<FurtherStart, Start, End>(
  @JvmField val captured: Segment<Start, End>, override val init: Segment<FurtherStart, Start>
) : Contable<FurtherStart, Start, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<Start> = UnderCont(captured, stack)
}

internal data class PromptSegment<Start, End>(
  @JvmField val prompt: Prompt<End>, override val init: Segment<Start, End>
) : Contable<Start, End, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<End> = stack.pushPrompt(prompt)
}

internal data class ReaderSegment<State, Start, End>(
  @JvmField val prompt: Reader<State>,
  @JvmField val state: State,
  @JvmField val fork: State.() -> State,
  override val init: Segment<Start, End>
) : Contable<Start, End, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<End> = stack.pushReader(prompt, state, fork).apply {
    forkOnFirstRead = true
  }
}

// Expects that cont eventually refers to box
@PublishedApi
internal data class SingleUseSegment<Start, End>(
  @JvmField val delimiter: PromptCont<End>, @JvmField val cont: SplitSeq<Start>, @JvmField var hasBeenCopied: Boolean = false
) : Segment<Start, End> {
  @PublishedApi
  internal fun makeReusable(): Segment<Start, End> = cont.makeReusable(delimiter, EmptySegment).also {
    this.hasBeenCopied = true
  }

  fun repushValues() {
    if (hasBeenCopied) {
      cont.repushValues(delimiter)
    }
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.repushValues(
  delimiter: PromptCont<End>
): Unit = when (this) {
  is PromptCont -> {
    p.cont = this
    if (delimiter !== this) rest.repushValues(delimiter) else Unit
  }

  is ReaderCont<*, Start> -> {
    p as Reader<Any?>
    @Suppress("UNCHECKED_CAST")
    p.cont = this as ReaderCont<Any?, Start>
    rest.repushValues(delimiter)
  }

  is EmptyCont -> error("Box not found $delimiter in $this")
  is Segmentable<*, *> -> rest.repushValues(delimiter)
}

private tailrec fun <Start, End, FurtherStart> SplitSeq<Start>.makeReusable(
  delimiter: PromptCont<End>, seg: Segment<FurtherStart, Start>
): Segment<FurtherStart, End> = when (this) {
  is PromptCont<Start> if (delimiter === this) -> {
    @Suppress("UNCHECKED_CAST")
    (this as Segmentable<Start, End>).toSegment(seg)
  }

  is EmptyCont -> error("Box not found $delimiter in $seg")
  is Segmentable<Start, *> -> rest.makeReusable(delimiter, toSegment(seg))
}

internal sealed class Segmentable<Start, Rest>(override val context: CoroutineContext) : SplitSeq<Start> {
  abstract val rest: SplitSeq<Rest>
  override val callerFrame: CoroutineStackFrame? get() = rest.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = rest.getStackTraceElement()
  abstract fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>): Segment<FurtherStart, Rest>
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