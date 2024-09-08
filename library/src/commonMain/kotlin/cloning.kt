import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.jvm.JvmInline

internal expect val Continuation<*>.isCompilerGenerated: Boolean
internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

@JvmInline
internal value class Frame<in A, out B>(val cont: Continuation<A>) {
  fun resumeWith(value: Result<A>, into: Continuation<B>) = cont.copy(into).resumeWith(value)
  fun resumeWithIntercepted(value: Result<A>, into: Continuation<B>) = cont.copy(into).intercepted().resumeWith(value)
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val frame: Frame<Start, First>) {
  @Suppress("UNCHECKED_CAST")
  private val completion: Continuation<First> get() = frame.cont.completion as Continuation<First>
  fun head() = frame
  fun tail(): FrameList<First, *, End>? =
    if (this.completion is WrapperCont) null else FrameList<First, Nothing, End>(Frame(this.completion))
}

internal sealed interface SplitSeq<in Start, First, out End> {
  val context: CoroutineContext
}

internal fun <Start, First, End> SplitSeq<Start, First, End>.resumeWith(result: Result<Start>, isIntercepted: Boolean) {
  val exception = result.exceptionOrNull()
  if (exception is SeekingStackException) exception.use(this)
  else resumeWithImpl(result, isIntercepted)
}

private tailrec fun <Start, First, End> SplitSeq<Start, First, End>.resumeWithImpl(
  result: Result<Start>, isIntercepted: Boolean
) {
  when (this) {
    is EmptyCont -> (if (isIntercepted) underlying.intercepted() else underlying).resumeWith(
      result
    )

    is FramesCont<Start, First, *, End> -> if (wrapperCont == null) {
      if (isIntercepted) {
        head.resumeWithIntercepted(result, WrapperCont(tail))
      } else {
        val tail = tail
        val wrapper = WrapperCont(tail, isWaitingForValue = true)
        head.resumeWith(result, wrapper)
        val res = wrapper.result
        if (res != waitingForValue && res != hasBeenIntercepted) {
          val exception = res.exceptionOrNull()
          if (exception is SeekingStackException) exception.use(tail)
          else tail.resumeWithImpl(res, false)
        } else {
          wrapper.result = hasBeenIntercepted
        }
      }
    } else {
      val wrapper = wrapperCont
      val rest = rest!! as SplitSeq<Any?, *, *>
      wrapper.seq = rest
      wrapper.realContext = rest.context
      if (isIntercepted) {
        wrapper.result = hasBeenIntercepted
        frames.head().cont.intercepted().resumeWith(result)
      } else {
        wrapper.result = waitingForValue
        frames.head().cont.resumeWith(result)
        val res = wrapper.result
        if (res != waitingForValue && res != hasBeenIntercepted) {
          val exception = res.exceptionOrNull()
          if (exception is SeekingStackException) exception.use(rest)
          else rest.resumeWithImpl(res, false)
        } else {
          wrapper.result = hasBeenIntercepted
        }
      }
    }

    is PromptCont -> rest!!.resumeWithImpl(result, isIntercepted = isIntercepted)

    is ReaderCont<*, Start, First, End> -> rest!!.resumeWithImpl(result, isIntercepted = isIntercepted)
  }
}

internal tailrec fun <Start, First, End, P, FurtherStart, FurtherFirst> SplitSeq<Start, First, End>.splitAtAux(
  p: Prompt<P>, seg: Segment<FurtherStart, FurtherFirst, Start>
): Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, First, *, End> -> (rest!! as SplitSeq<Any?, *, End>).splitAtAux(p, FramesSegment(frames, seg))
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    val pair: Pair<Segment<FurtherStart, FurtherFirst, Start>, SplitSeq<Start, *, End>> = seg to rest!!
    @Suppress("UNCHECKED_CAST")
    pair as Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>>
  } else {
    rest!!.splitAtAux(p, PromptSegment(this.p, seg))
  }

  is ReaderCont<*, Start, First, End> -> rest!!.splitAtAux(p, toSegment(seg))
}

private fun <State, Start, First, End, FurtherStart, FurtherFirst> ReaderCont<State, Start, First, End>.toSegment(seg: Segment<FurtherStart, FurtherFirst, Start>): ReaderSegment<State, FurtherStart, FurtherFirst, Start> =
  ReaderSegment(p, state, fork, seg)

