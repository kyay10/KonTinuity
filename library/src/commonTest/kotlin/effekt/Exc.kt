package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

interface Exc {
  suspend fun raise(msg: String): Nothing
}

suspend fun Exc.raise(): Nothing = raise("")

class Maybe<R>(p: HandlerPrompt<Option<R>>) : Exc, Handler<Option<R>> by p {
  override suspend fun raise(msg: String): Nothing = discard { None }
}

suspend fun <R> maybe(block: suspend Exc.() -> R): Option<R> = handle {
  Some(block(Maybe(this)))
}