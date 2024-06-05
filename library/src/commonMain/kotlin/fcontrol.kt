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
    return reader.pushReader({ prompt.control { k -> handler(it, k) } }) {
      prompt.reset(body)
    }
  }

  @ResetDsl
  public suspend fun <R> mapWithHandler(
    handler: suspend (suspend (Error) -> T).(Error, Cont<T, R>) -> R, body: suspend () -> R
  ): R {
    // TODO this doesn't update if our continuation gets reinstated with a different parent handler
    val oldHandler = reader.ask()
    return resetWithHandler({ e, k -> oldHandler.handler(e, k) }, body)
  }
}

@ResetDsl
public suspend fun <Error, T, R> newResetWithHandler(
  handler: suspend Handle<Error, T>.(Error, Cont<T, R>) -> R, body: suspend Handle<Error, T>.() -> R
): R = with(Handle<Error, T>()) {
  resetWithHandler({ e, k -> handler(e, k) }) { body() }
}