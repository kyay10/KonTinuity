import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat

context(Reset<R>)
@Composable
public fun <T, R> shift(block: suspend (Shift<T, R>) -> R): Maybe<T> {
  val state = remember { Shift<T, R>() }.apply {
    if (state.isEmpty || shouldUpdateEffects) configure { block(this) }
    if (this == currentShift || state.isEmpty) shouldUpdateEffects = true
  }
  return state.state
}

context(Reset<List<R>>)
@Composable
public fun <T, R> List<T>.bind(): Maybe<T> = shift { continuation ->
  flatMap { value ->
    println("gonna shift $value")
    continuation(value).also { println("received $it")}
  }
}

context(Reset<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): Maybe<T> = shift { continuation ->
  flatMapConcat { value ->
    println("gonna shift $value")
    continuation(value).also { println("received $it")}
  }
}