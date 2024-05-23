import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(InternalCoroutinesApi::class)
public suspend fun <R> withMultishot(
  context: CoroutineContext = EmptyCoroutineContext, body: suspend CoroutineScope.() -> R
): R {
  val interceptor = context[ContinuationInterceptor] ?: coroutineContext[ContinuationInterceptor]
  check(interceptor !is MultishotContext) { "withMultishot cannot be used inside multishot context" }
  val multishot =
    if (interceptor is Delay) MultishotContextWithDelay(interceptor, interceptor) else MultishotContext(interceptor)
  return withContext(context + multishot, body)
}

@OptIn(InternalCoroutinesApi::class)
private class MultishotContextWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
  MultishotContext(interceptor), Delay by delay

private open class MultishotContext(private val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    MultishotContinuation(continuation.clone())

  inner class MultishotContinuation<T>(private val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) {
      val cont = cont.clone().let { if (interceptor != null) interceptor.interceptContinuation(it) else it }
      cont.resumeWith(result)
    }
  }
}

@PublishedApi
internal suspend inline fun <T> suspendMultishotCoroutine(crossinline block: (Continuation<T>) -> Unit): T =
  suspendCoroutineUninterceptedOrReturn {
    block(it.intercepted())
    COROUTINE_SUSPENDED
  }