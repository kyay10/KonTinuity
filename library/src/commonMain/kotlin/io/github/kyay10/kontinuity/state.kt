package io.github.kyay10.kontinuity

import kotlin.jvm.JvmInline

internal data class StateValue<T>(var value: T) : Stateful<StateValue<T>> {
  override fun fork(): StateValue<T> = copy()
}

@JvmInline
public value class State<T> internal constructor(private val underlying: Reader<StateValue<T>>) {
  public var value: T
    get() = underlying.unsafeValue.value
    set(value) {
      underlying.value.value = value
    }
}

public inline fun <T> State<T>.modify(f: (T) -> T) {
  value = f(value)
}

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R =
  runReader(StateValue(value)) { body(State(this)) }