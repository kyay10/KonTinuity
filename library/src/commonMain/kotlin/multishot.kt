import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@OptIn(InternalCoroutinesApi::class)
public suspend fun <R> multishotBoundary(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(it)
}

@PublishedApi
internal class MultishotContinuation<T>(cont: Continuation<T>, private val intercepted: Boolean) : Continuation<T> {
  private val cont = cont.clone() // TODO: seems like this clone call could be removed
  override val context: CoroutineContext get() = cont.context

  override fun resumeWith(result: Result<T>) {
    val cont = cont.clone().let { if (intercepted) it.intercepted() else it }
    cont.resumeWith(result)
  }
}

@PublishedApi
internal class ContWrapper<T>(val cont: MultishotContinuation<T>)

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal suspend inline fun <T> suspendMultishotCoroutine(
  intercepted: Boolean = true, block: (Continuation<T>) -> Unit
): T {
  val res = suspendCoroutineUninterceptedOrReturn<Any?> {
    ContWrapper(MultishotContinuation(it, intercepted))
  } // ContWrapper<T> | T
  if (res is ContWrapper<*>) {
    block(res.cont as Continuation<T>)
    suspendCoroutineUninterceptedOrReturn<Nothing> { COROUTINE_SUSPENDED } // Suspend forever
  } else return res as T
}