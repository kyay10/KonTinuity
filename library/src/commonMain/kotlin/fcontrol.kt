import kotlin.jvm.JvmInline

@JvmInline
public value class Fcontrol<Error, T> internal constructor(private val reader: Reader<suspend (Error) -> T>) {
  public constructor() : this(Reader())

  @ResetDsl
  public suspend fun fcontrol(error: Error): T = reader.ask()(error)

  @ResetDsl
  public suspend fun <R> resetFcontrol(
    handler: suspend (Error, SubCont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(extraContext = reader.context { prompt.takeSubCont(false) { k -> handler(it, k) } }, body = body)
  }

  @ResetDsl
  public suspend fun <R> resetFcontrol0(
    handler: suspend (Error, SubCont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return prompt.pushPrompt(extraContext = reader.context { prompt.takeSubCont { k -> handler(it, k) } }, body = body)
  }
}

@ResetDsl
public suspend fun <Error, T, R> newResetFcontrol(
  handler: suspend Fcontrol<Error, T>.(Error, SubCont<T, R>) -> R, body: suspend Fcontrol<Error, T>.() -> R
): R = with(Fcontrol<Error, T>()) {
  resetFcontrol({ e, k -> handler(e, k) }) { body() }
}