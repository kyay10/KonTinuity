import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class Handler<Error, T, R>(val handler: (Error, SubCont<T, R>) -> R, override val key: Handle<Error, T, R>) : CoroutineContext.Element

public class Handle<Error, T, R>: CoroutineContext.Key<Handler<Error, T, R>> {
  internal val prompt = Prompt<R>()
}

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.resetWithHandler(
  handler: (Error, SubCont<T, R>) -> R, body: suspend () -> R
): R = prompt.pushPrompt(Handler(handler, this), body)

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.fcontrol(error: Error): T {
  val handler = coroutineContext[this] ?: error("Handler $this not set")
  return prompt.takeSubCont(deleteDelimiter = false) { sk ->
    handler.handler(error, sk)
  }
}