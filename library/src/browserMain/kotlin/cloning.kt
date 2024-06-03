import kotlin.coroutines.Continuation

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private val baseContClass = kotlin.coroutines.InterceptedCoroutine::class

@Suppress("UNCHECKED_CAST")
internal actual fun <T> Continuation<T>.clone(upTo: Hole<*>, replacement: Hole<*>): Continuation<T> =
  if (baseContClass.isInstance(this)) {
    val cont = this
    val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
    descriptors._intercepted_1.value = null
    val resultContinuation: Any? = descriptors.resultContinuation_1.value
    if (resultContinuation === cont) {
      descriptors.resultContinuation_1.value = cont
    } else if (resultContinuation is Continuation<*>) {
      val newResultContinuation = resultContinuation.clone(upTo, replacement)
      descriptors.resultContinuation_1.value = newResultContinuation
      descriptors._context_1.value = newResultContinuation.context
    }
    js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
  } else if (this == upTo) {
    replacement as Continuation<T>
  } else if (this is CloneableContinuation<T>) {
    clone(upTo, replacement)
  } else {
    error("Continuation $this is not cloneable, but $upTo is not found in the chain.")
  }