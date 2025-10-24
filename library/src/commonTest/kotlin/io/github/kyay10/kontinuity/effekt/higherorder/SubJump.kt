package io.github.kyay10.kontinuity.effekt.higherorder

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.effekt.discard
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use

interface SubJump<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun <T> sub(): Either<AbortiveCont<T, Region>, T>
}

context(_: MultishotScope<Region>)
suspend inline fun <T, R, Region> SubJump<Region>.sub(block: (AbortiveCont<T, Region>) -> R, onJump: (T) -> R): R =
  sub<T>().fold(block, onJump)

typealias AbortiveCont<T, Region> = suspend context(MultishotScope<Region>) (T) -> Nothing

context(_: MultishotScope<Region>)
suspend fun <R, Region> subJump(block: suspend context(NewScope<Region>) SubJump<NewRegion>.() -> R): R = handle {
  block(object : SubJump<HandleRegion> {
    context(_: MultishotScope<HandleRegion>)
    override suspend fun <T> sub(): Either<AbortiveCont<T, HandleRegion>, T> = use { resume ->
      resume(Left {
        discard {
          resume(Right(it))
        }
      })
    }
  })
}