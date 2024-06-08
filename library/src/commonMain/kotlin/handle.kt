import kotlin.jvm.JvmInline

@JvmInline
public value class Handle<Error, T> internal constructor(private val reader: Reader<suspend (Error) -> T>) {
  public constructor() : this(Reader())

  @ResetDsl
  public suspend fun call(error: Error): T = reader.ask()(error)

  @ResetDsl
  public suspend fun <R> handle(
    handler: suspend (Error, Cont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(
      extraContext = reader.context(DeepHandler(prompt, this, handler)::invoke), body = body
    )
  }

  private class DeepHandler<Error, T, R>(
    private val prompt: Prompt<R>,
    private val handle: Handle<Error, T>,
    private val handler: suspend (Error, Cont<T, R>) -> R
  ) {
    suspend operator fun invoke(error: Error): T = prompt.takeSubCont { k ->
      handler(error) {
        k.pushSubContWith(it, isDelimiting = true, extraContext = handle.reader.context(::invoke))
      }
    }
  }

  @ResetDsl
  public suspend fun <R> handleShallow(
    handler: suspend (Error, Cont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(extraContext = reader.context(ShallowHandler(prompt, handler)::invoke), body = body)
  }

  private class ShallowHandler<Error, T, R>(
    private val prompt: Prompt<R>,
    private val handler: suspend (Error, Cont<T, R>) -> R
  ) {
    suspend operator fun invoke(error: Error): T = prompt.takeSubCont { k ->
      handler(error) { result ->
        k.pushSubContWith(result)
      }
    }
  }
}

@ResetDsl
public suspend fun <Error, T, R> newHandle(
  handler: suspend (Error, Cont<T, R>) -> R, body: suspend Handle<Error, T>.() -> R
): R = with(Handle<Error, T>()) {
  handle(handler) { body() }
}

@ResetDsl
public suspend fun <Error, T, R> newHandleShallow(
  handler: suspend Handle<Error, T>.(Error, Cont<T, R>) -> R, body: suspend Handle<Error, T>.() -> R
): R = with(Handle<Error, T>()) {
  handleShallow({ e, k -> handler(e, k) }) { body() }
}