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
  fun head() = Frame<_, First>(list.first() as Continuation<Start>)
  fun tail() = FrameList<First, Nothing, End>(list.tail()) as FrameList<First, *, End>
}

internal sealed interface SplitSeq<in Start, First, out End> : Continuation<Start> {
  val isEmpty: Boolean
  val head: Frame<Start, First>
  val tail: SplitSeq<First, *, End>

  override fun resumeWith(result: Result<Start>) = if (nonEmpty) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else {
      head.resumeWith(result, tail)
    }
  } else error("No continuation to resume with $result")
}

internal tailrec fun <Start, First, End, P, FurtherStart, FurtherFirst> SplitSeq<Start, First, End>.splitAtAux(
  p: Prompt<P>, seg: Segment<FurtherStart, FurtherFirst, Start>
): Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, First, *, End> -> rest.splitAtAux(p, FramesSegment(frames, seg))
  is PromptCont -> if (p === this.p) {
    // Start and P are now unified, but the compiler doesn't get it
    val pair: Pair<Segment<FurtherStart, FurtherFirst, Start>, SplitSeq<Start, *, End>> = seg to rest
    pair as Pair<Segment<FurtherStart, FurtherFirst, P>, SplitSeq<P, *, End>>
  } else {
    rest.splitAtAux(p, PromptSegment(this.p, seg))
  }

  is ReaderCont<*, Start, First, End> -> rest.splitAtAux(
    p, ReaderSegment(this.p as Reader<Any?>, state, fork as (Any?.() -> Any?), seg)
  )
}

internal tailrec fun <Start, First, End, FurtherStart, FurtherFirst> SplitSeq<Start, First, End>.splitAtAux(
  p: Reader<*>, seg: Segment<FurtherStart, FurtherFirst, Start>
): Pair<Segment<FurtherStart, FurtherFirst, *>, SplitSeq<*, *, End>> = when (this) {
  is EmptyCont -> error("Prompt not found $p in $seg")
  is FramesCont<Start, First, *, End> -> rest.splitAtAux(p, FramesSegment(frames, seg))
  is PromptCont -> rest.splitAtAux(p, PromptSegment(this.p, seg))
  is ReaderCont<*, Start, First, End> -> if (p === this.p) {
    seg to rest
  } else rest.splitAtAux(
    p, ReaderSegment(this.p as Reader<Any?>, state, fork as (Any?.() -> Any?), seg)
  )
}

internal tailrec fun <Start, End, P> SplitSeq<Start, *, End>.find(p: Prompt<P>): SplitSeq<P, *, End> = when (this) {
  is EmptyCont -> error("Prompt not found $p")

  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> if (p === this.p) rest as SplitSeq<P, *, End> else rest.find(p)
  is ReaderCont<*, Start, *, End> -> rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.find(p: Reader<S>): S = when (this) {
  is EmptyCont -> error("Reader not found $p")
  is FramesCont<Start, *, *, End> -> rest.find(p)
  is PromptCont -> rest.find(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) state as S else rest.find(p)
}

internal tailrec fun <Start, End, S> SplitSeq<Start, *, End>.findOrNull(p: Reader<S>): S? = when (this) {
  is EmptyCont -> null
  is FramesCont<Start, *, *, End> -> rest.findOrNull(p)
  is PromptCont -> rest.findOrNull(p)
  is ReaderCont<*, Start, *, End> -> if (p === this.p) state as S else rest.findOrNull(p)
}

internal val SplitSeq<*, *, *>.nonEmpty get() = !isEmpty

internal fun <Start, First, End, P> SplitSeq<Start, First, End>.splitAt(p: Prompt<P>): Pair<Segment<Start, First, P>, SplitSeq<P, *, End>> =
  splitAtAux(p, emptySegment())

internal fun <Start, First, End> SplitSeq<Start, First, End>.splitAt(p: Reader<*>): Pair<Segment<Start, First, *>, SplitSeq<*, *, End>> =
  splitAtAux(p, emptySegment())

internal fun <P, First, End> SplitSeq<P, First, End>.pushPrompt(p: Prompt<P>): PromptCont<P, First, End> = PromptCont(p, this)
internal fun <S, P, First, End> SplitSeq<P, First, End>.pushReader(p: Reader<S>, value: S, fork: S.() -> S): ReaderCont<S, P, First, End> =
  ReaderCont(p, value, fork, this)

internal data class EmptyCont<Start>(val underlying: Continuation<Start>) : SplitSeq<Start, Nothing, Nothing> {
  override val isEmpty = true
  override val head get() = error("No head on EmptyCont")
  override val tail get() = error("No tail on EmptyCont")

  override val context: CoroutineContext
    get() = underlying.context

  override fun resumeWith(result: Result<Start>) = underlying.resumeWith(result)
}

internal fun <Start, First, End, FurtherEnd> FrameList<Start, First, End>.asFramesCont(rest: SplitSeq<End, *, FurtherEnd>): SplitSeq<Start, First, FurtherEnd> =
  if(isEmpty()) rest as SplitSeq<Start, First, FurtherEnd> else FramesCont(this, rest)

// frame :: frames ::: rest
internal data class FramesCont<Start, First, Last, End>(
  val frames: FrameList<Start, First, Last>, val rest: SplitSeq<Last, *, End>
) : SplitSeq<Start, First, End> {
  override val isEmpty get() = false
  override val head get() = frames.head()
  override val tail get() = frames.tail().asFramesCont(rest)

  //override fun <FurtherStart> push(f: Frame<FurtherStart, Start>) = FramesCont(f, frame prependTo frames, rest)

  override val context: CoroutineContext = rest.context
}

internal data class PromptCont<Start, First, End>(
  val p: Prompt<Start>, val rest: SplitSeq<Start, First, End>
) : SplitSeq<Start, First, End> {
  override val isEmpty get() = rest.isEmpty
  override val head get() = rest.head
  override val tail get() = rest.tail
  override val context get() = rest.context

  override fun resumeWith(result: Result<Start>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else rest.resumeWith(result)
  }
}

internal data class ReaderCont<State, Start, First, End>(
  val p: Reader<State>, val state: State, val fork: State.() -> State, val rest: SplitSeq<Start, First, End>
) : SplitSeq<Start, First, End> {
  override val isEmpty get() = rest.isEmpty
  override val head get() = rest.head
  override val tail get() = rest.tail
  override val context get() = rest.context

  override fun resumeWith(result: Result<Start>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingStackException) exception.use(this)
    else rest.resumeWith(result)
  }
}

