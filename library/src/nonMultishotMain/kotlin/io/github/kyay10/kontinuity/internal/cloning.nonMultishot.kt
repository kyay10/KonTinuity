package io.github.kyay10.kontinuity.internal

internal actual fun <Start> Stack<Start>.copy(rest: Marker<*, *>): Stack<Start> {
  error("Cloning is not supported")
}

internal actual fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, rest: SplitCont<*>) =
  start.also {
    if (values === SEGMENT_USED) error(SEGMENT_ALREADY_USED)
    values = SEGMENT_USED
    // TODO if (delimiter.rest !== this)
    delimiter.stack = stack
    delimiter.rest = rest
  }