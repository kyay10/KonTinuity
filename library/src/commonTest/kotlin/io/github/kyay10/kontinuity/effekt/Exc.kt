package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import kotlin.contracts.contract

fun interface Exc {
  suspend fun raise(msg: String): Nothing
}

context(exc: Exc)
suspend fun raise(msg: String): Nothing = exc.raise(msg)

context(exc: Exc)
suspend fun raise(): Nothing = raise("")

context(_: Exc) suspend fun ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  if (!condition) raise()
}

class Maybe<R>(p: HandlerPrompt<Option<R>>) : Exc, Handler<Option<R>> by p {
  override suspend fun raise(msg: String): Nothing = discard { None }
}

suspend fun <R> maybe(block: suspend Exc.() -> R): Option<R> = handle {
  Some(block(Maybe(this)))
}