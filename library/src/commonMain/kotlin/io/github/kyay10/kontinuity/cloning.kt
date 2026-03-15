package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.Frames.Copied.Companion.resumeWithImpl
import io.github.kyay10.kontinuity.Segmentable.Segment
import io.github.kyay10.kontinuity.Segmentable.Segment.Companion.invalidateAndCollectValues
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

internal const val REENTRANT_NOT_SUPPORTED = "Reentrant resumptions are not supported"
internal const val COPYING_NOT_SUPPORTED: String = "Copying continuations is not supported"

@PublishedApi
internal const val IS_NOT_ON_THE_STACK: String = " is not on the stack"
private const val NOT_A_COMPILER_CONTINUATION = "Not a compiler generated continuation "
private const val MODIFIED_CONCURRENTLY = " was likely modified concurrently: found "
private const val SEGMENT_ALREADY_USED = "Segment was already used once, but is being reused again"
private const val PROMPT_ALREADY_RESUMED = "Prompt was already resumed, so it cannot be invalidated"

private const val SMALL_DATA_BUFFER_SIZE = 6

internal expect val SUPPORTS_MULTISHOT: Boolean

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?

internal expect fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>
): Any?

public sealed class SplitSeq<in Start>(@JvmField @PublishedApi internal val realContext: CoroutineContext) :
  Continuation<Start>, CoroutineStackFrame {
  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) realContext.onErrorResume { resume(result) }
  }

  protected abstract fun resume(result: Result<Start>)
}

public sealed interface SplitContOrSegment

public val SplitContOrSegment.isSegment: Boolean
  get() {
    contract {
      returns(true) implies (this@isSegment is Segment<*, *>)
      returns(false) implies (this@isSegment is SplitCont<*>)
    }
    return this is Segment<*, *>
  }

@PublishedApi
internal inline fun SplitContOrSegment?.ifSegment(block: (Segment<*, *>?) -> Nothing): SplitCont<*> {
  contract {
    returns() implies (this@ifSegment is SplitCont<*>)
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
  }
  return when (this) {
    is Segment<*, *>? -> block(this)
    is SplitCont<*> -> this
  }
}

public sealed class SplitCont<in Start>(realContext: CoroutineContext) :
  CoroutineContext by realContext, SplitSeq<Start>(realContext), SplitContOrSegment {
  final override val context: CoroutineContext get() = this
}

@PublishedApi
internal fun <Start> SplitCont<Start>.errorIfEmptyCont(): Segmentable<Start> =
  this as? Segmentable<Start> ?: error(REENTRANT_NOT_SUPPORTED)

@PublishedApi
internal inline fun <Start, P, R> Frames<Start>.splitAt(
  p: Prompt<P>,
  stackRest: SplitCont<*>,
  block: (Frames<P>, SplitCont<*>, Segment<Start, P>) -> R
): R = block(
  Frames(p.frames),
  p.rest.ifSegment { error("$p$IS_NOT_ON_THE_STACK") },
  Segment(p, this, stackRest.errorIfEmptyCont())
)

internal class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>, context: CoroutineContext) :
  SplitCont<Start>(context) {
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null

  override fun resume(result: Result<Start>) = underlying.resumeWith(result)
}

