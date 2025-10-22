package io.github.kyay10.kontinuity.effekt.higherorder

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.discard
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use

interface SubJump {
  context(_: MultishotScope)
  suspend fun <T> sub(): Either<AbortiveCont<T>, T>
}

context(_: MultishotScope)
suspend inline fun <T, R> SubJump.sub(block: (AbortiveCont<T>) -> R, onJump: (T) -> R): R = sub<T>().fold(block, onJump)

typealias AbortiveCont<T> = suspend context(MultishotScope) (T) -> Nothing

context(_: MultishotScope)
suspend fun <R> subJump(block: suspend context(MultishotScope) SubJump.() -> R): R = handle {
  block(object : SubJump {
    context(_: MultishotScope)
    override suspend fun <T> sub(): Either<AbortiveCont<T>, T> = use { resume ->
      resume(Left {
        discard {
          resume(Right(it))
        }
      })
    }
  })
}