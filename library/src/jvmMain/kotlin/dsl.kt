import androidx.compose.runtime.Composable
import arrow.core.raise.Raise
import arrow.core.raise.recover
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf

context(A) internal fun <A> given(): A = this@A

/** MonadFail-style errors */
public suspend fun <R> reset(
  failValue: R,
  body: @Composable context(Raise<Unit>) Reset<R>.() -> R
): R = coroutineScope { lazyReset(failValue, body).use { it } }

public fun <R> CoroutineScope.lazyReset(
  failValue: R,
  body: @Composable context(Raise<Unit>) Reset<R>.() -> R
): Resource<R> = lazyReset reset@{
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

public fun <R> CoroutineScope.flowReset(
  body: @Composable context(Raise<Unit>) Reset<Flow<R>>.() -> R
): Resource<Flow<R>> = lazyReset(emptyFlow()) {
  flowOf(body(given<Raise<Unit>>(), this@lazyReset))
}

context(Reset<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): T = shift { continuation ->
  flatMapConcat { value -> continuation(value) }
}