@PublishedApi
@JvmInline
internal value class Frames<in Start>(val frames: Continuation<Start>) {
  fun copy(rest: Segmentable<*>) = Frames(Copied(frames, rest))

  fun resumeWith(result: Result<Start>) = resumeWithImpl(result)

  @PublishedApi
  internal class Under<RealStart, Start>(
    private val captured: Segment<RealStart, Start>,
    private val frames: Frames<Start>,
    private val rest: SplitCont<*>,
  ) : SplitSeq<RealStart>(rest.realContext) {
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null

    override fun resume(result: Result<RealStart>) = underflow().resumeWith(result)

    fun underflow() = captured.prependTo(false, frames, rest)
    fun underflowCopied(newRest: Segmentable<*>) = captured.prependTo(true, frames, newRest)
  }

  private class Copied<in Start>(
    cont: Continuation<Start>,
    @JvmField val rest: Segmentable<*>,
  ) : SplitSeq<Start>(rest.realContext) {
    private var cont: Continuation<*> = if (cont is Copied) cont.cont else cont
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null

    override fun resume(result: Result<Start>) = Frames(this).resumeWithImpl(result)

    companion object {
      private inline fun <T> Continuation<T>.invokeCopied(
        completion: Continuation<*>,
        context: CoroutineContext,
        result: Result<T>,
        onSuspend: () -> Nothing
      ) = runCatching({ invokeCopied(completion, context, result) }, onSuspend)

      @Suppress("UNCHECKED_CAST")
      tailrec fun <Start> Frames<Start>.resumeWithImpl(result: Result<Start>) {
        val frames = frames
        // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
        var current = frames as Continuation<Any?>
        var param: Result<Any?> = result
        if (frames is Copied) {
          val framesRest = frames.rest as Segmentable<Any?>
          current = frames.cont as Continuation<Any?>
          while (true) {
            if (current is Under<Any?, *>)
              return current.underflowCopied(framesRest).resumeWithImpl(param)
            var completion =
              current.completion as? Continuation<Any?> ?: error("$NOT_A_COMPILER_CONTINUATION$current")
            when (completion) {
              is Copied -> completion = completion.cont as Continuation<Any?>
              is Segmentable -> {
                // top-level completion reached -- invoke and return
                val outcome = current.invokeCopied(framesRest, framesRest, param) { return }
                return framesRest.underflow().resumeWithImpl(outcome)
              }
            }
            // Optimized by only setting it upon suspension.
            // This is safe only if no one accesses cont in between
            // That seems to be the case due to trampolining.
            // Note to self: if any weird behavior happens, uncomment this line
            //frames.cont = completion
            val outcome = current.invokeCopied(frames, framesRest, param) {
              frames.cont = completion
              return
            }
            //releaseIntercepted() // this state machine instance is terminating
            // unrolling recursion via loop
            current = completion
            param = outcome
          }
        } else return current.resumeWith(param)
      }
    }
  }
}

public class Prompt<Start> @PublishedApi internal constructor(
  context: CoroutineContext,
  frames: Continuation<Start>,
  @PublishedApi @JvmField internal var rest: SplitContOrSegment?,
) : Segmentable<Start>(context, frames) {
  @PublishedApi
  override fun underflow(): Frames<Start> = super.underflow().also { rest = null }

  @Suppress("UNCHECKED_CAST")
  context(arr: Array<out Any?>)
  override fun revalidateSingle(isFinal: Boolean, index: Int): Int {
    val frames = Frames(arr[index] as Continuation<Start>)
    val rest = arr[index + 1] as Segmentable<*>
    if (this.rest !== rest) {
      invalidateAndCollectValues()
      this.rest = rest
    }
    this.frames = (if (isFinal) frames else frames.copy(rest)).frames
    return index + 2
  }
}

public typealias Reader<S> = ReaderT<*, out S>

public class ReaderT<Start, S> @PublishedApi internal constructor(
  context: CoroutineContext,
  private val fork: S.() -> S,
  @JvmField internal var state: S,
  frames: Continuation<Start>,
  @PublishedApi @JvmField internal val rest: SplitCont<*>,
) : Segmentable<Start>(context, frames) {
  private class ForkOnFirstRead(state: Any?) {
    @JvmField
    val state: Any? = if (state is ForkOnFirstRead) state.state else state
  }

  @Suppress("UNCHECKED_CAST")
  public val value: S
    get() {
      var state = state
      if (state is ForkOnFirstRead) {
        state = fork(state.state as S)
        this.state = state
      }
      return state
    }

  @Suppress("UNCHECKED_CAST")
  context(arr: Array<out Any?>)
  override fun revalidateSingle(isFinal: Boolean, index: Int): Int {
    val state = arr[index] as S
    this@ReaderT.state = if (isFinal) state else ForkOnFirstRead(state) as S
    if (!isFinal) frames = Frames(frames).copy(rest.errorIfEmptyCont()).frames
    return index + 1
  }
}

private val SEGMENT_USED = arrayOfNulls<Any?>(0)

