package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import kotlin.contracts.contract

fun interface Exc<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun raise(msg: String): Nothing
}

context(exc: Exc<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> raise(msg: String): Nothing = exc.raise(msg)

context(exc: Exc<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> raise(): Nothing = raise("")

context(exc: Exc<Region>, _: MultishotScope<Region>)
suspend fun <Region> ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  return if (!condition) raise() else Unit
}

class Maybe<R, in IR, OR>(p: HandlerPrompt<Option<R>, IR, OR>) : Exc<IR>, Handler<Option<R>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun raise(msg: String): Nothing = discard { None }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> maybe(block: suspend context(NewScope<Region>) Exc<NewRegion>.() -> R): Option<R> = handle {
  Some(block(Maybe(this)))
}