import kotlin.coroutines.Continuation

@PublishedApi
internal actual fun <T> Continuation<T>.clone(): Continuation<T> {
  val cont = this
  return js("Object.defineProperties(Object.create(Object.getPrototypeOf(cont)), Object.getOwnPropertyDescriptors(cont))")
}