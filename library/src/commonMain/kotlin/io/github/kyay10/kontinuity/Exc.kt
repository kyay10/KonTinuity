package io.github.kyay10.kontinuity

import arrow.core.None
import arrow.core.Option
import arrow.core.raise.RaiseDSL
import arrow.core.raise.context.Raise
import arrow.core.some
import kotlin.contracts.contract
import kotlin.jvm.JvmName

// Basically a suspending version of Raise<Unit>
public interface Exc : Raise<Unit> {
  @RaiseDSL public suspend fun raise(): Nothing
}

public fun <R> Handler<R>.constantExc(value: R): Exc =
  object : Exc {
    override suspend fun raise(): Nothing = discardWithFast(Result.success(value))

    override fun raise(r: Unit) = discardWith(Result.success(value))
  }

@RaiseDSL
context(exc: Exc)
public suspend fun raise(): Nothing = exc.raise()

@RaiseDSL
context(_: Exc)
public suspend fun ensure(condition: Boolean) {
  contract { returns() implies condition }
  return if (!condition) raise() else Unit
}

@RaiseDSL
context(_: Exc)
public suspend inline fun <T> T.bind(): T & Any {
  contract { returns() implies (this@bind != null) }
  return this ?: raise()
}

@get:JvmName("optionExc")
public val <R> Handler<Option<R>>.exc: Exc
  get() = constantExc(None)

@get:JvmName("listExc")
public val <R> Handler<List<R>>.exc: Exc
  get() = constantExc(emptyList())

@get:JvmName("unitExc")
public val Handler<Unit>.exc: Exc
  get() = constantExc(Unit)

public inline fun <Error, R> Handler<R>.Raise(crossinline transform: (Error) -> R): Raise<Error> =
  object : Raise<Error> {
    override fun raise(r: Error): Nothing = discard { transform(r) }
  }

public suspend fun <R> maybe(block: suspend context(Exc) () -> R): Option<R> = handle { block(exc).some() }
