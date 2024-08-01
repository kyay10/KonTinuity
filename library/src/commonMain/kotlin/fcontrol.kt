import kotlin.jvm.JvmInline

private fun interface FcontrolHandler<Error, T> : Stateful<FcontrolHandler<Error, T>> {
  suspend fun handle(error: Error): T
  override fun fork(): FcontrolHandler<Error, T> = this
}

@JvmInline
public value class Fcontrol<Error, T> private constructor(private val reader: StatefulReader<FcontrolHandler<Error, T>>) {
  public constructor() : this(StatefulReader())

  @ResetDsl
  public suspend fun fcontrol(error: Error): T = reader.ask().handle(error)

  @ResetDsl
  public suspend fun <R> resetFcontrol(
    handler: suspend (Error, SubCont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return reader.pushReader(FcontrolHandler { prompt.takeSubCont(false) { k -> handler(it, k) } }) {
      prompt.pushPrompt(body)
    }
  }

  @ResetDsl
  public suspend fun <R> resetFcontrol0(
    handler: suspend (Error, SubCont<T, R>) -> R, body: suspend () -> R
  ): R {
    val prompt = Prompt<R>()
    return reader.pushReader(FcontrolHandler {
      prompt.takeSubCont { k ->
        reader.deleteBinding()
        handler(it, k)
      }
    }) {
      prompt.pushPrompt(body)
    }
  }
}

@ResetDsl
public suspend fun <Error, T, R> newResetFcontrol(
  handler: suspend Fcontrol<Error, T>.(Error, SubCont<T, R>) -> R, body: suspend Fcontrol<Error, T>.() -> R
): R = with(Fcontrol<Error, T>()) {
  resetFcontrol({ e, k -> handler(e, k) }) { body() }
}