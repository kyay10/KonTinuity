package io.github.kyay10.kontinuity.higherorder

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import io.github.kyay10.kontinuity.discard
import io.github.kyay10.kontinuity.handle
import io.github.kyay10.kontinuity.use

interface SubJump {
  suspend fun <T> sub(): Either<AbortiveCont<T>, T>
}

context(sj: SubJump)
suspend inline fun <T, R> sub(block: (AbortiveCont<T>) -> R, onJump: (T) -> R): R = sj.sub<T>().fold(block, onJump)

typealias AbortiveCont<T> = suspend (T) -> Nothing

suspend fun <R> subJump(block: suspend context(SubJump) () -> R): R = handle {
  block(
    object : SubJump {
      override suspend fun <T> sub(): Either<AbortiveCont<T>, T> = use { resume ->
        resume(Left { discard { resume(Right(it)) } })
      }
    }
  )
}
