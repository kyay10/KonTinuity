import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> internal constructor(
  @PublishedApi internal val ekFragment: MultishotContinuation<T>,
  private val prompt: Prompt<R>,
) {

  @ResetDsl
  public suspend fun pushSubContWith(isDelimiting: Boolean = false, value: Result<T>): R = suspendMultishotCoroutine { k ->
    ekFragment.resumeWith(if (isDelimiting) Hole(prompt, k) else k, prompt, value)
  }


  @ResetDsl
  public suspend fun pushSubCont(isDelimiting: Boolean = false, value: suspend () -> T): R =
    suspendMultishotCoroutine { k ->
      val replacement = if (isDelimiting) Hole(prompt, k) else k
      value.startCoroutine(Continuation(k.context) { result ->
        ekFragment.resumeWith(replacement, prompt, result)
      })
    }

  @ResetDsl
  public suspend fun pushDelimSubContWith(value: Result<T>): R = pushSubContWith(isDelimiting = true, value)

  @ResetDsl
  public suspend fun pushDelimSubCont(value: suspend () -> T): R = pushSubCont(isDelimiting = true, value)
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(body: suspend Prompt<R>.() -> R): R = suspendMultishotCoroutine { k ->
  body.startCoroutine(this, Hole(this, k))
}

internal data class Hole<R>(val prompt: Prompt<R>, private val ultimateCont: MultishotContinuation<R>) :
  Continuation<R> {
  override val context: CoroutineContext get() = ultimateCont.context

  @Suppress("UNCHECKED_CAST")
  override fun resumeWith(result: Result<R>) {
    if (result.isFailure) {
      val exception = result.exceptionOrNull()
      if (exception is UnwindCancellationException && exception.prompt === prompt) {
        val (_, function, ekFragment, deleteDelimiter) = exception
        val ultimateCont =
          (if (deleteDelimiter) ultimateCont else this) as Continuation<Any?>
        if (ekFragment == null) {
          function.startCoroutine(null, ultimateCont)
        } else {
          val subCont = SubCont(ekFragment, prompt)
          function.startCoroutine(subCont as SubCont<Any?, Any?>, ultimateCont)
        }
        return
      }
    }
    ultimateCont.resumeWith(result)
  }
}

@Suppress("UNCHECKED_CAST")
@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendMultishotCoroutine(intercepted = false) { k ->
  throw UnwindCancellationException(
    this, body as suspend (SubCont<Any?, Any?>?) -> Any?, k as MultishotContinuation<Any?>, deleteDelimiter
  )
}

@ResetDsl
@PublishedApi
internal fun <R> Prompt<R>.abort(
  deleteDelimiter: Boolean = false, value: Result<R>
): Nothing = abortS(deleteDelimiter) { value.getOrThrow() }

@ResetDsl
@PublishedApi
internal fun <R> Prompt<R>.abortS(
  deleteDelimiter: Boolean = false, value: suspend () -> R
): Nothing {
  throw UnwindCancellationException(this, { value() }, null, deleteDelimiter)
}

public class Prompt<R>

internal data class UnwindCancellationException(
  val prompt: Prompt<*>,
  val function: suspend (SubCont<Any?, Any?>?) -> Any?,
  val ekFragment: MultishotContinuation<Any?>?,
  val deleteDelimiter: Boolean
) : CancellationException("Should never get swallowed")