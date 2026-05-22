package io.github.kyay10.kontinuity.internal
internal actual class MultishotProof(val nothing: Nothing)

context(proof: MultishotProof)
internal actual fun <T> Stack<T>.copy(rest: Marker<*, *>): Stack<T> = proof.nothing

internal actual fun <T, R> Segment<T, R>.prependToFinal(stack: Stack<R>, rest: SplitCont<*>) = start.also {
  if (values === SEGMENT_USED) error(SEGMENT_ALREADY_USED)
  values = SEGMENT_USED
  // TODO if (delimiter.rest !== this)
  delimiter.stack = stack
  delimiter.rest = rest
}
