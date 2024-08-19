import kotlinx.coroutines.CancellationException

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual abstract class SeekingStackException: CancellationException("Should never get swallowed") {
  actual abstract fun use(stack: SplitSeq<*, *, *>)
}