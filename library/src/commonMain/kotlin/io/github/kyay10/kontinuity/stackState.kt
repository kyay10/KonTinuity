package io.github.kyay10.kontinuity

public typealias StackState<T> = State<List<T>>

context(_: StackState<T>)
public fun <T> getLast(): T = ask().value.last()

context(s: StackState<T>)
public inline fun <T> modifyLast(f: (T) -> T): Unit = modify { it + f(getLast()) }

public suspend fun <T, R> MultishotScope.runStackState(value: T, body: suspend context(StackState<T>) MultishotScope.() -> R): R =
  runState(listOf(value), body)