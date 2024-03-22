import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import arrow.core.raise.option
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FibiTest {
  @Test
  fun `test 3rd element`() = runTest {
    val flow1 = flowOf(1, 2, 3)
    val flow2 = flowOf(2, 3, 4)
    flowComprehension {
      val first by flow1.collectHere(0)
      val second by flow2.collectHere(1)
      first to second
    }.test(10.seconds) {
      for (first in 0..3) {
        for (second in 1..4) {
          awaitItem() shouldBe (first to second)
        }
      }
      cancel()
    }
  }

  @Test
  fun `lists`() = runTest {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(2, 3, 4)
    val flow = listComprehension {
      val first by list1.bind()
      val second by list2.bind()
      println("first: $first, second: $second")
      option { first.bind() to second.bind() }
    }
    flow.test(10.seconds) {
      while (true) {
        println(awaitEvent())
      }
    }
    flow.test(10.seconds) {
      for (second in list2) {
        awaitError().shouldBeInstanceOf<NullPointerException>()
      }
      for (first in list1) {
        awaitError().shouldBeInstanceOf<NullPointerException>()
        for (second in list2) {
          awaitItem().also(::println) shouldBe (first to second)
        }
      }
      cancel()
    }
  }
}


sealed interface ComprehensionScope2 {
  fun obtainLock(): ComprehensionLock
  fun unlockNext()
  fun releaseLock()
}

class ComprehensionLock {
  val mutex = Mutex()
  internal val condition = mutex.newCondition()
  suspend fun awaitNextItem() {
    mutex.lock()
  }

  suspend fun awaitReset() = mutex.withLock {
    condition.await()
  }
}

class ComprehensionScope2Impl : ComprehensionScope2 {
  private val stack = mutableListOf<ComprehensionLock>()
  private var index = 0
  override fun obtainLock(): ComprehensionLock = ComprehensionLock().also {
    stack.add(it)
    index = stack.size - 1
  }

  override fun unlockNext() {
    if (stack.isEmpty()) return
    stack[index].mutex.unlock()
  }

  override fun releaseLock() {
    if (stack.isEmpty()) return
    index--
  }
}


internal fun <T> listComprehension(
  body: @Composable context(ComprehensionScope2) () -> Option<T>
): Flow<T> {
  val scope: ComprehensionScope2 = ComprehensionScope2Impl()
  return flow {
    coroutineScope {
      val clock = GatedFrameClock(this)
      val outputBuffer = Channel<T>(1)

      launch(clock, start = CoroutineStart.UNDISPATCHED) {
        launchMolecule(
          mode = RecompositionMode.ContextClock,
          body = { body(scope) },
          emitter = {
            clock.isRunning = false
            outputBuffer.trySend(it.getOrElse { return@launchMolecule }).getOrThrow()
          }
        )
      }

      while (true) {
        val result = outputBuffer.tryReceive()
        // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
        val value = if (result.isFailure) {
          scope.unlockNext()
          clock.isRunning = true
          outputBuffer.receive()
        } else {
          result.getOrThrow()
        }
        println("emitting $value")
        emit(value)
      }
    }
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> flowComprehension(
  body: @Composable context(ComprehensionScope) () -> T
): Flow<T> {
  val scope: ComprehensionScope = ComprehensionScopeImpl()
  return flow {
    coroutineScope {
      val clock = GatedFrameClock(this)
      val outputBuffer = Channel<T>(1)

      launch(clock, start = CoroutineStart.UNDISPATCHED) {
        launchMolecule(
          mode = RecompositionMode.ContextClock,
          emitter = {
            clock.isRunning = false
            outputBuffer.trySend(it).getOrThrow()
          },
          body = { body(scope) },
        )
      }

      for (item in outputBuffer) {
        emit(item)
        if (outputBuffer.isEmpty) {
          scope.unlockNext()
          clock.isRunning = true
        }
      }
    }
  }
}

sealed interface ComprehensionScope {
  fun obtainLock(): Mutex
  fun unlockNext()
  fun releaseLock()
}

class ComprehensionScopeImpl : ComprehensionScope {
  private val stack = mutableListOf<Mutex>()
  override fun obtainLock(): Mutex = Mutex(true).also {
    stack.add(it)
  }

  override fun unlockNext() {
    if (stack.isEmpty()) return
    stack.last().unlock()
  }

  override fun releaseLock() {
    if (stack.isEmpty()) return
    stack.removeAt(stack.lastIndex)
  }
}

context(ComprehensionScope)
@Composable
fun <T : R, R> Flow<T>.collectHere(
  initial: R,
  context: CoroutineContext = EmptyCoroutineContext
): State<R> = with(remember { obtainLock() }) {
  produceState(initial, this, context, given<ComprehensionScope>()) {
    if (context == EmptyCoroutineContext) {
      collect { element ->
        withLock {
          value = element
        }
        lock()
      }
      releaseLock()
    } else withContext(context) {
      collect {
        withLock {
          value = it
        }
        lock()
      }
      releaseLock()
    }
  }
}

context(Condition)
@Composable
fun <T> List<T>.bind(): State<T> =
  produceState(first(), this, given<Condition>()) {
    for (element in this@bind) {
      mutex.withLock {
        await()
        value = element
      }
    }
  }

context(ComprehensionScope2)
@Composable
fun <T> List<T>.bind(): State<Option<T>> {
  val lock = remember { obtainLock() }
  val state = remember {
    mutableStateOf(
      None,
      NoneEqualPolicy as SnapshotMutationPolicy<Option<T>>
    )
  }
  LaunchedEffect(lock, this, given<ComprehensionScope2>()) {
    for (element in this@bind) {
      lock.awaitNextItem()
      println("gonna store $element from ${this@bind}")
      state.value = Some(element)
    }
    lock.awaitReset()
  }
  return state
}

private data object NoneEqualPolicy : SnapshotMutationPolicy<Option<*>> {
  override fun equivalent(a: Option<*>, b: Option<*>): Boolean = a is None && b is None
}


context(T) fun <T> given(): T = this@T
