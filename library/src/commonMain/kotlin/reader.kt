import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

public class ReaderValue<T>(public val value: T, override val key: Reader<T>) : CoroutineContext.Element

public interface Reader<T> : CoroutineContext.Key<ReaderValue<T>>
public fun interface ForkingReader<T> : Reader<T> {
  public fun T.fork(): T
}

public fun <T> Reader(): Reader<T> = object : Reader<T> {}

public suspend fun <T> Reader<T>.ask(): T = (coroutineContext[this] ?: error("Reader $this not set")).value
public suspend fun <T> Reader<T>.askOrNull(): T? = coroutineContext[this]?.value

public suspend fun Reader<*>.isSet(): Boolean = coroutineContext[this] != null

public suspend fun <T, R> runReader(value: T, body: suspend Reader<T>.() -> R): R {
  val reader = Reader<T>()
  return reader.pushReader(value) { reader.body() }
}

public suspend fun <T, R> runForkingReader(value: T, fork: T.() -> T, body: suspend Reader<T>.() -> R): R {
  val reader = ForkingReader<T>(fork)
  return reader.pushReader(value) { reader.body() }
}

public suspend fun <T, R> Reader<T>.pushReader(value: T, body: suspend () -> R): R = pushContext(
  context = context(value),
  rewindHandler = if (this is ForkingReader<T>) RewindHandler {
    it + context(it[this]!!.value.fork())
  } else null,
  body = body
)

public fun <T> Reader<T>.context(value: T): CoroutineContext.Element = ReaderValue<T>(value, this)
