public typealias Handle<Error, T, R> = suspend Handler<Error, T, R>.(Error, Cont<T, R>) -> R

@ResetDsl
public suspend fun <Error, T, R> resetWithHandler(
  body: suspend context(Handler<Error, T, R>) Prompt<R>.() -> R, handler: Handle<Error, T, R>
): R = with(Handler(handler)) {
  topReset { body(this) }
}

public class Handler<Error, T, R>(@PublishedApi internal var handler: Handle<Error, T, R>) {
  public fun installHandler(handler: Handle<Error, T, R>) {
    this.handler = handler
  }

  @ResetDsl
  public inline fun handle(block: () -> R, noinline handler: Handle<Error, T, R>): R {
    val previousHandler = this.handler
    installHandler(handler)
    return try {
      block()
    } finally {
      installHandler(previousHandler)
    }
  }
}

context(Handler<Error, T, R>)
@ResetDsl
public suspend fun <Error, T, R> Prompt<R>.fcontrol(value: Error): T = control { handler(this@Handler, value, it) }