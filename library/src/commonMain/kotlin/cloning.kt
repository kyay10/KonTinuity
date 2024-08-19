import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.jvm.JvmInline

internal expect val Continuation<*>.isCompilerGenerated: Boolean
internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

internal inline fun Continuation<*>.forEach(block: (Continuation<*>) -> Unit) {
  var current: Continuation<*> = this
  while (true) {
    block(current)
    current = when (current) {
      in CompilerGenerated -> current.completion
      else -> error("Continuation $current is not see-through, so its stack can't be traversed")
    }
  }
}

internal object CompilerGenerated {
  operator fun contains(cont: Continuation<*>): Boolean = cont.isCompilerGenerated
}

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
    if (completion is EmptyContinuation) null else FrameList<First, Nothing, End>(Frame(completion))
}

internal sealed interface SplitSeq<in Start, First, out End> : Continuation<Start> {
  override fun resumeWith(result: Result<Start>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else resumeWithImpl(
      result, isIntercepted = when (this) {
        is InterceptedCont -> true
        is PromptCont -> true
        else -> false
      }
    )
  }
}

private fun <Start> SplitSeq<Start, *, *>.resumeWithImpl(result: Result<Start>, isIntercepted: Boolean) {
  var current: SplitSeq<Start, *, *> = this
  var isIntercepted = isIntercepted
  while (true) {
    when (current) {
      is EmptyCont -> return (if (isIntercepted) current.underlying.intercepted() else current.underlying).resumeWith(
        result
      )

      is FramesCont<Start, *, *, *> -> return current.resumeWithImpl(result, isIntercepted)
      is InterceptedCont -> {
        current = current.rest
        isIntercepted = true
      }

      is PromptCont -> {
        current = current.rest
        isIntercepted = true
      }

      is ReaderCont<*, Start, *, *> -> current = current.rest
    }
  }
}

internal tailrec fun <Start, First, End, P, FurtherStart, FurtherFirst> SplitSeq<Start, First, End>.splitAtAux(
  p: Prompt<P>, seg: Segment<FurtherStart, FurtherFirst, Start>
): Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, First, *, End> -> rest.splitAtAux(p, FramesSegment(frames, seg))
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    val pair: Pair<Segment<FurtherStart, FurtherFirst, Start>, SplitSeq<Start, *, End>> = seg to rest
    @Suppress("UNCHECKED_CAST")
    pair as Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>>
  } else {
    rest.splitAtAux(p, PromptSegment(this.p, seg))
  }

  is ReaderCont<*, Start, First, End> -> rest.splitAtAux(p, toSegment(seg))
  is InterceptedCont -> rest.splitAtAux(p, InterceptedSegment(seg))
}

private fun <State, Start, First, End, FurtherStart, FurtherFirst> ReaderCont<State, Start, First, End>.toSegment(seg: Segment<FurtherStart, FurtherFirst, Start>): ReaderSegment<State, FurtherStart, FurtherFirst, Start> =
  ReaderSegment(p, state, fork, seg)

internal tailrec fun <Start, First, End, FurtherStart, FurtherFirst> SplitSeq<Start, First, End>.splitAtAux(
  p: Reader<*>, seg: Segment<FurtherStart, FurtherFirst, Start>
): Pair<Segment<FurtherStart, FurtherFirst, *>, SplitSeq<*, *, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, First, *, End> -> rest.splitAtAux(p, FramesSegment(frames, seg))
  is PromptCont -> rest.splitAtAux(p, PromptSegment(this.p, seg))
  is ReaderCont<*, Start, First, End> -> if (p === this.p) {
    seg to rest
  } else rest.splitAtAux(p, toSegment(seg))

  is InterceptedCont -> rest.splitAtAux(p, InterceptedSegment(seg))
}

