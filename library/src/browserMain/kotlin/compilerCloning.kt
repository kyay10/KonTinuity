import kotlin.coroutines.Continuation

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private val baseContClass = kotlin.coroutines.InterceptedCoroutine::class

internal actual fun <T, R> Continuation<T>.compilerGeneratedCloneOrNull(
  prompt: Prompt<R>, replacement: Continuation<R>
): Continuation<T>? = takeIf { baseContClass.isInstance(it) }?.let { cont ->
  val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
  val resultContinuation = descriptors.resultContinuation_1.value as Continuation<*>
  val newResultContinuation = if (resultContinuation === cont) cont else resultContinuation.clone(prompt, replacement)
  descriptors.resultContinuation_1.value = newResultContinuation
  descriptors._context_1.value = newResultContinuation.context
  descriptors._intercepted_1.value = null
  js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
}