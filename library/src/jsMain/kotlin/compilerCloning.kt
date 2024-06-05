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

internal actual val Continuation<*>.isCompilerGenerated: Boolean get() = contClass.isInstance(this)
internal actual val Continuation<*>.completion: Continuation<*> get() = asDynamic().resultContinuation_1

internal actual fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T> {
  val cont = this
  val descriptors = js("Object.getOwnPropertyDescriptors(cont)")
  descriptors.resultContinuation_1.value = completion
  descriptors._context_1.value = completion.context
  descriptors._intercepted_1.value = null
  return js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), descriptors)")
}