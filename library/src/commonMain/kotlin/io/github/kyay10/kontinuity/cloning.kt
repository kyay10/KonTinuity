package io.github.kyay10.kontinuity

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

internal tailrec fun <Start> SplitSeq<Start>.resumeWith(result: Result<Start>) {
  result.onFailure {
    if (it is SeekingStackException) return it.use(this)
  }
  with(frameCont()) {
    when (this) {
      is EmptyCont -> underlying.resumeWith(result)

      is FramesCont<Start, *, *> if (wrapperCont != null) -> {
        reattachFrames()
        wrapperCont.beginWaitingForValue()
        head.cont.resumeWith(result)
        wrapperCont.usingResult { return rest.resumeWith(it) }
      }

      is FramesCont<Start, *, *> -> with(resumeCopiedHeadAndCollectResult(result)) {
        // seq == tail
        usingResult { return seq.resumeWith(it) }
      }
    }
  }
}

internal tailrec fun <Start, P, FurtherStart> SplitSeq<Start>.splitAtAux(
  prompt: Prompt<P>, seg: Segment<FurtherStart, Start>
): Pair<Segment<FurtherStart, P>, SplitSeq<P>> = when (this) {
  is PromptCont if (prompt === this.p) -> {
    // Start and P are now unified, but the compiler doesn't get it
    val pair: Pair<Segment<FurtherStart, Start>, SplitSeq<Start>> = seg to rest
    @Suppress("UNCHECKED_CAST")
    pair as Pair<Segment<FurtherStart, P>, SplitSeq<P>>
  }

  is EmptyCont -> error("Prompt not found $prompt in $seg")
  is Segmentable<Start, *> -> rest.splitAtAux(prompt, toSegment(seg))
}

internal tailrec fun <Start> SplitSeq<Start>.deleteReader(
  p: Reader<*>, previous: ExpectsSequenceStartingWith<Start>?
): Unit = when (this) {
  is ReaderCont<*, Start> if (p === this.p) -> previous?.rest = rest
  is EmptyCont -> error("Reader not found $p")
  is ExpectsSequenceStartingWith<*> -> rest.deleteReader(p, self)
}

internal tailrec fun <Start, P> SplitSeq<Start>.find(p: Prompt<P>): SplitSeq<P> = when (this) {
  is PromptCont if (p === this.p) -> {
    // Start and P are now unified, but the compiler doesn't get it
    @Suppress("UNCHECKED_CAST")
    rest as SplitSeq<P>
  }

  is EmptyCont -> error("Prompt not found $p")
  is ExpectsSequenceStartingWith<*> -> rest.find(p)
}

internal tailrec fun <Start, P> SplitSeq<Start>.findSeqBefore(
  p: Prompt<P>,
  previous: ExpectsSequenceStartingWith<Start>?
): ExpectsSequenceStartingWith<P>? = when (this) {
  is PromptCont if (p === this.p) -> {
    // Start and P are now unified, but the compiler doesn't get it
    @Suppress("UNCHECKED_CAST")
    previous as ExpectsSequenceStartingWith<P>?
  }

  is EmptyCont -> error("Prompt not found $p")

  is ExpectsSequenceStartingWith<*> -> rest.findSeqBefore(p, self)
}

internal tailrec fun <Start, S> SplitSeq<Start>.find(p: Reader<S>): S = when (this) {
  is ReaderCont<*, Start> if (p === this.p) -> {
    // S == this.S
    @Suppress("UNCHECKED_CAST")
    state as S
  }

  is EmptyCont -> error("Reader not found $p")
  is ExpectsSequenceStartingWith<*> -> rest.find(p)
}

internal tailrec fun <Start, S> SplitSeq<Start>.findOrNull(p: Reader<S>): S? = when (this) {
  is ReaderCont<*, Start> if (p === this.p) -> {
    @Suppress("UNCHECKED_CAST")
    state as S // S == this.S
  }

  is EmptyCont -> null
  is ExpectsSequenceStartingWith<*> -> rest.findOrNull(p)
}

