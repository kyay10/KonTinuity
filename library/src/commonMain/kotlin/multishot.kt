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

  private fun prepareContinuation(replacementPromptContinuation: Continuation<*>?, prompt: Prompt<*>?): Continuation<T> =
    cont.clone(replacementPromptContinuation, prompt).let { if (intercepted) it.intercepted() else it }

  override fun resumeWith(result: Result<T>) {
    prepareContinuation(null, null).resumeWith(result)
  }

  fun resumeWith(replacementPromptContinuation: Continuation<*>, prompt: Prompt<*>, result: Result<T>) {
    prepareContinuation(replacementPromptContinuation, prompt).resumeWith(result)
  }
}

@PublishedApi
internal suspend inline fun <T> suspendMultishotCoroutine(
  intercepted: Boolean = true, crossinline block: (MultishotContinuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(MultishotContinuation(it.clone(null, null), intercepted))
  COROUTINE_SUSPENDED
}