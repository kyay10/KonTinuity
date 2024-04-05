@file:OptIn(ExperimentalStdlibApi::class)

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
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
  fun obtainLock(): ComprehensionLock
  fun removeLock(lock: ComprehensionLock)
}

internal class ComprehensionLock(private val parent: ComprehensionScope) : AutoCloseable {
  private val waitingMutex = Mutex(true)
  private val resetMutex = Mutex(true)
  override fun close() {
    parent.removeLock(this)
  }

  val done: Boolean
    get() = resetMutex.isLocked && !waitingMutex.isLocked

  suspend fun awaitNextItem() {
    waitingMutex.lock()
  }

  suspend fun awaitReset() {
    waitingMutex.unlock()
    resetMutex.lock()
  }

  fun unlockOrReset() {
    if (done) {
      resetMutex.unlock()
    } else {
      waitingMutex.unlock()
    }
  }
}

internal class ComprehensionScopeImpl : ComprehensionScope {
  private val stack = mutableListOf<ComprehensionLock>()
  val finished: Boolean
    get() = stack.all { it.done }

  override fun obtainLock(): ComprehensionLock = ComprehensionLock(this).also {
    stack.add(it)
  }

  override fun removeLock(lock: ComprehensionLock) {
    stack.remove(lock)
  }

  fun unlockNext() {
    val i = stack.indexOfLast { !it.done }.coerceAtLeast(0)
    for (lock in stack.subList(i, stack.size)) {
      lock.unlockOrReset()
    }
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

    do {
      scope.unlockNext()
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = if (result.isFailure) {
        clock.isRunning = true
        outputBuffer.receive()
      } else {
        result.getOrThrow()
      }
      emit(value)
    } while (!scope.finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bind(): State<T> {
  val lock = remember(this@bind) { obtainLock() }
  return remember(this@bind) { mutableStateOf(first(), neverEqualPolicy()) }.apply {
    LaunchedEffect(this@bind) {
      lock.use { lock ->
        var first = true
        while (true) {
          lock.awaitReset()
          for (element in this@bind) {
            lock.awaitNextItem()
            if (first) {
              first = false
            } else {
              println("generating $element")
              value = element
            }
          }
        }
      }
    }
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bindHere(): T = bind().value
