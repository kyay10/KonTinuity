import Reset.Companion.lazyReset
import androidx.compose.runtime.Composable
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope

public typealias Handle<Error, T, R> = suspend Handler<Error, T, R>.(Error, ControlOrShiftCont<T, R>) -> R

@ResetDsl
public suspend fun <Error, T, R> ResourceScope.lazyResetWithHandler(
  body: @Composable context(Handler<Error, T, R>) Reset<R>.() -> R,
  handler: Handle<Error, T, R>
): R = with(Handler(handler)) {
  lazyReset { body(this) }
}

@ResetDsl
public suspend fun <Error, T, R> resetWithHandler(
  body: @Composable context(Handler<Error, T, R>) Reset<R>.() -> R, handler: Handle<Error, T, R>
): R = resourceScope {
  lazyResetWithHandler(body, handler)
}

public class Handler<Error, T, R>(@PublishedApi internal var handler: Handle<Error, T, R>) {
  public fun installHandler(handler: suspend Handler<Error, T, R>.(Error, ControlOrShiftCont<T, R>) -> R) {
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

context(Reset<R>, Handler<Error, T, R>)
@Composable
@ResetDsl
public fun <Error, T, R> fcontrol(value: Error): T = controlOrShift { handler(this@Handler, value, it) }