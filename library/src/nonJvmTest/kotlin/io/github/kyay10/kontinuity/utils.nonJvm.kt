package io.github.kyay10.kontinuity
/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

val EmptyContinuation: Continuation<Any?> = Continuation(EmptyCoroutineContext) { result ->
  result.getOrThrow()
}

actual fun runSuspend(block: suspend () -> Unit) {
  block.startCoroutine(EmptyContinuation)
}