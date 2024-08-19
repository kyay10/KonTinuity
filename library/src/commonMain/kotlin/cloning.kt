import arrow.core.tail
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
  fun resumeWith(value: Result<A>, into: Continuation<B>) = cont.copy(into).intercepted().resumeWith(value)
}

@JvmInline
internal value class FrameList<in Start, First, out End>(val list: List<Continuation<*>>) {
  fun isEmpty() = list.isEmpty()
  fun head() = Frame<_, Any?>(list.first()) as Frame<Start, First>
  fun tail() = FrameList<First, Nothing, End>(list.tail()) as FrameList<First, *, End>
}

internal sealed interface SplitSeq<in Start, out End> : Continuation<Start> {
  val isEmpty: Boolean
  val head: Frame<Start, *>
  val tail: SplitSeq<*, End>

  override fun resumeWith(result: Result<Start>) = if (nonEmpty) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else {
      (head as Frame<Start, Any?>).resumeWith(result, tail as SplitSeq<Any?, End>)
    }
  } else error("No continuation to resume with $result")
}

internal tailrec fun <Start, End, P, FurtherStart> SplitSeq<Start, End>.splitAtAux(
  p: Prompt<P>, seg: Segment<FurtherStart, Start>
): Pair<Segment<FurtherStart, P>, SplitSeq<P, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, *, *, End> -> rest.splitAtAux(p, FramesSegment(frame, frames, seg))
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    val pair: Pair<Segment<FurtherStart, Start>, SplitSeq<Start, End>> = seg to rest
    pair as Pair<Segment<FurtherStart, P>, SplitSeq<P, End>>
  } else {
    rest.splitAtAux(p, PromptSegment(this.p, seg))
  }

  is ReaderCont<*, Start, End> -> rest.splitAtAux(
    p, ReaderSegment(this.p as Reader<Any?>, state, fork as (Any?.() -> Any?), seg)
  )
}

internal tailrec fun <Start, End, FurtherStart> SplitSeq<Start, End>.splitAtAux(
  p: Reader<*>, seg: Segment<FurtherStart, Start>
): Pair<Segment<FurtherStart, *>, SplitSeq<*, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, *, *, End> -> rest.splitAtAux(p, FramesSegment(frame, frames, seg))
  is PromptCont -> rest.splitAtAux(p, PromptSegment(this.p, seg))
  is ReaderCont<*, Start, End> -> if (p === this.p) {
    seg to rest
  } else rest.splitAtAux(
    p, ReaderSegment(this.p as Reader<Any?>, state, fork as (Any?.() -> Any?), seg)
  )
}