// sub continuations / stack segments
// mirrors the stack, and so is in reverse order. allows easy access to the state
// stored in the current prompt
internal sealed interface Segment<in Start, First, out End>

internal infix fun <Start, First, End, FurtherEnd> Segment<Start, First, End>.prependTo(stack: SplitSeq<End, *, FurtherEnd>): SplitSeq<Start, First, FurtherEnd> =
  prependTo2(stack) as SplitSeq<Start, First, FurtherEnd>

private tailrec infix fun <Start, First, End, FurtherEnd> Segment<Start, First, End>.prependTo2(stack: SplitSeq<End, *, FurtherEnd>): SplitSeq<*, *, *> =
  when (this) {
    is EmptySegment -> stack
    is FramesSegment<Start, First, *, *, End> -> init.prependTo2(
      FramesCont(
        frames as FrameList<Any?, Any?, Any?>, stack as SplitSeq<Any?, *, *>
      )
    )

    is PromptSegment<Start, First, End> -> init.prependTo2(PromptCont(prompt, stack))
    is ReaderSegment<*, Start, First, End> -> {
      val f = fork as (Any?.() -> Any?)
      init.prependTo2(ReaderCont(prompt as Reader<Any?>, f(state), f, stack))
    }
  }

internal fun <T, First> emptySegment(): Segment<T, First, T> = EmptySegment as Segment<T, First, T>

internal data object EmptySegment : Segment<Any?, Nothing, Any?>

internal data class FramesSegment<FurtherStart, FurtherFirst, Start, First, End>(
  val frames: FrameList<Start, First, End>, val init: Segment<FurtherStart, FurtherFirst, Start>
) : Segment<FurtherStart, FurtherFirst, End>

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
      // instead of copying every continuation, we could also just copy the last one
      list.add(it.copy(EmptyContinuation(it.context)))
    }
    error("No SplitSeq found in stack")
  }
  return FrameList<R, Any?, Any?>(list).asFramesCont(last as SplitSeq<Any?, *, *>)
}

internal fun findNearestSplitSeq(continuation: Continuation<*>): SplitSeq<*, *, *> {
  continuation.forEach {
    if (it is SplitSeq<*, *, *>) {
      return it
    }
  }
  error("No SplitSeq found in stack")
}

private fun <R> collectStack2(continuation: Continuation<R>): SplitSeq<R, *, *> {
  val list = mutableListOf<Continuation<*>>()
  val last: SplitSeq<*, *, *> = run {
    continuation.forEach {
      if (it is SplitSeq<*, *, *>) {
        return@run it
      }
      list.add(it.copy(EmptyContinuation(it.context)))
    }
    error("No SplitSeq found in stack")
  }
  return when {
    list.isEmpty() -> last as SplitSeq<R, *, *>
    last is FramesCont<*, *, *, *> -> {
      list.addAll(last.frames.list)
      FrameList<R, Any?, Any?>(list).asFramesCont(last.rest as SplitSeq<Any?, *, *>)
    }

    else -> FrameList<R, Any?, Any?>(list).asFramesCont(last as SplitSeq<Any?, *, *>)
  }
}

private data class EmptyContinuation(override val context: CoroutineContext) : Continuation<Any?> {
  override fun resumeWith(result: Result<Any?>) = error("No continuation to resume with $result")
}