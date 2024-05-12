import Suspender.Companion.suspender
import _Reset.Companion._nestedReset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import arrow.fx.coroutines.ResourceScope
import kotlin.coroutines.suspendCoroutine

public sealed class H<R> {
  public data class HC<T, R>(val f: Cont<T, R>, val x: @Composable (Cont<T, R>) -> R) : H<R>() {
    @Composable
    public fun doIt(): R = x(f)
  }

  public data class HValue<R>(val v: R) : H<R>()
}

@ResetDsl
public suspend fun <R> ResourceScope.lazyGreset(
  hr: @Composable _Reset<H<R>>.(H<R>) -> R, tag: _Reset<H<R>> = _Reset(), body: @Composable _Reset<H<R>>.() -> R
): R = with(suspender()) {
  suspendCoroutine { k ->
    startSuspendingComposition(k::resumeWith) {
      nestedGreset(hr, tag, body)
    }
  }
}

@Composable
@ResetDsl
public fun <R> nestedGreset(
  hr: @Composable _Reset<H<R>>.(H<R>) -> R, tag: _Reset<H<R>> = _Reset(), body: @Composable _Reset<H<R>>.() -> R
): R = tag.hr(_nestedReset(tag) {
  H.HValue<R>(body())
})

@Composable
@ResetDsl
public inline fun <T, R> _Reset<H<R>>.gshift(
  crossinline hs: @Composable _Reset<H<R>>.(H<R>) -> R, crossinline block: @Composable (Cont<T, R>) -> R
): T = _shiftC { f ->
  H.HC<T, R>(@DontMemoize { x -> hs(f(x)) }) @DontMemoize { block(it) }
}

@Composable
public fun <R> _Reset<H<R>>.hrStop(h: H<R>): R = when (h) {
  is H.HC<*, R> -> nestedGreset({ hrStop(it) }, this) { h.doIt() }
  is H.HValue -> h.v
}

@Composable
public fun <R> _Reset<H<R>>.hsStop(h: H<R>): R = hrStop(h)

@Composable
public fun <R> _Reset<H<R>>.hrProp(h: H<R>): R = when (h) {
  is H.HC<*, R> -> h.doIt()
  is H.HValue -> h.v
}

@Composable
public fun <R> _Reset<H<R>>.hsProp(h: H<R>): R = when (h) {
  is H.HC<*, R> -> foo(h)
  is H.HValue -> h.v
}

@Composable
private fun <T, R> _Reset<H<R>>.foo(hc: H.HC<T, R>) = _shiftC { g -> H.HC({ y -> hsProp(g(hc.f(y))) }, hc.x) }