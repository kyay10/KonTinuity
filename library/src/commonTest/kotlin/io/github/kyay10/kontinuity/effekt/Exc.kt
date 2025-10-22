package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import kotlin.contracts.contract

fun interface Exc {
  context(_: MultishotScope)
  suspend fun raise(msg: String): Nothing
}

context(exc: Exc, _: MultishotScope)
suspend inline fun raise(msg: String): Nothing = exc.raise(msg)

context(exc: Exc, _: MultishotScope)
suspend inline fun raise(): Nothing = raise("")

context(_: Exc, _: MultishotScope)
suspend fun ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  return if (!condition) raise() else Unit
}

class Maybe<R>(p: HandlerPrompt<Option<R>>) : Exc, Handler<Option<R>> by p {
  context(_: MultishotScope)
  override suspend fun raise(msg: String): Nothing = discard { None }
}

context(_: MultishotScope)
suspend fun <R> maybe(block: suspend context(MultishotScope) Exc.() -> R): Option<R> = handle {
  Some(block(Maybe(this)))
}