import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.OptionRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.jvm.JvmInline

@DslMarker
public annotation class ComprehensionDsl

@ComprehensionDsl
public sealed interface ComprehensionScope {
  public fun readAll()
  public fun <T> ComprehensionState<T>.register(producer: suspend ProducerScope<T>.() -> Unit): OptionalState<T>
}

public class ComprehensionState<T> : RememberObserver {
  private lateinit var channel: ReceiveChannel<T>

  private val _state = mutableStateOf<Any?>(EmptyValue, neverEqualPolicy())
  internal val state get() = OptionalState<T>(_state)

  internal suspend fun advance(): Boolean {
    val value = channel.receiveCatching()
    _state.value = value.getOrElse { if(it != null) throw it else EmptyValue }
    return value.isSuccess
  }

  internal fun configure(receiveChannel: ReceiveChannel<T>) {
    channel = receiveChannel
  }

  override fun onAbandoned() {}

  override fun onForgotten() {
    channel.cancel()
  }

  override fun onRemembered() {}
}

@JvmInline
public value class OptionalState<@Suppress("unused") out T>(internal val state: State<Any?>) {
  public fun read() {
    state.value
  }
}

context(OptionRaise)
@Suppress("UNCHECKED_CAST")
public val <T> OptionalState<T>.value: T
  get() = if (state.value == EmptyValue) raise(None) else state.value as T

internal object EmptyValue

context(OptionRaise)
public operator fun <T> OptionalState<T>.provideDelegate(
  thisRef: Any?, property: Any?
): State<T> {
  value
  @Suppress("UNCHECKED_CAST") return state as State<T>
}

internal class ComprehensionScopeImpl(
  private val coroutineScope: CoroutineScope
) : ComprehensionScope {
  override fun readAll() {
    stack.forEach { it.state.read() }
  }

  private val stack = mutableListOf<ComprehensionState<*>>()
  val finished get() = stack.isEmpty()

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun <T> ComprehensionState<T>.register(producer: suspend ProducerScope<T>.() -> Unit): OptionalState<T> {
    if (this !in stack) {
      configure(coroutineScope.produce(block = producer))
      stack.add(this)
    }
    return state
  }

  /**
   * Unlocks the next lock in the list
   *
   * @return `true` if we're done, `false` otherwise
   */
  internal suspend fun unlockNext() {
    stack.removeLastWhile { !it.advance() }
  }
}

public inline fun <T> MutableList<T>.removeLastWhile(predicate: (T) -> Boolean) {
  val iterator = listIterator(size)
  while (iterator.hasPrevious()) {
    if (!predicate(iterator.previous())) {
      return
    }
    iterator.remove()
  }
}

public fun <T> listComprehension(
  body: @Composable context(ComprehensionScope) () -> Option<T>
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScopeImpl(this)
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<Option<T>>(1)

    launch(clock, start = CoroutineStart.UNDISPATCHED) {
      launchMolecule(mode = RecompositionMode.ContextClock, body = {
        body(scope)
      }, emitter = {
        clock.isRunning = false
        outputBuffer.trySend(it).getOrThrow()
      })
    }

    do {
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = if (result.isFailure) {
        clock.isRunning = true
        outputBuffer.receive()
      } else {
        result.getOrThrow()
      }
      value.onSome { emit(it) }
      scope.unlockNext()
    } while (!scope.finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): OptionalState<T> {
  val lock = remember { ComprehensionState<T>() }
  return lock.register {
    for (element in this@bind) {
      send(element)
    }
  }
}

context(ComprehensionScope)
@Composable
public fun <T> Flow<T>.bind(): OptionalState<T> {
  val lock = remember { ComprehensionState<T>() }
  return lock.register {
    collect { element ->
      send(element)
    }
  }
}

@Composable
public inline fun <R> ComprehensionScope.effect(block: () -> R): R {
  val scope by rememberUpdatedState(this)
  var lastRememberedValue by remember { mutableStateOf(false) }
  var result by remember {
    @Suppress("UNCHECKED_CAST")
    // will get set immediately
    mutableStateOf(null as R)
  }
  val newestValue = baseEffect {
    scope.readAll()
    Snapshot.withoutReadObservation {
      !lastRememberedValue
    }
  }
  if (newestValue != lastRememberedValue) {
    result = block()
    lastRememberedValue = newestValue
  }
  return result
}

@Composable
@PublishedApi
internal fun <R> baseEffect(block: () -> R): R = remember { derivedStateOf(block) }.let {
  Snapshot.withoutReadObservation { it.value }
}