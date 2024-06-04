import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

public class State<T> : Reader<T> {
  @PublishedApi
  internal val prompt: Prompt<Nothing> = Prompt()
}

// TODO would using mutation be saner here?
public suspend fun <T> State<T>.set(value: T) = prompt.takeSubCont(deleteDelimiter = false) { sk ->
  sk.pushSubContWith(Result.success(Unit), isDelimiting = true, extraContext = context(value))
}

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = set(f(get()))

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R {
  val state = State<T>()
  return state.pushState(value) { state.body() }
}

public suspend fun <T, R> State<T>.pushState(value: T, body: suspend () -> R): R = newReset {
  prompt.pushPrompt(context(value)) {
    abort(body())
  }
}

public suspend inline fun <T> State<T>.forEach(f: (T) -> Unit) {
  var coroutineContext: CoroutineContext? = coroutineContext
  var value = coroutineContext?.get(this)
  while(value != null) {
    f(value.value)
    coroutineContext = coroutineContext?.promptParentContext(prompt)
    value = coroutineContext?.get(this)
  }
}