package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.internal.Frames.Under
import io.github.kyay10.kontinuity.runCatching

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
  captured.prependTo(stack, rest)

internal actual fun <Start> Stack<Start>.copy(rest: Marker<*, *>): Stack<Start> = Stack(Copied(this, rest))

internal class Copied<Start>(stack: Stack<Start>, val rest: Marker<*, *>) : SplitSeq<Start>() {
  private var stack: Stack<Start> = stack.unwrapCopied

  override val context get() = rest
  override val callerFrame: CoroutineStackFrame? get() = stack.frames as? CoroutineStackFrame

  override fun resume(result: Result<Start>) = stack.resumeCopied(result, this, rest)

  companion object {
    val <S> Stack<S>.unwrapCopied get(): Stack<S> = (frames as? Copied)?.stack ?: this

    // Precondition: next.rest === rest
    tailrec fun <Start, Next> Frames<Start, Next>.resumeCopied(
      param: Result<Start>,
      next: Copied<*>,
      rest: Marker<*, *>,
    ) {
      when (frames) {
        is Under<Start, *> -> {
          next.stack = Stack(CompletedContinuation)
          val underflow = frames.underflowCopied()
          val rest = frames.captured.startRest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }

        is Finalizer<Start, *> -> {
          next.stack = Stack(CompletedContinuation)
          val underflow = frames.underflow()
          val rest = frames.rest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }
      }
      val completion = completion?.unwrapCopied ?: error("$NOT_A_COMPILER_CONTINUATION$this")
      if (completion.frames is Prompt) { // completion.frames === rest seems to always hold
        next.stack = Stack(CompletedContinuation)
        val outcome = runCatching({ invokeCopied(completion, completion.frames, param) }) { return }
        // inlined version of completion.resumeWith(outcome)
        val underflow = completion.frames.underflow().frames
        return if (underflow is Copied) underflow.stack.resumeCopied(outcome, underflow, underflow.rest)
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
        next.stack = completion
        return
      }
      completion.resumeCopied(outcome, next, rest)
    }
  }
}

@Suppress("ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS")
private fun <Start, End> Segment<Start, End>.reattach(isFinal: Boolean, stack: Stack<End>, rest: SplitCont<*>) {
  when (val values = values) {
    SEGMENT_USED -> error(SEGMENT_ALREADY_USED)
    null -> if (!isFinal) collectValues()
    else -> startRest.revalidate(values, isFinal, delimiter)
  }
  if (isFinal) values = SEGMENT_USED
  if (delimiter.rest !== this) delimiter.invalidateAndCollectValues()
  delimiter.stack = stack
  delimiter.rest = rest
}

internal actual fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, rest: SplitCont<*>) =
  start.also { reattach(true, stack, rest) }

internal fun <Start, End> Segment<Start, End>.prependTo(stack: Stack<End>, rest: SplitCont<*>) =
  start.also { reattach(false, stack, rest) }

private fun Segment<*, *>.collectValues() {
  values = startRest.invalidate()
}

// TODO don't immediately revalidate if unnecessary
internal fun Marker<*, *>.invalidateAndCollectValues() {
  findSegment { _, _ -> }?.run { if (values == null) collectValues() }
}

private inline fun Marker<*, *>.findSegment(action: (current: Marker<*, *>, rest: Marker<*, *>) -> Unit): Segment<*, *>? {
  var current: Marker<*, *> = this
  while (true) {
    current = when (current) {
      is Finalizer<*, *> -> current.rest.also { action(current, it) }
      is Prompt -> {
        val rest = current.rest.ifSegment { return it }
        rest as? Marker<*, *> ?: error(REENTRANT_NOT_SUPPORTED)
        rest.also { action(current, it) }
      }
    }
  }
}

// TODO control immediate revalidation with a parameter
private fun Marker<*, *>.invalidate(): Array<Any?> {
  var buf = arrayOfNulls<Any?>(SMALL_DATA_BUFFER_SIZE)
  var size = 0
  val _ = findSegment { current, rest ->
    val state = current.suspendAndResume(rest)
    if (buf.size < size + 2) buf = buf.copyOf(buf.size * 2)
    buf[size++] = state
    buf[size++] = rest
  } ?: error(HANDLER_ALREADY_RESUMED)
  return buf
}

@Suppress("UNCHECKED_CAST")
private fun <Start, S> Marker<Start, S>.suspendAndResume(rest: Marker<*, *>) =
  onSuspend().also { onResume(it, rest, isFinal = false) }

private tailrec fun <S> Marker<*, S>.revalidate(
  values: Array<Any?>,
  isFinal: Boolean,
  delimiter: Prompt<*>,
  index: Int = 0
) {
  if (this === delimiter) return
  @Suppress("UNCHECKED_CAST")
  val state = values[index] as S
  val rest = values[index + 1] as Marker<*, *>
  if (this is Prompt && this.rest !== rest) {
    invalidateAndCollectValues()
    this.rest = rest
  }
  onResume(state, rest, isFinal)
  rest.revalidate(values, isFinal, delimiter, index + 2)
}