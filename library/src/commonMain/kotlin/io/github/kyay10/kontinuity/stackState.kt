package io.github.kyay10.kontinuity

public typealias StackState<T> = State<List<T>>

public suspend operator fun <T> StackState<T>.plusAssign(value: T) = modify { it + value }
public suspend fun <T> StackState<T>.getLast() = ask().value.last()

public suspend inline fun <T> StackState<T>.modifyLast(f: (T) -> T) = plusAssign(f(getLast()))

public suspend fun <T, R> runStackState(value: T, body: suspend StackState<T>.() -> R): R =
  runState(listOf(value), body)