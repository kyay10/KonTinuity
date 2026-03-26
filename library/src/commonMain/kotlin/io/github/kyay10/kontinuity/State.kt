package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.internal.StateCont
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmInline

@JvmInline
public value class State<S> internal constructor(private val reader: StateCont<*, S>) {
  public var value: S
    get() = reader.value
    set(value) {
      reader.value = value
    }

  public val unsafeValue: S get() = reader.unsafeValue
}

public suspend fun <T, R> runState(value: T, fork: T.() -> T = { this }, body: suspend State<T>.() -> R): R =
  suspendCoroutineHere { stack, stackRest ->
    val reader = StateCont(fork, value, stack, stackRest)
    body.startCoroutineUninterceptedOrReturn(State(reader), reader)
  }

public typealias Reader<S> = State<out S>

@ResetDsl
public suspend fun <T, R> runReader(value: T, fork: T.() -> T = { this }, body: suspend Reader<T>.() -> R): R =
  runState(value, fork, body)

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend fun <S : Stateful<S>, R> runReader(value: S, body: suspend Reader<S>.() -> R): R =
  runReader(value, Stateful<S>::fork, body)