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
  public suspend fun pushSubContWith(isDelimiting: Boolean = false, value: Result<T>): R =
    suspendMultishotCoroutine { k ->
      ekFragment.resumeWithHoleReplaced(if (isDelimiting) Hole(prompt, k) else k, prompt, value)
    }

  @ResetDsl
  public suspend fun pushSubCont(isDelimiting: Boolean = false, value: suspend () -> T): R =
    suspendMultishotCoroutine { k ->
      val replacement = if (isDelimiting) Hole(prompt, k) else k
      value.startCoroutine(ekFragment.withHoleReplaced(replacement, prompt))
    }

  @ResetDsl
  public suspend fun pushDelimSubContWith(value: Result<T>): R = pushSubContWith(isDelimiting = true, value)

  @ResetDsl
  public suspend fun pushDelimSubCont(value: suspend () -> T): R = pushSubCont(isDelimiting = true, value)
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(body: suspend () -> R): R = suspendMultishotCoroutine { k ->
  body.startCoroutine(Hole(this, k))
}

internal data class Hole<R>(val prompt: Prompt<R>, private val ultimateCont: MultishotContinuation<R>) :
  Continuation<R> {
  override val context: CoroutineContext get() = ultimateCont.context

  @Suppress("UNCHECKED_CAST")
  override fun resumeWith(result: Result<R>) {
    val exception = result.exceptionOrNull()
    if (exception is UnwindCancellationException && exception.prompt === prompt) {
      val function = exception.function as suspend (SubCont<Any?, R>?) -> R
      val ekFragment = exception.ekFragment
      val deleteDelimiter = exception.deleteDelimiter
      val subCont = ekFragment?.let { SubCont(ekFragment, prompt) }
      function.startCoroutine(subCont, if (deleteDelimiter) ultimateCont else this)
    } else ultimateCont.resumeWith(result)
  }
}

@Suppress("UNCHECKED_CAST")
@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendMultishotCoroutine(intercepted = false) { k ->
  // TODO inspect coroutine stack instead and resume at the right Hole
  throw NoTrace(
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
  throw NoTrace(this, { value() }, null, deleteDelimiter)
}

public class Prompt<R>

internal sealed class UnwindCancellationException(
  val prompt: Prompt<*>,
  val function: suspend (SubCont<Any?, Any?>?) -> Any?,
  val ekFragment: MultishotContinuation<Any?>?,
  val deleteDelimiter: Boolean
) : CancellationException("Should never get swallowed")

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class NoTrace(
  prompt: Prompt<*>,
  function: suspend (SubCont<Any?, Any?>?) -> Any?,
  ekFragment: MultishotContinuation<Any?>?,
  deleteDelimiter: Boolean
) : UnwindCancellationException