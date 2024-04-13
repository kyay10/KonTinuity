import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import arrow.core.Some
import arrow.core.raise.OptionRaise
import arrow.core.raise.option
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal sealed interface ComprehensionScope {
  fun ComprehensionLock.register()
}

internal class ComprehensionLock {
  private val waitingMutex = Mutex()

  val done: Boolean
    get() = !waitingMutex.isLocked

  var reset by mutableStateOf(false)

  suspend fun awaitNextItem() {
    waitingMutex.lock()
  }

  fun unlock() {
    waitingMutex.unlock()
  }

  fun signalFinished() {
    waitingMutex.unlock()
  }
}

internal class ComprehensionScopeImpl : ComprehensionScope {
  private val stack = mutableListOf<ComprehensionLock>()
  val finished: Boolean
    get() = stack.all { it.done }

  override fun ComprehensionLock.register() {
    if (this !in stack) stack.add(this)
  }

  fun unlockNext(): Boolean = finished.also {
    val index = stack.indexOfLast { !it.done }
    if (index == -1) return@also
    val snapshot = Snapshot.takeMutableSnapshot()
    snapshot.enter {
      stack[index].unlock()
      for (i in (index + 1)..<stack.size) {
        stack.removeAt(index + 1).apply {
          reset = !reset
        }
      }
    }
    snapshot.apply().check()
    snapshot.dispose()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || this::class != other::class) return false

    other as ComprehensionScopeImpl

    return stack == other.stack
  }

  override fun hashCode(): Int {
    return stack.hashCode()
  }
}

internal inline fun <T> listComprehension(
  crossinline body: @Composable context(ComprehensionScope) () -> Option<T>
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScopeImpl()
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
    var finished: Boolean
    do {
      finished = scope.unlockNext()
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = if (result.isFailure) {
        clock.isRunning = true
        outputBuffer.receive()
      } else {
        result.getOrThrow()
      }
      println("emitting: $value")
      value.onSome { emit(it) }
    } while (!finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bind(): OptionalState<T> {
  val lock = remember { ComprehensionLock() }
  val stateOfState = remember { mutableStateOf<MutableState<Option<T>>?>(null) }
  remember(lock.reset) {
    stateOfState.value = mutableStateOf(None, neverEqualPolicy())
  }
  lock.register()
  LaunchedEffect(lock.reset) {
    val state = stateOfState.value!!
    lock.awaitNextItem()
    for (element in this@bind) {
      lock.awaitNextItem()
      println("producing: $element")
      state.value = Some(element)
    }
    lock.signalFinished()
  }
  return remember { OptionalState(derivedStateOf(neverEqualPolicy()) { stateOfState.value!!.value }) }
}

public data class OptionalState<T>(val state: State<Option<T>>)

context(OptionRaise)
public val <T> OptionalState<T>.value: T
  get() = state.value.bind()

context(OptionRaise)
public operator fun <T> OptionalState<T>.provideDelegate(
  thisRef: Any?,
  property: Any?
): OptionalState<T> {
  state.value.bind()
  return this
}

public operator fun <T> OptionalState<T>.getValue(thisRef: Any?, property: Any?): T =
  state.value.getOrNull() ?: error("Should never happen")

context(A) private fun <A> given(): A = this@A

@Composable
public fun <R> effect(block: () -> R): R = remember { derivedStateOf(neverEqualPolicy(), block) }.value