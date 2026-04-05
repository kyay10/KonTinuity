package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.runCatching

private const val HANDLER_ALREADY_RESUMED = "Handler was already resumed, so it cannot be invalidated"
private const val UNEXPECTED_SEGMENT_FOUND = "Handler was connected to an unexpected segment while invalidating: "

private const val SMALL_DATA_BUFFER_SIZE = 6

internal expect val <N> Frames<*, N>.completion: Stack<N>

internal expect fun <T, N> Frames<T, N>.invokeCopied(completion: Stack<N>, result: Result<T>): N

private fun <T, R> Under<T, R>.underflowCopied(): Stack<T> {
  val captured = captured
  return captured.start.also { captured.reattach(false, stack, rest) }
}

internal actual fun <T> Stack<T>.copy(): Stack<T> = Stack(Copied(this))

internal class Copied<Start>(stack: Stack<Start>) : SplitSeq<Start>() {
  override var stack: Stack<Start> = (stack.frames as? Copied)?.stack ?: stack
  override val context get() = stack.frames.context as SplitCont<*>

  override fun resume(result: Result<Start>) = stack.resumeCopied(result, this)

  companion object {
    tailrec fun <T, N> Frames<T, N>.resumeCopied(param: Result<T>, next: Copied<T>) {
      // TODO profile impact of merging `is Prompt` into this when
      // TODO see if upkeeping context using `.also { next.stack = it }` is necessary
      when (frames) {
        is Under<T, *> -> return frames.underflowCopied().resumeCopied(param, next)
        is Finalizer<T, *> -> return frames.underflow().resumeCopied(param, next)
        is Copied -> return frames.stack.resumeCopied(param, next)
      }
      val completion = completion
      if (completion.frames is Prompt) {
        val outcome = runCatching({ invokeCopied(completion, param) }) { return }
        // inlined version of completion.resumeWith(outcome)
        val underflow = completion.frames.underflow().frames
        return if (underflow is Copied) underflow.stack.resumeCopied(outcome, underflow)
        else underflow.resumeWith(outcome)
      }
      @Suppress("UNCHECKED_CAST")
      next as Copied<N>
      // Optimized by only setting it upon suspension.
      // This is safe only if no one accesses next.stack in between
      // That seems to be the case due to trampolining.
      // Note to self: if any weird behavior happens, uncomment this line
      //next.stack = completion
      val outcome = runCatching({ invokeCopied(Stack(next), param) }) {
        next.stack = completion
        return
      }
      completion.resumeCopied(outcome, next)
    }
  }
}

@Suppress("ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS")
private fun <Start, End> Segment<Start, End>.reattach(isFinal: Boolean, stack: Stack<End>, rest: SplitCont<*>) {
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

internal actual fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, rest: SplitCont<*>) =
  start.also { reattach(true, stack, rest) }

internal fun <Start, End> Segment<Start, End>.prependTo(stack: Stack<End>, rest: SplitCont<*>) =
  start.copy().also { reattach(false, stack, rest) }

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
  current.onResume(state, isFinal)
  revalidate(current, values, isFinal, index - 2)
}