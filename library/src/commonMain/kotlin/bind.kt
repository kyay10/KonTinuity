import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.raise.Raise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat

context(Reset<R>, Raise<Unit>)
@Composable
public fun <T, R> shift(block: suspend (Shift<T, R>) -> R): T =
  remember { Shift<T, R>() }.configure(block)

context(Reset<List<R>>, Raise<Unit>)
@Composable
public fun <T, R> List<T>.bind(): T = shift { continuation ->
  flatMap { value -> continuation(value) }
}

context(Reset<Flow<R>>, Raise<Unit>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): T = shift { continuation ->
  flatMapConcat { value -> continuation(value) }
}

context(Reset<R>, Raise<Unit>)
@Composable
public fun <T, R> await(block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}