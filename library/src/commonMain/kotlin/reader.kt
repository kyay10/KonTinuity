import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

public class ReaderValue<T>(public val value: T, override val key: Reader<T>) : CoroutineContext.Element

public interface Reader<T> : CoroutineContext.Key<ReaderValue<T>>

public fun <T> Reader(): Reader<T> = object : Reader<T> {}

public suspend fun <T> Reader<T>.get(): T {
  val readerValue = coroutineContext[this] ?: error("Reader $this not set")
  return readerValue.value
}

public suspend fun Reader<*>.isSet(): Boolean = coroutineContext[this] != null

public suspend fun <T, R> runReader(value: T, body: suspend Reader<T>.() -> R): R {
  val reader = Reader<T>()
  return reader.pushReader(value) { reader.body() }
}

public suspend fun <T, R> Reader<T>.pushReader(value: T, body: suspend () -> R): R = pushContext(context(value), body)

public suspend fun <T> Reader<T>.context(value: T): CoroutineContext.Element = ReaderValue<T>(value, this)