internal tailrec fun <Start, First, End> SplitSeq<Start, First, End>.deleteReader(
  p: Reader<*>, previous: ExpectsSequenceStartingWith<*>
): Unit = when (this) {
  is EmptyCont -> error("Reader not found $p")
  is FramesCont<Start, *, *, End> -> rest!!.deleteReader(p, this)
  is PromptCont -> rest!!.deleteReader(p, this)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) {
    previous as ExpectsSequenceStartingWith<Start>
    previous.sequence = rest!!
  } else rest!!.deleteReader(p, this)
}

internal tailrec fun <Start, End, P> SplitSeq<Start, *, End>.find(p: Prompt<P>): SplitSeq<P, *, End> = when (this) {
  is EmptyCont -> error("Prompt not found $p")

  is FramesCont<Start, *, *, End> -> rest!!.find(p)
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    @Suppress("UNCHECKED_CAST")
    this.rest!! as SplitSeq<P, *, End>
  } else rest!!.find(p)

  is ReaderCont<*, Start, *, End> -> rest!!.find(p)
}

internal tailrec fun <Start, End, P> SplitSeq<Start, *, End>.findGuyBefore(
  p: Prompt<P>,
  previous: ExpectsSequenceStartingWith<Start>?
): ExpectsSequenceStartingWith<P>? = when (this) {
  is EmptyCont -> error("Prompt not found $p")

  is FramesCont<Start, *, *, End> -> (rest!! as SplitSeq<Any?, *, End>).findGuyBefore(p, this)
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    @Suppress("UNCHECKED_CAST")
    previous as ExpectsSequenceStartingWith<P>?
  } else rest!!.findGuyBefore(p, this)

  is ReaderCont<*, Start, *, End> -> rest!!.findGuyBefore(p, this)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.find(p: Reader<S>): S = when (this) {
  is EmptyCont -> error("Reader not found $p")
  is FramesCont<Start, *, *, End> -> rest!!.find(p)
  is PromptCont -> rest!!.find(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) {
    @Suppress("UNCHECKED_CAST")
    this.state as S
  } else rest!!.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.findOrNull(p: Reader<S>): S? = when (this) {
  is EmptyCont -> null
  is FramesCont<Start, *, *, End> -> rest!!.findOrNull(p)
  is PromptCont -> rest!!.findOrNull(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) {
    @Suppress("UNCHECKED_CAST")
    this.state as S
  } else rest!!.findOrNull(p)
}

internal fun <Start, First, End, P> SplitSeq<Start, First, End>.splitAt(p: Prompt<P>): Pair<Segment<Start, *, P>, SplitSeq<P, *, End>> =
  splitAtAux(p, EmptySegment)

internal fun <Start, First, End, P> SplitSeq<Start, First, End>.splitAtOnce(p: Prompt<P>): Pair<Segment<Start, *, P>, SplitSeq<P, *, End>> {
  val box = findGuyBefore(p, null)
  return if (box != null) {
    SingleUseSegment(box, this) to box.sequence!!.also { box.sequence = null } as SplitSeq<P, *, End>
  } else {
    EmptySegment to this as SplitSeq<P, *, End>
  }
}

internal fun <P, First, End> SplitSeq<P, First, End>.pushPrompt(p: Prompt<P>): PromptCont<P, First, End> =
  PromptCont(p, this)

internal fun <S, P, First, End> SplitSeq<P, First, End>.pushReader(
  p: Reader<S>, value: S, fork: S.() -> S
): ReaderCont<S, P, First, End> = ReaderCont(p, value, fork, this)

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : SplitSeq<Start, Nothing, Nothing> {
  override val context: CoroutineContext = underlying.context
}

// frame :: frames ::: rest
internal data class FramesCont<Start, First, Last, End>(
  val frames: FrameList<Start, First, Last>, var rest: SplitSeq<Last, *, End>?,
  val wrapperCont: WrapperCont<Last>?,
) : SplitSeq<Start, First, End>, ExpectsSequenceStartingWith<Last> {
  val head get() = frames.head()

  @Suppress("UNCHECKED_CAST")
  val tail: SplitSeq<First, *, End>
    get() = frames.tail()?.let { FramesCont(it, rest, wrapperCont) }
      ?: rest as SplitSeq<First, *, End> // First == Last, but the compiler doesn't get it

  override val context: CoroutineContext = rest!!.context
  override var sequence: SplitSeq<Last, *, *>?
    get() = rest
    set(value) {
      rest = value as SplitSeq<Last, *, End>?
    }
}

internal fun CoroutineContext.unwrap(): CoroutineContext =
  if (this is WrapperCont<*>) this.realContext else this

internal class WrapperCont<T>(seq: SplitSeq<T, *, *>, isWaitingForValue: Boolean = false) : Continuation<T>,
  CoroutineContext {
  var seq: SplitSeq<T, *, *>? = seq
  var result: Result<T> = if (isWaitingForValue) waitingForValue else hasBeenIntercepted
  var realContext = seq.context.unwrap()
    set(value) {
      field = value.unwrap()
    }

  override val context: CoroutineContext get() = this

  override fun resumeWith(result: Result<T>) {
    if (this.result == waitingForValue) {
      this.result = result
    } else {
      seq!!.resumeWith(result, isIntercepted = false)
    }
  }

  // TODO improve these implementations
  override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext.get(key)
}

private data object WaitingForValue : Throwable()

private val waitingForValue = Result.failure<Nothing>(WaitingForValue)

private data object HasBeenIntercepted : Throwable()

private val hasBeenIntercepted = Result.failure<Nothing>(HasBeenIntercepted)

internal data class PromptCont<Start, First, End>(
  val p: Prompt<Start>, var rest: SplitSeq<Start, First, End>?
) : SplitSeq<Start, First, End>, ExpectsSequenceStartingWith<Start> {
  override val context = rest!!.context
  override var sequence: SplitSeq<Start, *, *>?
    get() = rest
    set(value) {
      rest = value as SplitSeq<Start, First, End>?
    }
}

internal data class ReaderCont<State, Start, First, End>(
  val p: Reader<State>, val state: State, val fork: State.() -> State, var rest: SplitSeq<Start, First, End>?
) : SplitSeq<Start, First, End>, ExpectsSequenceStartingWith<Start> {
  override val context = rest!!.context

  override var sequence: SplitSeq<Start, *, *>?
    get() = rest
    set(value) {
      rest = value as SplitSeq<Start, First, End>?
    }
}

// sub continuations / stack segments
// mirrors the stack, and so is in reverse order. allows easy access to the state
// stored in the current prompt
internal sealed interface Segment<in Start, First, out End>

internal tailrec infix fun <Start, First, End, FurtherEnd> Segment<Start, First, End>.prependTo(stack: SplitSeq<End, *, FurtherEnd>): SplitSeq<Start, *, FurtherEnd> =
  when (this) {
    is EmptySegment -> {
      // Start == End
      stack as SplitSeq<Start, *, FurtherEnd>
    }

    is FramesSegment<Start, First, *, *, End> -> init prependTo FramesCont(frames, stack, null)

    is PromptSegment<Start, First, End> -> init prependTo PromptCont(prompt, stack)
    is ReaderSegment<*, Start, First, End> -> init prependTo ReaderCont(prompt, fork(state), fork, stack)

    is SingleUseSegment<Start, First, End> -> {
      box.sequence = stack
      cont as SplitSeq<Start, *, FurtherEnd>
    }
  }

internal data object EmptySegment : Segment<Any?, Nothing, Nothing>

internal data class FramesSegment<FurtherStart, FurtherFirst, Start, First, End>(
  val frames: FrameList<Start, First, End>, val init: Segment<FurtherStart, FurtherFirst, Start>
) : Segment<FurtherStart, FurtherFirst, End>

internal data class PromptSegment<Start, First, End>(
  val prompt: Prompt<End>, val init: Segment<Start, First, End>
) : Segment<Start, First, End>

internal data class ReaderSegment<State, Start, First, End>(
  val prompt: Reader<State>, val state: State, val fork: (State.() -> State), val init: Segment<Start, First, End>
) : Segment<Start, First, End>

// Expects that cont eventually refers to box
internal data class SingleUseSegment<Start, First, End>(
  val box: ExpectsSequenceStartingWith<End>, val cont: SplitSeq<Start, First, *>
) : Segment<Start, First, End>

internal sealed interface ExpectsSequenceStartingWith<Start> {
  var sequence: SplitSeq<Start, *, *>?
}

internal fun <R> collectStack(continuation: Continuation<R>): SplitSeq<R, *, *> =
  findNearestWrapperCont(continuation).toFramesCont(continuation)

private fun <R, T> WrapperCont<T>.toFramesCont(
  continuation: Continuation<R>
): FramesCont<R, Any?, T, Any?> =
  FramesCont(FrameList(Frame(continuation)), seq!!.also { seq = null }, this)

internal fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*, *, *> =
  findNearestWrapperCont(continuation).seq!!

private fun findNearestWrapperCont(continuation: Continuation<*>): WrapperCont<*> =
  continuation.context as? WrapperCont<*> ?: error("No WrapperCont found in stack")