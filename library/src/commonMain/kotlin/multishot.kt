import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@OptIn(InternalCoroutinesApi::class)
public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  val pStack = PStack()
  body.startCoroutine(Continuation(it.context + pStack) { result ->
    pStack.clear()
    it.resumeWith(result)
  })
}

@PublishedApi
internal class MultishotContinuation<T>(private val cont: Continuation<T>, private val intercepted: Boolean) :
  Continuation<T> {
  override val context: CoroutineContext get() = cont.context

  override fun resumeWith(result: Result<T>) {
    val cont = cont.clone().let { if (intercepted) it.intercepted() else it }
    cont.resumeWith(result)
  }
}

@PublishedApi
internal suspend inline fun <T> suspendMultishotCoroutine(
  intercepted: Boolean = true, crossinline block: (Continuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(MultishotContinuation(it, intercepted))
  COROUTINE_SUSPENDED
}