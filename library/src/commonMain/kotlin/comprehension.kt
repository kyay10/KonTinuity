import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
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

  fun unlockNext() : Boolean = finished.also {
    val index = stack.indexOfLast { !it.done }
    if (index == -1) return@also
    stack[index].unlock()
    for (i in (index + 1) ..< stack.size) {
      stack.removeAt(index + 1).apply {
        reset = !reset
      }
    }
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
  crossinline body: @Composable context(ComprehensionScope) () -> T
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScopeImpl()
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<T>(1)

    launch(clock, start = CoroutineStart.UNDISPATCHED) {
      launchMolecule(mode = RecompositionMode.ContextClock, body = {
        body(scope)
      }, emitter = {
        clock.isRunning = false
        outputBuffer.trySend(it).getOrThrow()
      })
    }
    var finished = false
    do {
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = if (result.isFailure) {
        clock.isRunning = true
        outputBuffer.receive()
      } else {
        result.getOrThrow()
      }
      println("emitting $value")
      emit(value)
      finished = scope.unlockNext()
    } while (!finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bind(): State<T> {
  val lock = remember { ComprehensionLock() }
  lock.register()
  return remember(lock.reset) { mutableStateOf(first(), neverEqualPolicy()) }.apply {
    LaunchedEffect(lock.reset) {
      var first = true
      for (element in this@bind) {
        lock.awaitNextItem()
        if (first) {
          println("skipping $element")
          first = false
        } else {
          println("generating $element")
          value = element
        }
      }
      lock.signalFinished()
    }
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bindHere(): T = bind().value

context(A) private fun <A> given(): A = this@A