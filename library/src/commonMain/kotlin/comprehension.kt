import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
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
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.jvm.JvmInline

@DslMarker
public annotation class ComprehensionDsl
public sealed interface ComprehensionTree

public class ComprehensionState<T> : RememberObserver, ComprehensionTree {
  internal var resetNeeded: Boolean = true
    private set

  private var channel: ReceiveChannel<T>? = null

  private val _state = mutableStateOf<Any?>(EmptyValue, neverEqualPolicy())
  internal val state get() = OptionalState<T>(_state)

  internal suspend fun advance(): Boolean {
    val value = channel!!.receiveCatching()
    value.onClosed { resetNeeded = true }
    _state.value = value.getOrElse { if (it != null) throw it else EmptyValue }
    return value.isSuccess
  }

  internal fun configure(channel: ReceiveChannel<T>) {
    if (!resetNeeded) return
    this.channel?.cancel()
    this.channel = channel
    resetNeeded = false
  }

  override fun onForgotten() {
    channel?.cancel()
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
) : ComprehensionTree {
  internal val stack = mutableListOf<ComprehensionState<*>>()
  internal var currentEndIndex = 0

  @OptIn(ExperimentalCoroutinesApi::class)
  internal fun <T> ComprehensionState<T>.configure(producer: suspend ProducerScope<T>.() -> Unit) {
    configure(coroutineScope.produce(block = producer))
  }

  /** Returns true if all states are finished */
  internal suspend fun unlockNext(): Boolean {
    currentEndIndex = 0
    for (state in stack.asReversed()) {
      if (state.advance()) break
    }
    return stack.all { it.resetNeeded }
  }

  internal fun readAll() {
    stack.subList(0, currentEndIndex).forEach { it.state.read() }
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

    launchMolecule({
      clock.isRunning = false
      outputBuffer.trySend(it).getOrThrow()
    }, clock, ComprehensionApplier(scope)) {
      body(scope)
    }

    outputBuffer.receive().onSome { emit(it) }

    while (!scope.unlockNext()) {
      clock.isRunning = true
      outputBuffer.receive().onSome { emit(it) }
    }
    coroutineContext.cancelChildren()
  }
}

internal class ComprehensionApplier(root: ComprehensionScope) :
  AbstractApplier<ComprehensionTree>(root) {
  override fun insertBottomUp(index: Int, instance: ComprehensionTree) {}

  override fun insertTopDown(index: Int, instance: ComprehensionTree) =
    with(current as ComprehensionScope) {
      instance as ComprehensionState<*>
      stack.add(index, instance)
    }

  override fun move(from: Int, to: Int, count: Int) = with(current as ComprehensionScope) {
    (stack as MutableList<ComprehensionTree>).move(from, to, count)
  }

  override fun remove(index: Int, count: Int) = with(current as ComprehensionScope) {
    (stack as MutableList<ComprehensionTree>).remove(index, count)
  }

  override fun onClear() {
    (current as ComprehensionScope).stack.clear()
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> bind(block: suspend ProducerScope<T>.() -> Unit): OptionalState<T> {
  val state = remember { ComprehensionState<T>() }
  ComposeNode<_, ComprehensionApplier>(factory = { state }, update = {
    if (state.resetNeeded) reconcile {
      configure(block)
    }
  })
  currentEndIndex++
  return state.state
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): OptionalState<T> = bind {
  forEach { send(it) }
}

context(ComprehensionScope)
@Composable
public fun <T> Flow<T>.bind(): OptionalState<T> = bind {
  collect { send(it) }
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
