import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass

// Usually is CoroutineImpl
private val contClass: KClass<*> = run {
  var c: Continuation<*>? = null
  suspend { c = foo() }.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) { })
  val cont = c!!
  (js("Object.getPrototypeOf(Object.getPrototypeOf(cont)).constructor") as JsClass<*>).kotlin
}

private suspend fun foo(): Continuation<*> = suspendCoroutineUninterceptedOrReturn { it }

internal actual fun <T, R> Continuation<T>.compilerGeneratedCloneOrNull(
  prompt: Prompt<R>, replacement: Continuation<R>
): Continuation<T>? = takeIf { contClass.isInstance(it) }?.let { cont ->
  val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
  val resultContinuation = descriptors.resultContinuation_1.value as Continuation<*>
  val newResultContinuation = if (resultContinuation === cont) cont else resultContinuation.clone(prompt, replacement)
  descriptors.resultContinuation_1.value = newResultContinuation
  descriptors._context_1.value = newResultContinuation.context
  descriptors._intercepted_1.value = null
  js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
}