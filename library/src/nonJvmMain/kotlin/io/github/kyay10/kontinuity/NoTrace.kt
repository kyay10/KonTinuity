package io.github.kyay10.kontinuity

import kotlin.coroutines.cancellation.CancellationException

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual open class NoTrace : CancellationException("Should never get swallowed")