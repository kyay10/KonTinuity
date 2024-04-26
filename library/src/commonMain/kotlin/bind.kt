import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat

@NonRestartableComposable
@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T =
  remember { Shift<T, R>(this) }.configure(block)

// TODO: investigate if we can reuse Recomposer and GatedFrameClock here
@Composable
public fun <T, R> Reset<R>.reset(
  body: @Composable Reset<T>.() -> T
): T = await { resetAliased(body) }

private suspend fun <R> resetAliased(
  body: @Composable Reset<R>.() -> R
): R = reset(body)

context(Reset<List<R>>)
@Composable
public fun <T, R> List<T>.bind(): T = shift { continuation ->
  flatMap { value -> continuation(value) }
}

context(Reset<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): T = shift { continuation ->
  flatMapConcat { value -> continuation(value) }
}

context(Reset<R>)
@Composable
public inline fun <T, R> await(crossinline block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}