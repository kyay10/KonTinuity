/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Launch a coroutine into this [CoroutineScope] which will continually recompose any composition that has
 * the returned [Recomposer] as a parent.
 *
 * The coroutine context is inherited from the [CoroutineScope].
 */
internal fun CoroutineScope.launchRecomposer(): Recomposer {
  val recomposer = Recomposer(coroutineContext)
  var snapshotHandle: ObserverHandle? = null
  launch(start = UNDISPATCHED) {
    try {
      recomposer.runRecomposeAndApplyChanges()
    } catch (_: CancellationException) {
      snapshotHandle?.dispose()
    }
  }

  var applyScheduled = false
  snapshotHandle = Snapshot.registerGlobalWriteObserver {
    if (!applyScheduled) {
      applyScheduled = true
      launch {
        applyScheduled = false
        Snapshot.sendApplyNotifications()
      }
    }
  }
  return recomposer
}