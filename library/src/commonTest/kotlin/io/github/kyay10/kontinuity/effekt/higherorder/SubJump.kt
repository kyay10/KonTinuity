package io.github.kyay10.kontinuity.effekt.higherorder

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.discard
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use

interface SubJump {
  suspend fun <T> MultishotScope.sub(): Either<AbortiveCont<T>, T>
}

context(sj: SubJump)
suspend inline fun <T, R> MultishotScope.sub(block: (AbortiveCont<T>) -> R, onJump: (T) -> R): R =
  with(sj) { sub<T>().fold(block, onJump) }

typealias AbortiveCont<T> = suspend MultishotScope.(T) -> Nothing

suspend fun <R> MultishotScope.subJump(block: suspend context(SubJump) MultishotScope.() -> R): R = handle {
  block(object : SubJump {
    override suspend fun <T> MultishotScope.sub(): Either<AbortiveCont<T>, T> = use { resume ->
      resume(Left {
        discard {
          resume(Right(it))
        }
      })
    }
  }, this)
}