public sealed class Segmentable<Start>(
  context: CoroutineContext,
  @PublishedApi @JvmField internal var frames: Continuation<Start>,
) : SplitCont<Start>(context) {
  final override val callerFrame: CoroutineStackFrame? get() = frames as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null

  @PublishedApi
  internal open fun underflow(): Frames<Start> = Frames(frames)

  // returns new index
  context(arr: Array<out Any?>)
  protected abstract fun revalidateSingle(isFinal: Boolean, index: Int): Int

  final override fun resume(result: Result<Start>) {
    underflow().resumeWith(result)
  }

  @PublishedApi
  internal class Segment<Start, End>(
    private val delimiter: Prompt<End>,
    private val start: Frames<Start>,
    private val startRest: Segmentable<*>,
    private var values: Array<Any?>? = null,
  ) : SplitContOrSegment {
    init {
      delimiter.rest = this
    }

    @Suppress("UNCHECKED_CAST")
    fun prependTo(shouldCopy: Boolean, stack: Frames<End>, stackRest: SplitCont<*>): Frames<Start> {
      if (values === SEGMENT_USED) error(if (SUPPORTS_MULTISHOT) SEGMENT_ALREADY_USED else COPYING_NOT_SUPPORTED)
      val shouldCopy = shouldCopy && SUPPORTS_MULTISHOT

      return (if (shouldCopy) start.copy(startRest.errorIfEmptyCont()) else start).also {
        if (shouldCopy) collectValues()
        values?.run { startRest.revalidate(!shouldCopy, delimiter) }
        if (!shouldCopy) values = SEGMENT_USED
        if (delimiter.rest !== this) delimiter.invalidateAndCollectValues()
        delimiter.frames = stack.frames
        delimiter.rest = stackRest
      }
    }

    context(values: Array<Any?>)
    private tailrec fun Segmentable<*>.revalidate(isFinal: Boolean, delimiter: Prompt<*>, index: Int = 0) {
      if (this === delimiter) return
      val newIndex = revalidateSingle(isFinal, index)
      when (this) {
        is Prompt -> rest.ifSegment { error("${this@Segment}$MODIFIED_CONCURRENTLY$it") }
        is Reader<*> -> rest
      }.errorIfEmptyCont().revalidate(isFinal, delimiter, newIndex)
    }

    private fun collectValues() {
      if (values != null) return
      if (!SUPPORTS_MULTISHOT) return
      values = startRest.invalidate()
    }

    companion object {
      fun Segmentable<*>.invalidateAndCollectValues() = findSegment()?.collectValues()
    }
  }

  internal companion object {
    inline fun Segmentable<*>.findSegment(
      onReader: (current: Reader<*>) -> Unit = {},
      onPrompt: (current: Prompt<*>, rest: SplitCont<*>) -> Unit = { _, _ -> },
    ): Segment<*, *>? {
      var current: Segmentable<*> = this
      while (true) {
        current = when (current) {
          is Reader<*> -> current.rest.also { onReader(current) }
          is Prompt<*> -> current.rest.ifSegment { return it }.also { onPrompt(current, it) }
        }.errorIfEmptyCont()
      }
    }

    fun Segmentable<*>.invalidate(): Array<Any?> {
      var bufSize = SMALL_DATA_BUFFER_SIZE
      var buf = arrayOfNulls<Any?>(bufSize)
      var size = 0
      // TODO benchmark buf.size vs bufSize
      // Inv: buf.size == bufSize
      findSegment(onReader = {
        if (bufSize < size + 1) {
          bufSize *= 2
          buf = buf.copyOf(bufSize)
        }
        buf[size++] = it.state
      }) { prompt, rest ->
        if (bufSize < size + 2) {
          bufSize *= 2
          buf = buf.copyOf(bufSize)
        }
        buf[size++] = prompt.frames
        buf[size++] = rest
      } ?: error(PROMPT_ALREADY_RESUMED)
      return buf
    }
  }
}

@PublishedApi
internal inline fun <T, R> collectStack(continuation: Continuation<T>, block: (Frames<T>, SplitCont<*>) -> R): R =
  block(Frames(continuation), continuation.context as SplitCont<*>)