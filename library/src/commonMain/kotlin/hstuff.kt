import _Reset.Companion._lazyReset
import _Reset.Companion._nestedReset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import arrow.fx.coroutines.ResourceScope

public sealed class H<R> {
  //public data class HS<T, R>(val f: Cont<T, R>, val x: suspend (Cont<T, R>) -> R) : H<R>()
  public data class HC<T, R>(val f: Cont<T, R>, val x: @Composable (Cont<T, R>) -> R) : H<R>() {
    @Composable
    public fun doIt(): R = x(f)
  }

  public data class HValue<R>(val v: R) : H<R>()
}

@ResetDsl
public suspend fun <R> ResourceScope.lazyGreset(
  hr: @Composable _Reset<H<R>>.(H<R>) -> R, body: @Composable _Reset<H<R>>.() -> R
): R = _lazyReset {
  nestedGreset(hr, null, body)
}

@Composable
@ResetDsl
public fun <R> nestedGreset(
  hr: @Composable _Reset<H<R>>.(H<R>) -> R,
  tag: _Reset<H<R>>? = null,
  body: @Composable _Reset<H<R>>.() -> R
): R {
  val reset = tag ?: _Reset()
  val result = _nestedReset(reset) {
    H.HValue(body())
  }
  return reset.hr(result)
}

@Composable
@ResetDsl
public inline fun <T, R> _Reset<H<R>>.gshift(
  crossinline hs: @Composable _Reset<H<R>>.(H<R>) -> R,
  crossinline block: @Composable (Cont<T, R>) -> R
): T =
  _shift { f ->
    H.HC<T, R>(@DontMemoize { x -> hs(f(x)) }) @DontMemoize { block(it) }
  }

@Composable
public fun <R> _Reset<H<R>>.hrStop(h: H<R>): R = when (h) {
  is H.HC<*, R> -> nestedGreset({ hrStop(it) }, this) { h.doIt() } // TODO: seems equivalent to hrProp.
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
private fun <T, R> _Reset<H<R>>.foo(hc: H.HC<T, R>) = _shift { g -> H.HC({ y -> hsProp(g(hc.f(y))) }, hc.x)}