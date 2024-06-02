import kotlin.coroutines.Continuation

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private val baseContClass = kotlin.coroutines.InterceptedCoroutine::class

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal actual fun <T> Continuation<T>.clone(replacementPromptContinuation: Continuation<*>?, prompt: Prompt<*>?): Continuation<T> = if (baseContClass.isInstance(this)) {
  val cont = this
  val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
  descriptors._intercepted_1.value = null
  val resultContinuation: Any? = descriptors.resultContinuation_1.value
  if (resultContinuation === cont) {
    descriptors.resultContinuation_1.value = cont
  } else if (resultContinuation is Continuation<*>) {
    descriptors.resultContinuation_1.value = resultContinuation.clone(replacementPromptContinuation, prompt)
  }
  js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
} else if (this is Hole<*> && this.prompt === prompt) {
  replacementPromptContinuation as Continuation<T>
} else this