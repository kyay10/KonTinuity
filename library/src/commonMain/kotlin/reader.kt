import kotlin.coroutines.coroutineContext

public suspend fun <T> Reader<T>.ask(): T = (coroutineContext[this] ?: error("Reader $this not set")).state
public suspend fun <T> Reader<T>.askOrNull(): T? = coroutineContext[this]?.state

public suspend fun Reader<*>.isSet(): Boolean = coroutineContext[this] != null

public suspend fun <T, R> runReader(value: T, fork: T.() -> T = { this }, body: suspend Reader<T>.() -> R): R =
  with(Reader<T>()) {
    pushReader(value, fork) { body() }
  }