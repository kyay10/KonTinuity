package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.internal.Copied.Companion.unwrapCopied
import io.github.kyay10.kontinuity.runCatching

private const val NOT_A_COMPILER_CONTINUATION = "Not a compiler generated continuation "
private const val HANDLER_ALREADY_RESUMED = "Handler was already resumed, so it cannot be invalidated"
private const val UNEXPECTED_SEGMENT_FOUND = "Handler was connected to an unexpected segment while invalidating: "

private const val SMALL_DATA_BUFFER_SIZE = 6

internal expect val <N> Frames<*, N>.completion: Stack<N>?

internal expect fun <T, N> Frames<T, N>.invokeCopied(completion: Stack<N>, context: SplitCont<*>, result: Result<T>): N

private fun <T, R> Under<T, R>.underflowCopied(): Stack<T> {
  val captured = captured
  return captured.start.also { captured.reattach(false, stack, context) }
}

internal actual fun <T> Stack<T>.copy(rest: Marker<*, *>): Stack<T> = Stack(Copied(unwrapCopied, rest))

internal class Copied<T>(override var stack: Stack<T>, override val context: Marker<*, *>) : SplitSeq<T>() {

  override fun resume(result: Result<T>) = stack.resumeCopied(result, this, context)

  companion object {
    val <S> Stack<S>.unwrapCopied get(): Stack<S> = (frames as? Copied)?.stack ?: this

    tailrec fun <T, N> Frames<T, N>.resumeCopied(param: Result<T>, next: Copied<T>, rest: Marker<*, *>) {
      when (frames) {
        is Under<T, *> -> {
          val underflow = frames.underflowCopied().unwrapCopied
          val rest = frames.captured.startRest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }

        is Finalizer<T, *> -> {
          val underflow = frames.underflow().unwrapCopied
          val rest = frames.rest
          return underflow.resumeCopied(param, Copied(underflow, rest), rest)
        }
      }
      val completion = completion?.unwrapCopied ?: error("$NOT_A_COMPILER_CONTINUATION$this")
      if (completion.frames is Prompt) { // completion.frames === rest seems to always hold
        val outcome = runCatching({ invokeCopied(completion, completion.frames, param) }) { return }
        // inlined version of completion.resumeWith(outcome)
        val underflow = completion.frames.underflow().frames
        return if (underflow is Copied) underflow.stack.resumeCopied(outcome, underflow, underflow.context)
        else underflow.resumeWith(outcome)
      }
      @Suppress("UNCHECKED_CAST")
      next as Copied<N>
      // Optimized by only setting it upon suspension.
      // This is safe only if no one accesses next.stack in between
      // That seems to be the case due to trampolining.
      // Note to self: if any weird behavior happens, uncomment this line
      //next.stack = completion
      val outcome = runCatching({ invokeCopied(Stack(next), rest, param) }) {
        next.stack = completion
        return
      }
      completion.resumeCopied(outcome, next, rest)
    }
  }
}

@Suppress("ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS")
private fun <T, R> Segment<T, R>.reattach(isFinal: Boolean, stack: Stack<R>, rest: SplitCont<*>) {
  when (values) {
    SEGMENT_USED -> error(SEGMENT_ALREADY_USED)
    null if !isFinal -> values = collectValues(startRest, delimiter)
  }
  values?.let { revalidate(delimiter, it, isFinal, it.size) }
  if (isFinal) values = SEGMENT_USED
  if (delimiter.rest !== this) delimiter.invalidateAndCollectValues()
  delimiter.stack = stack
  delimiter.rest = rest
}

internal actual fun <T, R> Segment<T, R>.prependToFinal(stack: Stack<R>, rest: SplitCont<*>) =
  start.also { reattach(true, stack, rest) }

internal fun <T, R> Segment<T, R>.prependTo(stack: Stack<R>, rest: SplitCont<*>) =
  start.copy(startRest).also { reattach(false, stack, rest) }

private fun collectValues(from: Marker<*, *>, until: Prompt<*>): Array<Any?> {
  var values = arrayOfNulls<Any?>(SMALL_DATA_BUFFER_SIZE)
  var size = 0
  val segment = from.findSegment {
    if (it === until) return values.copyOf(size)
    if (values.size < size + 2) values = values.copyOf(values.size * 2)
    values[size++] = it
    values[size++] = it.onSuspend()
  } ?: error(HANDLER_ALREADY_RESUMED)
  error("$UNEXPECTED_SEGMENT_FOUND$segment")
}

internal fun Marker<*, *>.invalidateAndCollectValues() {
  findSegment { }?.run { if (values == null) values = collectValues(startRest, delimiter) }
}

private inline fun SplitContOrSegment?.findSegment(action: (Marker<*, *>) -> Unit): Segment<*, *>? {
  var current = this
  while (current is Marker<*, *>) current = current.also(action).rest
  return when (current) {
    is Segment<*, *>? -> current
    is EmptyCont<*> -> error(REENTRANT_NOT_SUPPORTED)
  }
}

@Suppress("UNCHECKED_CAST")
private tailrec fun revalidate(rest: Marker<*, *>, values: Array<Any?>, isFinal: Boolean, index: Int) {
  if (index < 2) return
  val state = values[index - 1]
  val current = values[index - 2] as Marker<*, Any?>
  if (current is Prompt && current.rest !== rest) {
    current.invalidateAndCollectValues()
    current.rest = rest
  }
  current.onResume(state, rest, isFinal)
  revalidate(current, values, isFinal, index - 2)
}