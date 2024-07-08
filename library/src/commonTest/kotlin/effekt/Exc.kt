package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

interface Exc {
  suspend fun raise(msg: String): Nothing
}

suspend fun Exc.raise(): Nothing = raise("")

fun interface Maybe<R> : Exc, Handler<Option<R>> {
  override suspend fun raise(msg: String): Nothing = discard { None }
}

suspend fun <R> maybe(block: suspend Exc.() -> R): Option<R> = handle(::Maybe) {
  Some(block())
}