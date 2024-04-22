import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

  private var _state: Any? = EmptyValue
  internal val state get() = Optional<T>(_state)

  internal suspend fun advance(): Boolean {
    val value = channel!!.receiveCatching()
    value.onClosed { resetNeeded = true }
    _state = value.getOrElse { if (it != null) throw it else EmptyValue }
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

@PublishedApi
internal object EmptyValue

@JvmInline
public value class Optional<@Suppress("unused") out T>(@PublishedApi internal val underlying: Any?) {
  context(OptionRaise)
  @Suppress("UNCHECKED_CAST")
  public val value: T
    get() = if (underlying == EmptyValue) raise(None) else underlying as T
}

context(OptionRaise)
public inline operator fun <T> Optional<T>.provideDelegate(
  thisRef: Any?, property: Any?
): Optional<T> = this.also { value }

@Suppress("UNCHECKED_CAST")
public inline operator fun <T> Optional<T>.getValue(
  thisRef: Any?, property: Any?
): T = underlying as T

@ComprehensionDsl
public class ComprehensionScope internal constructor(
  private val coroutineScope: CoroutineScope
) : ComprehensionTree {
  internal val stack = mutableListOf<ComprehensionState<*>>()
  private val changedSinceLastRun = mutableSetOf<ComprehensionState<*>>()

  @PublishedApi
  internal var shouldUpdateEffects: Boolean = false
    private set

  internal fun accessed(state: ComprehensionState<*>) {
    if (state in changedSinceLastRun) shouldUpdateEffects = true
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  internal fun <T> ComprehensionState<T>.configure(producer: suspend ProducerScope<T>.() -> Unit) {
    configure(coroutineScope.produce(block = producer))
  }

  /** Returns true if all states are finished */
  internal suspend fun unlockNext(): Boolean {
    shouldUpdateEffects = false
    changedSinceLastRun.apply {
      clear()
      for (state in stack.asReversed()) {
        add(state)
        if (state.advance()) break
      }
    }
    return stack.all { it.resetNeeded }
  }
}

public fun <T> listComprehension(
  body: @Composable context(ComprehensionScope) () -> Option<T>
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScope(this)
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<Option<T>>(1)
    var runSignal by mutableStateOf(Unit, neverEqualPolicy())

    launchMolecule({
      clock.isRunning = false
      outputBuffer.trySend(it).getOrThrow()
    }, clock, ComprehensionApplier(scope)) {
      runSignal
      body(scope)
    }

    outputBuffer.receive().onSome { emit(it) }
    while (!scope.unlockNext()) {
      runSignal = Unit
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
internal fun <T> bind(block: suspend ProducerScope<T>.() -> Unit): Optional<T> {
  val state = remember { ComprehensionState<T>() }
  ComposeNode<_, ComprehensionApplier>(factory = { state }, update = {
    if (state.resetNeeded) reconcile {
      configure(block)
    }
  })
  accessed(state)
  return state.state
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): Optional<T> = bind {
  forEach { send(it) }
}

context(ComprehensionScope)
@Composable
public fun <T> Flow<T>.bind(): Optional<T> = bind {
  collect { send(it) }
}

@JvmInline
public value class EffectState(@PublishedApi internal val state: MutableState<Any?>)

public val ComprehensionScope.effect: EffectState
  @Composable get() = remember(this) { EffectState(mutableStateOf(EmptyValue)) }

context(ComprehensionScope)
public inline operator fun <R> EffectState.invoke(block: () -> R): R = with(state) {
  if (shouldUpdateEffects || value == EmptyValue) {
    // TODO explore if caching exceptions is a good idea
    value = block()
  }
  @Suppress("UNCHECKED_CAST")
  value as R
}
