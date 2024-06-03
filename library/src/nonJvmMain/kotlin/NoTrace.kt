import kotlin.coroutines.Continuation

@Suppress(
  "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING",
  "SEALED_INHERITOR_IN_DIFFERENT_MODULE"
)
internal actual class NoTrace actual constructor(
  prompt: Prompt<Any?>,
  function: suspend (SubCont<Any?, Any?>?) -> Any?,
  ekFragment: Continuation<Any?>?,
  deleteDelimiter: Boolean
) : UnwindCancellationException(prompt, function, ekFragment, deleteDelimiter)