internal tailrec fun <Start, End, P> SplitSeq<Start, End>.find(p: Prompt<P>): SplitSeq<P, End> = when (this) {
  is EmptyCont -> error("Prompt not found $p")

  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> if (p === this.p) rest as SplitSeq<P, End> else rest.find(p)
  is ReaderCont<*, Start, End> -> rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, End>.find(p: Reader<S>): S = when (this) {
  is EmptyCont -> error("Reader not found $p")
  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> rest.find(p)
  is ReaderCont<*, Start, End> -> if (p === this.p) state as S else rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, End>.findOrNull(p: Reader<S>): S? = when (this) {
  is EmptyCont -> null
  is FramesCont<Start, *, *, End> -> rest.findOrNull(p)
  is PromptCont -> rest.findOrNull(p)
  is ReaderCont<*, Start, End> -> if (p === this.p) state as S else rest.findOrNull(p)
}

internal val SplitSeq<*, *>.nonEmpty get() = !isEmpty

internal fun <Start, End, P> SplitSeq<Start, End>.splitAt(p: Prompt<P>): Pair<Segment<Start, P>, SplitSeq<P, End>> =
  splitAtAux(p, emptySegment())

internal fun <Start, End> SplitSeq<Start, End>.splitAt(p: Reader<*>): Pair<Segment<Start, *>, SplitSeq<*, End>> =
  splitAtAux(p, emptySegment())

internal fun <P, End> SplitSeq<P, End>.pushPrompt(p: Prompt<P>): PromptCont<P, End> = PromptCont(p, this)
internal fun <S, P, End> SplitSeq<P, End>.pushReader(p: Reader<S>, value: S, fork: S.() -> S): ReaderCont<S, P, End> =
  ReaderCont(p, value, fork, this)

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : SplitSeq<Start, Nothing> {
  override val isEmpty = true
  override val head get() = error("No head on EmptyCont")
  override val tail get() = error("No tail on EmptyCont")

  override val context: CoroutineContext
    get() = underlying.context

  override fun resumeWith(result: Result<Start>) = underlying.resumeWith(result)
}

internal fun <Start, First, End, FurtherEnd> FrameList<Start, First, End>.asFramesCont(rest: SplitSeq<End, FurtherEnd>): FramesCont<Start, First, End, FurtherEnd> =
  FramesCont(head(), tail(), rest)

// frame :: frames ::: rest
internal data class FramesCont<Start, First, Last, End>(
  val frame: Frame<Start, First>, val frames: FrameList<First, *, Last>, val rest: SplitSeq<Last, End>
) : SplitSeq<Start, End> {
  override val isEmpty get() = false
  override val head: Frame<Start, *> get() = frame
  override val tail: SplitSeq<*, End>
    get() = if (frames.isEmpty()) rest
    else frames.asFramesCont(rest)

  //override fun <FurtherStart> push(f: Frame<FurtherStart, Start>) = FramesCont(f, frame prependTo frames, rest)

  override val context: CoroutineContext = rest.context
}

internal data class PromptCont<Start, End>(
  val p: Prompt<Start>, val rest: SplitSeq<Start, End>
) : SplitSeq<Start, End> by rest {
  override fun resumeWith(result: Result<Start>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else rest.resumeWith(result)
  }
}

internal data class ReaderCont<State, Start, End>(
  val p: Reader<State>, val state: State, val fork: State.() -> State, val rest: SplitSeq<Start, End>
) : SplitSeq<Start, End> by rest {
  override fun resumeWith(result: Result<Start>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else rest.resumeWith(result)
  }
}

// sub continuations / stack segments
// mirrors the stack, and so is in reverse order. allows easy access to the state
// stored in the current prompt
internal sealed interface Segment<in Start, out End>

internal infix fun <Start, End, FurtherEnd> Segment<Start, End>.prependTo(stack: SplitSeq<End, FurtherEnd>): SplitSeq<Start, FurtherEnd> =
  prependTo2(stack) as SplitSeq<Start, FurtherEnd>

private tailrec infix fun <Start, End, FurtherEnd> Segment<Start, End>.prependTo2(stack: SplitSeq<End, FurtherEnd>): SplitSeq<*, *> =
  when (this) {
    is EmptySegment -> stack
    is FramesSegment<Start, *, *, End> -> init.prependTo2(
      FramesCont(
        frame as Frame<Any?, Any?>, frames as FrameList<Any?, Any?, Any?>, stack as SplitSeq<Any?, *>
      )
    )

    is PromptSegment<Start, End> -> init.prependTo2(PromptCont(prompt, stack))
    is ReaderSegment<*, Start, End> -> {
      val f = fork as (Any?.() -> Any?)
      init.prependTo2(ReaderCont(prompt as Reader<Any?>, f(state), f, stack))
    }
  }

internal fun <T> emptySegment(): Segment<T, T> = EmptySegment as Segment<T, T>

internal data object EmptySegment : Segment<Any?, Any?>

internal data class FramesSegment<FurtherStart, Start, First, End>(
  val frame: Frame<Start, First>, val frames: FrameList<First, *, End>, val init: Segment<FurtherStart, Start>
) : Segment<FurtherStart, End>

internal data class PromptSegment<Start, End>(
  val prompt: Prompt<End>, val init: Segment<Start, End>
) : Segment<Start, End>

internal data class ReaderSegment<State, Start, End>(
  val prompt: Reader<State>, val state: State, val fork: (State.() -> State), val init: Segment<Start, End>
) : Segment<Start, End>

internal fun <R> collectStack(continuation: Continuation<R>): SplitSeq<R, *> {
  val list = mutableListOf<Continuation<*>>()
  val last: SplitSeq<*, *> = run {
    continuation.forEach {
      if (it is SplitSeq<*, *>) {
        return@run it
      }
      // instead of copying every continuation, we could also just copy the last one
      list.add(it.copy(EmptyContinuation(it.context)))
    }
    error("No SplitSeq found in stack")
  }
  return FrameList<R, Any?, Any?>(list).asFramesCont(last as SplitSeq<Any?, *>)
}

internal fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*, *> {
  continuation.forEach {
    if (it is SplitSeq<*, *>) {
      return it
    }
  }
  error("No SplitSeq found in stack")
}

private fun <R> collectStack2(continuation: Continuation<R>): SplitSeq<R, *> {
  val list = mutableListOf<Continuation<*>>()
  val last: SplitSeq<*, *> = run {
    continuation.forEach {
      if (it is SplitSeq<*, *>) {
        return@run it
      }
      list.add(it.copy(EmptyContinuation(it.context)))
    }
    error("No SplitSeq found in stack")
  }
  return when {
    list.isEmpty() -> last as SplitSeq<R, *>
    last is FramesCont<*, *, *, *> -> {
      list.add(last.frame.cont)
      list.addAll(last.frames.list)
      FrameList<R, Any?, Any?>(list).asFramesCont(last.rest as SplitSeq<Any?, *>)
    }

    else -> FrameList<R, Any?, Any?>(list).asFramesCont(last as SplitSeq<Any?, *>)
  }
}

private data class EmptyContinuation(override val context: CoroutineContext) : Continuation<Any?> {
  override fun resumeWith(result: Result<Any?>) = error("No continuation to resume with $result")
}