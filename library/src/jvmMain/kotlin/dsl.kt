import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

context(A) internal fun <A> given(): A = this@A

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort { failValue }
}

public suspend fun <R> resetWithFail(
  failValue: R, body: suspend context(SingletonRaise<Unit>) Prompt<R>.() -> R
): R = topReset {
  body(SingletonRaise(PromptFail(this, failValue)), this)
}

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>) Prompt<List<R>>.() -> R
): List<R> = resetWithFail(emptyList()) {
  listOf(body(given<SingletonRaise<Unit>>(), this))
}

context(Prompt<List<R>>)
public suspend fun <T, R> List<T>.bind(): T = shift { continuation ->
  flatMap { continuation(it) }
}

public suspend fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>) Prompt<Flow<R>>.() -> R
): Flow<R> = resetWithFail(emptyFlow()) {
  flowOf(body(given<SingletonRaise<Unit>>(), this))
}

context(Prompt<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T, R> Flow<T>.bind(): T = shift(::flatMapConcat)