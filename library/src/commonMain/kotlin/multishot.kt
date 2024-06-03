import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@OptIn(InternalCoroutinesApi::class)
public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(it)
}

@PublishedApi
internal class MultishotContinuation<T>(cont: Continuation<T>, private val intercepted: Boolean) : Continuation<T> {
  private val cont = cont.clone(null, null)
  override val context: CoroutineContext get() = cont.context

  fun withHoleReplaced(replacementHole: Continuation<*>, prompt: Prompt<*>): MultishotContinuation<T> =
    MultishotContinuation(cont.clone(replacementHole, prompt), intercepted)

  fun resumeWithHoleReplaced(replacementHole: Continuation<*>, prompt: Prompt<*>, result: Result<T>) {
    cont.clone(replacementHole, prompt).let { if (intercepted) it.intercepted() else it }.resumeWith(result)
  }

  override fun resumeWith(result: Result<T>) {
    cont.clone(null, null).let { if (intercepted) it.intercepted() else it }.resumeWith(result)
  }
}

@PublishedApi
internal suspend inline fun <T> suspendMultishotCoroutine(
  intercepted: Boolean = true, crossinline block: (MultishotContinuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(MultishotContinuation(it, intercepted))
  COROUTINE_SUSPENDED
}