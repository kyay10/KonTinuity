public class NestableHandle<in Error, out T, R> {
  internal val reader = Reader<suspend (Error, Cont<T, R>) -> R>()
  internal val prompt = Prompt<R>()
}

public typealias Handle<Error, T> = NestableHandle<Error, T, *>

public interface RecursiveHandler<R> {
  public suspend fun pushWithSameHandler(body: suspend () -> R): R
}

private class RecursiveHandlerImpl<Error, T, R>(
  private val handler: suspend RecursiveHandler<R>.(Error, Cont<T, R>) -> R,
  private val handle: NestableHandle<Error, T, R>
) : RecursiveHandler<R> {
  suspend fun callHandler(error: Error, cont: Cont<T, R>): R = handler(error, cont)
  override suspend fun pushWithSameHandler(body: suspend () -> R): R = handle.resetWithHandler(::callHandler, body)
}

@ResetDsl
public suspend fun <Error, T, R> NestableHandle<Error, T, R>.resetWithHandler(
  handler: suspend (Error, Cont<T, R>) -> R, body: suspend () -> R
): R = prompt.pushPrompt {
  reader.pushReader(handler, body)
}

@ResetDsl
public suspend fun <Error, T, R> NestableHandle<Error, T, R>.resetWithHandlerRec(
  handler: suspend RecursiveHandler<R>.(Error, Cont<T, R>) -> R, body: suspend () -> R
): R = prompt.pushPrompt {
  reader.pushReader(RecursiveHandlerImpl<Error, T, R>(handler, this)::callHandler, body)
}

@ResetDsl
public suspend fun <Error, T, R> newResetWithHandler(
  handler: suspend (Error, Cont<T, R>) -> R, body: suspend NestableHandle<Error, T, R>.() -> R
): R = with(NestableHandle<Error, T, R>()) {
  resetWithHandler(handler) { body() }
}

@ResetDsl
public suspend fun <Error, T, R> newResetWithHandlerRec(
  handler: suspend RecursiveHandler<R>.(Error, Cont<T, R>) -> R, body: suspend Handle<Error, T>.() -> R
): R = with(NestableHandle<Error, T, R>()) {
  resetWithHandlerRec(handler) { body() }
}

@ResetDsl
public suspend fun <Error, T> Handle<Error, T>.fcontrol(error: Error): T = fcontrolImpl(error)

// Introduces existential type R
@ResetDsl
private suspend fun <Error, T, R> NestableHandle<Error, T, R>.fcontrolImpl(error: Error): T {
  val handler = reader.ask()
  // TODO should we be using control or control0?
  return prompt.control0 { cont ->
    handler(error, cont)
  }
}