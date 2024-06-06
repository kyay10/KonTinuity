import kotlin.jvm.JvmInline

@JvmInline
public value class Handle<Error, T> internal constructor(private val reader: Reader<suspend (Error) -> T>) {
  public constructor() : this(Reader())

  @ResetDsl
  public suspend fun fcontrol(error: Error): T = reader.ask()(error)

  @ResetDsl
  public suspend fun <R> resetWithHandler(
    handler: suspend (Error, Cont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(extraContext = reader.context { prompt.control { k -> handler(it, k) } }, body = body)
  }

  @ResetDsl
  public suspend fun <R> resetWithHandler0(
    handler: suspend (Error, Cont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(extraContext = reader.context { prompt.control0 { k -> handler(it, k) } }, body = body)
  }
}

@ResetDsl
public suspend fun <Error, T, R> newResetWithHandler(
  handler: suspend Handle<Error, T>.(Error, Cont<T, R>) -> R, body: suspend Handle<Error, T>.() -> R
): R = with(Handle<Error, T>()) {
  resetWithHandler({ e, k -> handler(e, k) }) { body() }
}