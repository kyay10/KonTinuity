import arrow.atomic.Atomic
import arrow.atomic.update
import arrow.atomic.value

public typealias StackState<T> = State<List<T>>

public suspend fun <T> StackState<T>.set(value: T) = ask().update { it + value }
public suspend fun <T> StackState<T>.get() = ask().value.last()

public suspend inline fun <T> StackState<T>.modify(f: (T) -> T) = ask().update { it + f(it.last()) }

public suspend fun <T, R> runStackState(value: T, body: suspend StackState<T>.() -> R): R {
  val state = Reader<Atomic<List<T>>>()
  return state.pushStackState(value) { state.body() }
}

public suspend fun <T, R> StackState<T>.pushStackState(value: T, body: suspend () -> R): R =
  pushState(listOf(value), body)

public suspend fun <T, R> StackState<T>.pushStackStateWithCurrent(value: T, body: suspend () -> R): R {
  val current = askOrNull()?.value ?: emptyList()
  return pushState(current + value, body)
}