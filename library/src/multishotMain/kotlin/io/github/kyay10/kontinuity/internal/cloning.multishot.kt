package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.internal.Frames.Under
import io.github.kyay10.kontinuity.internal.StateCont.ForkOnFirstRead
import io.github.kyay10.kontinuity.runCatching
import kotlin.coroutines.Continuation

private const val MODIFIED_CONCURRENTLY = " was likely modified concurrently: found "
private const val NOT_A_COMPILER_CONTINUATION = "Not a compiler generated continuation "
private const val HANDLER_ALREADY_RESUMED = "Handler was already resumed, so it cannot be invalidated"

private const val SMALL_DATA_BUFFER_SIZE = 6

internal expect val <N> Frames<*, N>.completion: Stack<N>?

internal expect fun <S, N> Frames<S, N>.invokeCopied(
  completion: Stack<N>,
  context: SplitCont<*>,
  result: Result<S>
): N

private fun <Start, End> Under<Start, End>.underflowCopied() =
  captured.prependTo(frames, rest)

internal fun <Start> Stack<Start>.copy(rest: SplitCont<*>): Stack<Start> = Stack(Copied(this, rest))

internal class Copied<Start>(frames: Stack<Start>, val rest: SplitCont<*>) : SplitSeq<Start>() {
  private var frames: Stack<Start> = frames.unwrapCopied

  override val context get() = rest
  override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame

  override fun resume(result: Result<Start>) = frames.resumeCopied(result, this, rest)

  companion object {
    val <S> Stack<S>.unwrapCopied get(): Stack<S> = (frames as? Copied)?.frames ?: this

    tailrec fun <Start, Next> Frames<Start, Next>.resumeCopied(
      param: Result<Start>,
      next: Copied<*>,
      rest: SplitCont<*>
    ) {
      when (frames) {
        is Under<Start, *> -> {
          next.frames = Stack(CompletedContinuation)
          val underflow = frames.underflowCopied()
          val rest = frames.captured.startRest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }

        is StateCont<Start, *> -> {
          next.frames = Stack(CompletedContinuation)
          val underflow = frames.underflow()
          val rest = frames.rest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }
      }
      val completion = completion?.unwrapCopied ?: error("$NOT_A_COMPILER_CONTINUATION$this")
      if (completion.frames is PromptCont) { // completion.frames === rest seems to always hold
        next.frames = Stack(CompletedContinuation)
        val outcome = runCatching({ invokeCopied(completion, completion.frames, param) }) { return }
        // inlined version of completion.resumeWith(outcome)
        val underflow = completion.frames.underflow().frames
        return if (underflow is Copied) underflow.frames.resumeCopied(outcome, underflow, underflow.rest)
        else underflow.resumeWith(outcome)
      }
      @Suppress("UNCHECKED_CAST")
      next as Copied<Next>
      // Optimized by only setting it upon suspension.
      // This is safe only if no one accesses cont in between
      // That seems to be the case due to trampolining.
      // Note to self: if any weird behavior happens, uncomment this line
      //next.frames = completion
      val outcome = runCatching({ invokeCopied(Stack(next), rest, param) }) {
        next.frames = completion
        return
      }
      completion.resumeCopied(outcome, next, rest)
    }
  }
}

@Suppress("ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS")
private fun <Start, End> Segment<Start, End>.reattach(isFinal: Boolean, stack: Stack<End>, stackRest: SplitCont<*>) {
  when (val values = values) {
    SEGMENT_USED -> error(SEGMENT_ALREADY_USED)
    null -> if (!isFinal) collectValues()
    else -> startRest.revalidate(values, isFinal, delimiter)
  }
  if (isFinal) values = SEGMENT_USED
  if (delimiter.rest !== this) delimiter.invalidateAndCollectValues()
  delimiter.frames = stack
  delimiter.rest = stackRest
}

internal actual fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, stackRest: SplitCont<*>) =
  start.also { reattach(true, stack, stackRest) }

internal fun <Start, End> Segment<Start, End>.prependTo(stack: Stack<End>, stackRest: SplitCont<*>) =
  start.also { reattach(false, stack, stackRest) }

private fun Segment<*, *>.collectValues() {
  values = startRest.invalidate()
}

// TODO don't immediately revalidate if unnecessary
internal fun Marker<*>.invalidateAndCollectValues() {
  findSegment()?.run { if (values == null) collectValues() }
}

private inline fun Marker<*>.findSegment(
  onReader: (current: StateCont<*, *>) -> Unit = {},
  onPrompt: (current: PromptCont<*>, rest: SplitCont<*>) -> Unit = { _, _ -> },
): Segment<*, *>? {
  var current: Marker<*> = this
  while (true) {
    current = when (current) {
      is StateCont<*, *> -> current.rest.also { onReader(current) }
      is PromptCont -> current.rest.ifSegment { return it }.also { onPrompt(current, it) }
    }.errorIfEmptyCont()
  }
}

// TODO control immediate revalidation with a parameter
private fun Marker<*>.invalidate(): Array<Any?> {
  var buf = arrayOfNulls<Any?>(SMALL_DATA_BUFFER_SIZE)
  var size = 0
  val _ = findSegment(onReader = {
    val state = it.invalidateState()
    if (buf.size < size + 1) buf = buf.copyOf(buf.size * 2)
    buf[size++] = state
  }) { prompt, rest ->
    val frames = prompt.invalidateFrames(rest).frames
    if (buf.size < size + 2) buf = buf.copyOf(buf.size * 2)
    buf[size++] = frames
    buf[size++] = rest
  } ?: error(HANDLER_ALREADY_RESUMED)
  return buf
}

private fun <Start> PromptCont<Start>.invalidateFrames(rest: SplitCont<*>) = frames.also { frames = it.copy(rest) }

@Suppress("UNCHECKED_CAST")
private fun <Start, S> StateCont<Start, S>.invalidateState() = state.also { state = ForkOnFirstRead(it) as S }

private tailrec fun Marker<*>.revalidate(
  values: Array<Any?>,
  isFinal: Boolean,
  delimiter: PromptCont<*>,
  index: Int = 0
) {
  if (this === delimiter) return
  val newIndex: Int
  when (this) {
    is PromptCont -> {
      newIndex = index + 2
      revalidateSingle(values, isFinal, index)
      rest.ifSegment { error("$this$MODIFIED_CONCURRENTLY$it") }
    }

    is StateCont<*, *> -> {
      newIndex = index + 1
      revalidateSingle(values, isFinal, index)
      rest
    }
  }.errorIfEmptyCont().revalidate(values, isFinal, delimiter, newIndex)
}

@Suppress("UNCHECKED_CAST")
private fun <Start> PromptCont<Start>.revalidateSingle(arr: Array<out Any?>, isFinal: Boolean, index: Int) {
  val frames = Stack(arr[index] as Continuation<Start>)
  val rest = arr[index + 1] as Marker<*>
  if (this.rest !== rest) {
    invalidateAndCollectValues()
    this.rest = rest
  }
  this.frames = if (isFinal) frames else frames.copy(rest)
}

@Suppress("UNCHECKED_CAST")
private fun <Start, S> StateCont<Start, S>.revalidateSingle(arr: Array<out Any?>, isFinal: Boolean, index: Int) {
  val state = arr[index] as S
  this.state = if (isFinal) state else ForkOnFirstRead(state) as S
}