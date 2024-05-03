import androidx.compose.runtime.Composable
import arrow.core.raise.Raise
import arrow.core.raise.recover
import arrow.fx.coroutines.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import Reset.Companion.lazyReset

context(A) internal fun <A> given(): A = this@A

/** MonadFail-style errors */
public suspend fun <R> reset(
  failValue: R,
  body: @Composable context(Raise<Unit>) Reset<R>.() -> R
): R = resourceScope { lazyReset(failValue, body) }

public suspend fun <R> ResourceScope.lazyReset(
  failValue: R,
  body: @Composable context(Raise<Unit>) Reset<R>.() -> R
): R = lazyReset reset@{
  recover({
    runCatchingComposable { body(this, this@reset) }.getOrThrow()
  }) { failValue }
}

public suspend fun <R> listReset(
  body: @Composable context(Raise<Unit>) Reset<List<R>>.() -> R
): List<R> = reset(emptyList()) {
  listOf(body(given<Raise<Unit>>(), this))
}

context(Reset<List<R>>)
@Composable
public fun <T, R> List<T>.bind(): T = shift { continuation ->
  flatMap { value -> continuation(value) }
}

@OptIn(DelicateCoroutinesApi::class)
public suspend fun <R> flowReset(
  body: @Composable context(Raise<Unit>) Reset<Flow<R>>.() -> R
): Flow<R> {
  val (flow, release) = resource {
    lazyReset(emptyFlow()) {
      flowOf(body(given<Raise<Unit>>(), this@lazyReset))
    }
  }.allocated()
  return flow.onCompletion { release(it?.let(ExitCase::ExitCase) ?: ExitCase.Completed) }
}

context(Reset<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): T = shift { continuation ->
  flatMapConcat { value -> continuation(value) }
}