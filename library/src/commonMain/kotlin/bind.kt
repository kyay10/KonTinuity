import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat

context(Reset<R>)
@Composable
public fun <T, R> shift(block: suspend (Shift<T, R>) -> R): Maybe<T> {
  val coroutineScope = rememberCoroutineScope()
  val state = remember { Shift<T, R>() }.apply {
    if (state.isEmpty) shouldUpdateEffects = true
    if (shouldUpdateEffects) coroutineScope.configure { block(this) }
    if (this === currentShift) shouldUpdateEffects = true
  }
  return state.state
}

context(Reset<List<R>>)
@Composable
public fun <T, R> List<T>.bind(): Maybe<T> = shift { continuation ->
  flatMap { value -> continuation(value) }
}

context(Reset<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): Maybe<T> = shift { continuation ->
  flatMapConcat { value -> continuation(value) }
}

context(Reset<R>)
@Composable
public fun <T, R> await(block: suspend () -> T): Maybe<T> = shift { continuation ->
  continuation(block())
}