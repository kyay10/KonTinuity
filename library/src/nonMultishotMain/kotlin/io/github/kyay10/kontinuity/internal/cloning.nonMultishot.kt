package io.github.kyay10.kontinuity.internal

internal actual fun <Start, End> Segment<Start, End>.prependToFinal(stack: Stack<End>, stackRest: SplitCont<*>) =
  start.also {
    if (values === SEGMENT_USED) error(SEGMENT_ALREADY_USED)
    values = SEGMENT_USED
    // TODO if (delimiter.rest !== this)
    delimiter.frames = stack
    delimiter.rest = stackRest
  }