internal fun <Start, P> SplitSeq<Start>.splitAt(p: Prompt<P>): Pair<Segment<Start, P>, SplitSeq<P>> =
  splitAtAux(p, EmptySegment)

internal fun <Start, P> SplitSeq<Start>.splitAtOnce(p: Prompt<P>): Pair<Segment<Start, P>, SplitSeq<P>> {
  val box = findSeqBefore(p, null)
  return if (box != null) {
    SingleUseSegment(box, this) to (box.rest as PromptCont).rest.also {
      box.clear()
    }
  } else {
    // Start and P are now unified, but the compiler doesn't get it
    // because box == null iff this is PromptCont && p === this.p
    @Suppress("UNCHECKED_CAST")
    EmptySegment to this as SplitSeq<P>
  }
}

internal fun <P> SplitSeq<P>.pushPrompt(p: Prompt<P>): PromptCont<P> =
  PromptCont(p, this)

internal fun <S, P> SplitSeq<P>.pushReader(
  p: Reader<S>, value: S, fork: S.() -> S
): ReaderCont<S, P> = ReaderCont(p, value, fork, this)

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : FrameCont<Start> {
  override val context: CoroutineContext = underlying.context
  override val callerFrame: CoroutineStackFrame? = (underlying as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (underlying as? CoroutineStackFrame)?.getStackTraceElement()
}

// frame :: frames ::: rest
internal data class FramesCont<Start, First, Last>(
  private val frames: FrameList<Start, First, Last>, override var _rest: SplitSeq<Last>?,
  val wrapperCont: WrapperCont<Last>?,
) : FrameCont<Start>, Segmentable<Start, Last>() {
  val head get() = frames.head

  @Suppress("UNCHECKED_CAST")
  val tail: SplitSeq<First>
    get() = frames.tail?.let { FramesCont(it, rest, wrapperCont) }
      ?: rest as SplitSeq<First> // First == Last, but the compiler doesn't get it

  fun resumeCopiedHeadAndCollectResult(result: Result<Start>): WrapperCont<First> {
    val wrapper = WrapperCont(tail, isWaitingForValue = true)
    head.resumeWith(result, wrapper)
    return wrapper
  }

  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = FramesSegment(frames, seg)

  override val context = rest.context
  override val callerFrame: CoroutineStackFrame? = (head.cont as? CoroutineStackFrame)?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = (head.cont as? CoroutineStackFrame)?.getStackTraceElement()
}

internal fun CoroutineContext.unwrap(): CoroutineContext =
  if (this is WrapperCont<*>) realContext else this

internal class WrapperCont<T>(seq: SplitSeq<T>, isWaitingForValue: Boolean = false) : Continuation<T>,
  CoroutineContext, CoroutineStackFrame {
  private var _seq: SplitSeq<T>? = seq
  var seq: SplitSeq<T>
    get() = _seq ?: error("No sequence found")
    set(value) {
      _seq = value
      realContext = value.context
    }

  fun clear() {
    _seq = null
  }

  private var result: Result<T> = if (isWaitingForValue) waitingForValue else hasBeenIntercepted

  fun beginWaitingForValue() {
    result = waitingForValue
  }

  fun endWaitingForValue() {
    result = hasBeenIntercepted
  }

  inline fun usingResult(block: (Result<T>) -> Unit) {
    contract {
      callsInPlace(block, kotlin.contracts.InvocationKind.AT_MOST_ONCE)
    }
    val result = result
    endWaitingForValue()
    if (result != waitingForValue && result != hasBeenIntercepted) {
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
      checkNotNull(_seq) { "No sequence to resume with result $result" }.resumeWith(result)
    }
  }

  override val callerFrame: CoroutineStackFrame?
    get() = _seq?.callerFrame

  override fun getStackTraceElement(): StackTraceElement? =
    _seq?.getStackTraceElement()

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

internal data class PromptCont<Start>(
  val p: Prompt<Start>, override var _rest: SplitSeq<Start>?
) : Segmentable<Start, Start>() {
  override val context = rest.context
  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = PromptSegment(this.p, seg)
}

internal data class ReaderCont<State, Start>(
  val p: Reader<State>, private var _state: State, private val fork: State.() -> State, override var _rest: SplitSeq<Start>?,
  private var forkOnFirstRead: Boolean = false
) : Segmentable<Start, Start>() {
  val state: State
    get() {
      if (forkOnFirstRead) {
        forkOnFirstRead = false
        _state = fork(_state)
      }
      return _state
    }

  override val context = rest.context
  override fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>) = ReaderSegment(p, _state, fork, seg)
}

// sub continuations / stack segments
// mirrors the stack, and so is in reverse order. allows easy access to the state
// stored in the current prompt
internal sealed interface Segment<in Start, out End>

internal tailrec infix fun <Start, End> Segment<Start, End>.prependTo(stack: SplitSeq<End>): SplitSeq<Start> =
  when (this) {
    is EmptySegment -> {
      // Start == End
      @Suppress("UNCHECKED_CAST")
      stack as SplitSeq<Start>
    }

    is FramesSegment<Start, *, *, End> -> init prependTo FramesCont(frames, stack, null)

    is PromptSegment -> init prependTo PromptCont(prompt, stack)
    is ReaderSegment<*, Start, End> -> init prependTo ReaderCont(
      prompt,
      state,
      fork,
      stack,
      forkOnFirstRead = true
    )

    is SingleUseSegment -> {
      box.rest = stack
      cont
    }
  }

internal data object EmptySegment : Segment<Any?, Nothing>

internal data class FramesSegment<FurtherStart, Start, First, End>(
  val frames: FrameList<Start, First, End>, val init: Segment<FurtherStart, Start>
) : Segment<FurtherStart, End>

internal data class PromptSegment<Start, End>(
  val prompt: Prompt<End>, val init: Segment<Start, End>
) : Segment<Start, End>

internal data class ReaderSegment<State, Start, End>(
  val prompt: Reader<State>, val state: State, val fork: State.() -> State, val init: Segment<Start, End>
) : Segment<Start, End>

// Expects that cont eventually refers to box
internal data class SingleUseSegment<Start, End>(
  val box: ExpectsSequenceStartingWith<End>, val cont: SplitSeq<Start>
) : Segment<Start, End>

internal sealed class ExpectsSequenceStartingWith<Start> : CoroutineStackFrame {
  val self: ExpectsSequenceStartingWith<Start> get() = this
  protected abstract var _rest: SplitSeq<Start>?
  fun clear() {
    _rest = null
  }

  var rest
    get() = _rest ?: error("No rest found")
    set(value) {
      _rest = value
    }
  override val callerFrame: CoroutineStackFrame? get() = _rest?.callerFrame
  override fun getStackTraceElement(): StackTraceElement? = _rest?.getStackTraceElement()
}

internal sealed class Segmentable<Start, Rest> : SplitSeq<Start>, ExpectsSequenceStartingWith<Rest>() {
  abstract fun <FurtherStart> toSegment(seg: Segment<FurtherStart, Start>): Segment<FurtherStart, Rest>
}

internal fun <R> collectStack(continuation: Continuation<R>): SplitSeq<R> =
  findNearestWrapperCont(continuation).deattachFrames(continuation)

private fun <R, T> WrapperCont<T>.deattachFrames(
  continuation: Continuation<R>
): FramesCont<R, Any?, T> =
  FramesCont(FrameList(Frame(continuation)), seq.also { clear() }, this)

internal tailrec fun <Start> SplitSeq<Start>.frameCont(): FrameCont<Start> =
  when (this) {
    is FrameCont -> this
    is ReaderCont<*, Start> -> rest.frameCont()
    is PromptCont -> rest.frameCont()
  }

internal fun FrameCont<*>.reattachFrames() = when (this) {
  is EmptyCont<*> -> Unit
  is FramesCont<*, *, *> -> reattachFrames()
}

private fun <Start, First, Last> FramesCont<Start, First, Last>.reattachFrames() {
  (wrapperCont ?: return).seq = rest
}

internal fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*> =
  findNearestWrapperCont(continuation).seq

private fun findNearestWrapperCont(continuation: Continuation<*>): WrapperCont<*> =
  continuation.context as? WrapperCont<*> ?: error("No WrapperCont found in stack")