internal tailrec fun <Start, End, P> SplitSeq<Start, *, End>.find(p: Prompt<P>): SplitSeq<P, *, End> = when (this) {
  is EmptyCont -> error("Prompt not found $p")

  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    @Suppress("UNCHECKED_CAST")
    this.rest as SplitSeq<P, *, End>
  } else rest.find(p)

  is ReaderCont<*, Start, *, End> -> rest.find(p)
  is InterceptedCont -> rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.find(p: Reader<S>): S = when (this) {
  is EmptyCont -> error("Reader not found $p")
  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> rest.find(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) {
    @Suppress("UNCHECKED_CAST")
    this.state as S
  } else rest.find(p)

  is InterceptedCont -> rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.findOrNull(p: Reader<S>): S? = when (this) {
  is EmptyCont -> null
  is FramesCont<Start, *, *, End> -> rest.findOrNull(p)
  is PromptCont -> rest.findOrNull(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) {
    @Suppress("UNCHECKED_CAST")
    this.state as S
  } else rest.find(p)

  is InterceptedCont -> rest.findOrNull(p)
}

internal fun <Start, First, End, P> SplitSeq<Start, First, End>.splitAt(p: Prompt<P>): Pair<Segment<Start, *, P>, SplitSeq<P, *, End>> =
  splitAtAux(p, emptySegment())

internal fun <Start, First, End> SplitSeq<Start, First, End>.splitAt(p: Reader<*>): Pair<Segment<Start, *, *>, SplitSeq<*, *, End>> =
  splitAtAux(p, emptySegment())

internal fun <P, First, End> SplitSeq<P, First, End>.pushPrompt(p: Prompt<P>): PromptCont<P, First, End> =
  PromptCont(p, this)

internal fun <S, P, First, End> SplitSeq<P, First, End>.pushReader(
  p: Reader<S>, value: S, fork: S.() -> S
): ReaderCont<S, P, First, End> = ReaderCont(p, value, fork, this)

internal fun <P, First, End> SplitSeq<P, First, End>.intercepted(): SplitSeq<P, First, End> =
  if (this is InterceptedCont || this is PromptCont) this else InterceptedCont(this)

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : SplitSeq<Start, Nothing, Nothing> {
  override val context: CoroutineContext = underlying.context
}

internal fun <Start, First, End, FurtherEnd> FrameList<Start, First, End>.asFramesCont(rest: SplitSeq<End, *, FurtherEnd>): SplitSeq<Start, First, FurtherEnd> =
  FramesCont(this, rest)

// frame :: frames ::: rest
internal data class FramesCont<Start, First, Last, End>(
  val frames: FrameList<Start, First, Last>, val rest: SplitSeq<Last, *, End>
) : SplitSeq<Start, First, End> {
  val head get() = frames.head()

  @Suppress("UNCHECKED_CAST")
  val tail: SplitSeq<First, *, End> = frames.tail()?.asFramesCont(rest)
    ?: rest as SplitSeq<First, *, End> // First == Last, but the compiler doesn't get it

  fun resumeWithImpl(result: Result<Start>, isIntercepted: Boolean) = if (isIntercepted) {
    head.resumeWithIntercepted(result, tail)
  } else {
    head.resumeWith(result, tail)
  }

  override val context: CoroutineContext = rest.context
}

internal data class PromptCont<Start, First, End>(
  val p: Prompt<Start>, val rest: SplitSeq<Start, First, End>
) : SplitSeq<Start, First, End> {
  override val context = rest.context
}

internal data class ReaderCont<State, Start, First, End>(
  val p: Reader<State>, val state: State, val fork: State.() -> State, val rest: SplitSeq<Start, First, End>
) : SplitSeq<Start, First, End> {
  override val context = rest.context
}

// TODO: flatten down by instead making a special version of FramesCont and EmptyCont
internal data class InterceptedCont<Start, First, End>(
  val rest: SplitSeq<Start, First, End>
) : SplitSeq<Start, First, End> {
  override val context = rest.context
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

    is FramesSegment<Start, First, *, *, End> -> init prependTo FramesCont(frames, stack)

    is PromptSegment<Start, First, End> -> init prependTo PromptCont(prompt, stack)
    is ReaderSegment<*, Start, First, End> -> init prependTo toCont(stack)
    is InterceptedSegment<Start, First, End> -> init prependTo InterceptedCont(stack)
  }

private fun <State, Start, First, End, FurtherEnd> ReaderSegment<State, Start, First, End>.toCont(stack: SplitSeq<End, *, FurtherEnd>): ReaderCont<State, End, *, FurtherEnd> =
  ReaderCont(prompt, fork(state), fork, stack)

internal fun <T> emptySegment(): Segment<T, *, T> = EmptySegment as Segment<T, *, T>

internal data object EmptySegment : Segment<Any?, Nothing, Any?>

internal data class FramesSegment<FurtherStart, FurtherFirst, Start, First, End>(
  val frames: FrameList<Start, First, End>, val init: Segment<FurtherStart, FurtherFirst, Start>
) : Segment<FurtherStart, FurtherFirst, End>

internal data class InterceptedSegment<Start, First, End>(
  val init: Segment<Start, First, End>
) : Segment<Start, First, End>

internal data class PromptSegment<Start, First, End>(
  val prompt: Prompt<End>, val init: Segment<Start, First, End>
) : Segment<Start, First, End>

internal data class ReaderSegment<State, Start, First, End>(
  val prompt: Reader<State>, val state: State, val fork: (State.() -> State), val init: Segment<Start, First, End>
) : Segment<Start, First, End>

internal fun <R> collectStack(continuation: Continuation<R>): SplitSeq<R, *, *> {
  val list = mutableListOf<Continuation<*>>()
  val last: SplitSeq<*, *, *> = run {
    continuation.forEach {
      if (it is SplitSeq<*, *, *>) {
        return@run it
      }
      list.add(it)
    }
    error("No SplitSeq found in stack")
  }
  var current: Continuation<*> = EmptyContinuation(last.context)
  for (i in list.lastIndex downTo 1) {
    current = list[i].copy(current)
  }
  return FrameList<R, Any?, Nothing>(Frame(continuation.copy(current))).asFramesCont(last)
}

internal fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*, *, *> {
  continuation.forEach {
    if (it is SplitSeq<*, *, *>) {
      return it
    }
  }
  error("No SplitSeq found in stack")
}

private data class EmptyContinuation(override val context: CoroutineContext) : Continuation<Any?> {
  override fun resumeWith(result: Result<Any?>) = error("No continuation to resume with $result")
}