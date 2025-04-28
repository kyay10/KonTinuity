package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

internal expect class StackTraceElement
internal expect interface CoroutineStackFrame {
  val callerFrame: CoroutineStackFrame?
  fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

@JvmInline
internal value class Frame<in A, B>(val cont: Continuation<A>) {
  fun resumeWith(value: Result<A>, into: Continuation<B>) = cont.copy(into).resumeWith(value)

  // B only exists existentially, so it's fine to have it refer to the completion's type
  @Suppress("UNCHECKED_CAST")
  val completion: Frame<B, *> get() = Frame<_, Any?>(cont.completion as Continuation<B>)
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val frame: Frame<Start, First>) {
  val head get() = frame
  val tail: FrameList<First, *, End>? get() = frame.completion.takeUnless { it.cont is WrapperCont }?.let(::FrameList)
}

internal sealed interface SplitSeq<in Start> : CoroutineStackFrame {
  val context: CoroutineContext
}

internal sealed interface FrameCont<in Start> : SplitSeq<Start>

internal tailrec fun <Start> SplitSeq<Start>.resumeWith(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *, *> -> with(resumeAndCollectResult(result)) {
    usingResult { return seq.resumeWith(it) }
  }

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

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : FrameCont<Start> {
  override val context: CoroutineContext = underlying.context
  override val callerFrame: CoroutineStackFrame? = (underlying as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (underlying as? CoroutineStackFrame)?.getStackTraceElement()
}

// frame :: frames ::: rest
internal class FramesCont<Start, First, Last>(
  private var frames: FrameList<Start, First, Last>, rest: SplitSeq<Last>,
  val wrapperCont: WrapperCont<Last>?,
) : FrameCont<Start>, Segmentable<Start, Last>(rest) {
  val head get() = frames.head

  @Suppress("UNCHECKED_CAST")
  fun resumeAndCollectResult(result: Result<Start>): WrapperCont<*> = if (wrapperCont == null) {
    val head = head
    val tail: SplitSeq<First> = frames.tail?.let {
      frames = it as FrameList<Start, First, Last>
      this as FramesCont<First, *, Last>
    } ?: rest as SplitSeq<First>
    val wrapper = WrapperCont(tail, isWaitingForValue = true)
    head.resumeWith(result, wrapper)
    wrapper
  } else {
    wrapperCont.beginWaitingForValue()
    head.cont.resumeWith(result)
    wrapperCont
  }

  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = FramesSegment(frames, seg)

  override val callerFrame: CoroutineStackFrame? get() = (head.cont as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (head.cont as? CoroutineStackFrame)?.getStackTraceElement()
}

// frame :: frames ::: rest
@PublishedApi
internal class UnderCont<Start, RealStart>(
  val captured: Segment<RealStart, Start>, rest: SplitSeq<Start>
) : Segmentable<RealStart, Start>(rest), FrameCont<RealStart> {
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
internal class WrapperCont<T>(var seq: SplitSeq<T>, isWaitingForValue: Boolean = false) : Continuation<T>,
  CoroutineContext, CoroutineStackFrame {
  private var result: Result<T> = if (isWaitingForValue) waitingForValue else hasBeenIntercepted

  fun beginWaitingForValue() {
    result = waitingForValue
  }

  fun endWaitingForValue() {
    result = hasBeenIntercepted
  }

  internal inline fun usingResult(block: (Result<T>) -> Unit) {
    contract {
      callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    val result = result
    endWaitingForValue()
    if (result != waitingForValue && result != hasBeenIntercepted && SuspendedException != result.exceptionOrNull()) {
      block(result)
    }
  }

  var realContext = seq.context.unwrap()
    set(value) {
      field = value.unwrap()
    }

  override val context: CoroutineContext get() = this

  override fun resumeWith(result: Result<T>) {
    if (this.result == waitingForValue) {
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

private data object WaitingForValue : Throwable()

private val waitingForValue = Result.failure<Nothing>(WaitingForValue)

private data object HasBeenIntercepted : Throwable()

private val hasBeenIntercepted = Result.failure<Nothing>(HasBeenIntercepted)

internal class PromptCont<Start>(
  val p: Prompt<Start>, rest: SplitSeq<Start>
) : Segmentable<Start, Start>(rest) {
  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = PromptSegment(this.p, seg)
}

internal class ReaderCont<State, Start>(
  val p: Reader<State>,
  private var _state: State,
  val fork: State.() -> State,
  rest: SplitSeq<Start>,
  var forkOnFirstRead: Boolean = false
) : Segmentable<Start, Start>(rest) {
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
  val frames: FrameList<Start, First, End>, override val init: Segment<FurtherStart, Start>
) : Contable<FurtherStart, Start, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<Start> = FramesCont(frames, stack, null)
}

internal data class UnderSegment<FurtherStart, Start, End>(
  val captured: Segment<Start, End>, override val init: Segment<FurtherStart, Start>
) : Contable<FurtherStart, Start, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<Start> = UnderCont(captured, stack)
}

internal data class PromptSegment<Start, End>(
  val prompt: Prompt<End>, override val init: Segment<Start, End>
) : Contable<Start, End, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<End> = stack.pushPrompt(prompt)
}

internal data class ReaderSegment<State, Start, End>(
  val prompt: Reader<State>, val state: State, val fork: State.() -> State, override val init: Segment<Start, End>
) : Contable<Start, End, End> {
  override fun toCont(stack: SplitSeq<End>): SplitSeq<End> = stack.pushReader(prompt, state, fork).apply {
    forkOnFirstRead = true
  }
}

// Expects that cont eventually refers to box
@PublishedApi
internal data class SingleUseSegment<Start, End>(
  val delimiter: PromptCont<End>, val cont: SplitSeq<Start>, var hasBeenCopied: Boolean = false
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

internal sealed class Segmentable<Start, Rest>(var rest: SplitSeq<Rest>) : SplitSeq<Start> {
  override val context: CoroutineContext = rest.context
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