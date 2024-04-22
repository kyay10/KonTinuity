import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.remember
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow

context(ComprehensionScope)
@Composable
public fun <T> bind(block: suspend ProducerScope<T>.() -> Unit): Maybe<T> {
  val state = remember { ComprehensionState<T>() }.also { accessed(it) }
  ComposeNode<_, ComprehensionScope.ComprehensionApplier>(factory = { state }, update = {
    if (state.state.isEmpty) reconcile { configure(block) }
  })
  return state.state
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): Maybe<T> = bind {
  forEach { send(it) }
}

context(ComprehensionScope)
@Composable
public fun <T> Flow<T>.bind(): Maybe<T> = bind {
  collect { send(it) }
}