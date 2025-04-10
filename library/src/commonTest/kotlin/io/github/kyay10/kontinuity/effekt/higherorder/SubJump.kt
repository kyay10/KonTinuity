package io.github.kyay10.kontinuity.effekt.higherorder

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import io.github.kyay10.kontinuity.effekt.discard
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use

interface SubJump {
  suspend fun <T> sub(): Either<AbortiveCont<T>, T>
}


suspend inline fun <T, R> SubJump.sub(block: (AbortiveCont<T>) -> R, onJump: (T) -> R): R = sub<T>().fold(block, onJump)

typealias AbortiveCont<T> = suspend (T) -> Nothing

suspend fun <R> subJump(block: suspend SubJump.() -> R): R = handle {
  block(object : SubJump {
    override suspend fun <T> sub(): Either<AbortiveCont<T>, T> = use { resume ->
      resume(Left {
        discard {
          resume(Right(it))
        }
      })
    }
  })
}