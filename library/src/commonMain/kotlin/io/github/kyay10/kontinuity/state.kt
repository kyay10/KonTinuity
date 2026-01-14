package io.github.kyay10.kontinuity

import kotlin.jvm.JvmInline

public data class StateValue<T>(public var value: T)

@JvmInline
public value class State<T>(private val underlying: Reader<StateValue<T>>) {
  public var value: T
    get() = underlying.value.value
    set(value) {
      underlying.value.value = value
    }
}

public inline fun <T> State<T>.modify(f: (T) -> T) {
  value = f(value)
}

public suspend inline fun <T, R> runState(value: T, crossinline body: suspend State<T>.() -> R): R =
  runReader(StateValue(value), { copy() }) { body(State(this)) }