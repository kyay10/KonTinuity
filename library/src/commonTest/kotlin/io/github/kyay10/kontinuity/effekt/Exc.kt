package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import kotlin.contracts.contract

fun interface Exc {
  suspend fun MultishotScope.raise(msg: String): Nothing
}

context(exc: Exc)
suspend inline fun MultishotScope.raise(msg: String): Nothing = with(exc) { raise(msg) }

context(exc: Exc)
suspend inline fun MultishotScope.raise(): Nothing = raise("")

context(_: Exc) suspend fun MultishotScope.ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  return if (!condition) raise() else Unit
}

class Maybe<R>(p: HandlerPrompt<Option<R>>) : Exc, Handler<Option<R>> by p {
  override suspend fun MultishotScope.raise(msg: String): Nothing = discard { None }
}

suspend fun <R> MultishotScope.maybe(block: suspend context(Exc) MultishotScope.() -> R): Option<R> = handle {
  Some(block(Maybe(given<HandlerPrompt<Option<R>>>()), this))
}