package io.github.kyay10.kontinuity

@PublishedApi
internal actual class Segment<Start, End> actual constructor(
  private val delimiter: Prompt<End>,
  private val start: Stack<Start>,
  startRest: Marker<*>,
) : SplitContOrSegment {
  private var used = false

  init {
    delimiter.rest = this
  }

  actual val trampoline = startRest.trampoline

  actual fun prependToFinal(stack: Stack<End>, stackRest: SplitCont<*>) = start.also {
    if (used) error(SEGMENT_ALREADY_USED)
    used = true
    delimiter.frames = stack
    delimiter.rest = stackRest
  }
}