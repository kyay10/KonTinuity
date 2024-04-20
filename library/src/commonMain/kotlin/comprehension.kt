import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.OptionRaise
import kotlinx.coroutines.CoroutineScope
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
import kotlin.jvm.JvmInline

@DslMarker
public annotation class ComprehensionDsl

public class ComprehensionState<T> : RememberObserver {
  private lateinit var channel: ReceiveChannel<T>

  private val _state = mutableStateOf<Any?>(EmptyValue, neverEqualPolicy())
  internal val state get() = OptionalState<T>(_state)

  internal suspend fun advance(): Boolean {
    val value = channel.receiveCatching()
    _state.value = value.getOrElse { if (it != null) throw it else EmptyValue }
    return value.isSuccess
  }

  internal fun configure(receiveChannel: ReceiveChannel<T>) {
    channel = receiveChannel
  }

  override fun onForgotten() {
    channel.cancel()
  }

  override fun onAbandoned() {}
  override fun onRemembered() {}
}

internal object EmptyValue

@JvmInline
public value class OptionalState<@Suppress("unused") out T>(@PublishedApi internal val state: State<Any?>) {
  public fun read() {
    state.value
  }

  context(OptionRaise)
  @Suppress("UNCHECKED_CAST")
  public val value: T
    get() = if (state.value == EmptyValue) raise(None) else state.value as T
}

context(OptionRaise)
public inline operator fun <T> OptionalState<T>.provideDelegate(
  thisRef: Any?, property: Any?
): State<T> {
  value
  @Suppress("UNCHECKED_CAST") return state as State<T>
}

@ComprehensionDsl
public class ComprehensionScope internal constructor(
  private val coroutineScope: CoroutineScope
) {
  private val stack = mutableListOf<ComprehensionState<*>>()
  internal val finished get() = stack.isEmpty()

  @OptIn(ExperimentalCoroutinesApi::class)
  internal fun <T> ComprehensionState<T>.register(producer: suspend ProducerScope<T>.() -> Unit): OptionalState<T> {
    if (this !in stack) {
      configure(coroutineScope.produce(block = producer))
      stack.add(this)
    }
    return state
  }

  internal suspend fun unlockNext() {
    stack.removeLastWhile { !it.advance() }
  }

  internal fun readAll() {
    stack.forEach { it.state.read() }
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
    val scope = ComprehensionScope(this)
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<Option<T>>(1)

    launchMolecule(RecompositionMode.ContextClock, {
      clock.isRunning = false
      outputBuffer.trySend(it).getOrThrow()
    }, clock) {
      body(scope)
    }

    do {
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = result.getOrElse {
        clock.isRunning = true
        outputBuffer.receive()
      }
      value.onSome { emit(it) }
      scope.unlockNext()
    } while (!scope.finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): OptionalState<T> = remember { ComprehensionState<T>() }.register {
  for (element in this@bind) {
    send(element)
  }
}

context(ComprehensionScope)
@Composable
public fun <T> Flow<T>.bind(): OptionalState<T> = remember { ComprehensionState<T>() }.register {
  collect { element ->
    send(element)
  }
}

public class EffectState(scope: ComprehensionScope) {
  private var lastRememberedValue by mutableStateOf(false)

  private val _result: MutableState<Any?> = mutableStateOf(null)

  @PublishedApi
  internal val result: Any? by _result

  private val newestValue by derivedStateOf {
    scope.readAll()
    Snapshot.withoutReadObservation {
      !lastRememberedValue
    }
  }

  @PublishedApi
  internal val shouldUpdate: Boolean get() = Snapshot.withoutReadObservation { newestValue } != lastRememberedValue

  @PublishedApi
  internal fun update(result: Any?) {
    _result.value = result
    lastRememberedValue = Snapshot.withoutReadObservation { newestValue }
  }
}

public val ComprehensionScope.effect: EffectState
  @Composable get(): EffectState = remember(this) { EffectState(this) }

public inline operator fun <R> EffectState.invoke(block: () -> R): R = with(this) {
  if (shouldUpdate) {
    // TODO explore if caching exceptions is a good idea
    update(block())
  }
  @Suppress("UNCHECKED_CAST") return result as R
}
