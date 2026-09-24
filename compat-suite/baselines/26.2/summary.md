# Lecithin plugin compatibility suite - run `20260924-baseline-26.2`

Minecraft 26.2 · protocol profile 26.2 · suite commit `70856ea637` · reference `paper`

## Targets

| target | role | jar | sha256 | server version | boot |
|---|---|---|---|---|---|
| `paper` | reference | paper:26.2:118:1fdb86222a567800f4f4b61104ff565284f9f5c891190fec20a80d41eb985108 | `1fdb86222a56` | 26.2-118-5d4f9bd (MC: 26.2) | 33.81 s |
| `lecithin` | candidate | github-release:LophineLabs/Lecithin:26.2-70856ea:lecithin-26.2-paperclip.jar:fadb38f62f61b3592378edcbbade41e52c5454e3c7ce9ce4bd281b42c6733e70 | `fadb38f62f61` | 26.2-64-70856ea (MC: 26.2) | 21.003 s |
| `lecithin-dispatch-off` | candidate | github-release:LophineLabs/Lecithin:26.2-70856ea:lecithin-26.2-paperclip.jar:fadb38f62f61b3592378edcbbade41e52c5454e3c7ce9ce4bd281b42c6733e70 | `fadb38f62f61` | 26.2-64-70856ea (MC: 26.2) | 24.052 s |

## Fixtures (API eras)

Every fixture is the same scenario source compiled separately against its own API. None declares `folia-supported`.

| fixture | compiled against | class file | api-version | layers | loaded on `paper` | loaded on `lecithin` | loaded on `lecithin-dispatch-off` |
|---|---|---|---|---|---|---|---|
| `legacy-1_8_8` | Spigot API 1.8.8 (2015, pre-flattening, no api-version)<br>`org.spigotmc:spigot-api:1.8.8-R0.1-20160221.082514-43` | 52 | *(none - legacy)* | [base,pre_flattening] | yes | yes | yes |
| `paper-26_2` | Paper API 26.2 build 118 (api-version 26.2)<br>`io.papermc.paper:paper-api:26.2.build.118-stable` | 69 | 26.2 | [base,since_1_13,paper_modern] | yes | yes | yes |
| `spigot-1_16_5` | Spigot API 1.16.5 (2021, api-version 1.16)<br>`org.spigotmc:spigot-api:1.16.5-R0.1-20210611.041013-99` | 52 | 1.16 | [base,since_1_13] | yes | yes | yes |

## Overview

Reference contract self-check: 70 of 70 cases hold on `paper`.

| target | PASS | SEMANTICALLY_EQUIVALENT | EXPECTED_FOLIA_DIFFERENCE | LECITHIN_EXTENSION | UNSUPPORTED_BY_DESIGN | KNOWN_FAIL | UNEXPECTED_PASS | REGRESSION | MISSING | HARNESS_ERROR | REFERENCE_INVALID |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `lecithin` | 46 | · | · | · | · | **24** | · | · | · | · | · |
| `lecithin-dispatch-off` | 10 | · | 45 | · | · | **15** | · | · | · | · | · |

## Results by archetype

Cells: classification against the reference; differing observed fields as `field: reference → target`. A row labelled *all* means every fixture era got the same verdict.

### Loading (plugin.yml, legacy plugins)

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `legacy.pre_flattening_material_constant` | legacy-1_8_8 | OBSERVED | PASS | PASS |
| `loading.enabled_without_folia_supported` | *all* | PASS | PASS | PASS |

### Legacy scheduler contract

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `sched.bukkit_async_then_legacy_sync` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`innerScheduleException: none → UnsupportedOperationException`<br>`primaryThread: true → not-run`<br>`runs: 1 → 0` |
| `sched.bukkitrunnable_timer_self_cancel` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`primaryThread: true → not-run`<br>`runs: 3 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.call_sync_method_from_bukkit_async` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`callablePrimary: true → not-run`<br>`exception: none → UnsupportedOperationException`<br>`value: 42 → not-run` |
| `sched.consumer_timer_self_cancel` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`primaryThread: true → not-run`<br>`runs: 3 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.legacy_cancel_before_first_run` | *all* | PASS | **KNOWN_FAIL**<br>`queuedBeforeCancel: true → false` | **EXPECTED_FOLIA_DIFFERENCE**<br>`queuedAfterCancel: false → ∅`<br>`queuedBeforeCancel: true → ∅`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.legacy_delayed_from_onenable` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`primaryThread: true → not-run`<br>`runs: 1 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.legacy_repeating_cancel_by_id` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`primaryThread: true → not-run`<br>`queuedAfterCancel: false → not-run`<br>`runs: 5 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.legacy_repeating_from_onenable` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`primaryThread: true → not-run`<br>`runs: 3 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.legacy_sync_delayed` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`delayRespected: true → not-run`<br>`primaryThread: true → not-run`<br>`runs: 1 → 0`<br>`scheduleException: none → UnsupportedOperationException` |
| `sched.run_task_asynchronously` | *all* | PASS | PASS | PASS |

### Tebex archetype - server-control callback with no entity/region provenance

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `tebex.bukkit_async_poll_to_legacy_sync` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more |
| `tebex.completable_future_callback_to_legacy_sync` | *all* | PASS | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more |
| `tebex.foreign_thread_callback_to_legacy_sync` | *all* | PASS | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more |
| `tebex.nested_async_to_legacy_sync` | *all* | PASS | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more | **KNOWN_FAIL**<br>`dispatchException: none → not-run`<br>`dispatchReturned: true → not-run`<br>`messageException: none → not-run`<br>`primaryThread: true → not-run`<br>+3 more |

### Essentials archetype - async PlayerEvent to player-scoped continuation

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `ess.async_chat_to_call_sync_method` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`A.playerAccess: ok → not-run`<br>`A.primaryThread: true → not-run`<br>`A.runs: 1 → 0`<br>`A.scheduleException: none → UnsupportedOperationException`<br>+4 more |
| `ess.async_chat_to_legacy_delayed` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`A.playerAccess: ok → not-run`<br>`A.primaryThread: true → not-run`<br>`A.runs: 1 → 0`<br>`A.scheduleException: none → UnsupportedOperationException`<br>+4 more |
| `ess.async_chat_to_legacy_runtask` | *all* | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`A.playerAccess: ok → not-run`<br>`A.primaryThread: true → not-run`<br>`A.runs: 1 → 0`<br>`A.scheduleException: none → UnsupportedOperationException`<br>+4 more |
| `ess.paper_async_chat_to_legacy_runtask` | paper-26_2 | PASS | PASS | **EXPECTED_FOLIA_DIFFERENCE**<br>`A.playerAccess: ok → not-run`<br>`A.primaryThread: true → not-run`<br>`A.runs: 1 → 0`<br>`A.scheduleException: none → UnsupportedOperationException`<br>+4 more |

### GriefPrevention archetype - different regions, one plugin-owned state

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `gp.legacy_repeating_per_player_shared_state` | *all* | PASS | **KNOWN_FAIL**<br>`concurrentEntry: false → true` | **EXPECTED_FOLIA_DIFFERENCE**<br>`A.scheduleException: none → UnsupportedOperationException`<br>`B.scheduleException: none → UnsupportedOperationException`<br>`totalRuns: 20 → 0` |
| `gp.region_callbacks_shared_state` | *all* | PASS | **KNOWN_FAIL**<br>`concurrentEntry: false → true` | **KNOWN_FAIL**<br>`concurrentEntry: false → true` |

### Task lifetime and plugin disable

| case | fixture | `paper` contract | `lecithin` | `lecithin-dispatch-off` |
|---|---|---|---|---|
| `lifecycle.disable_stops_all_tasks` | *all* | PASS | **KNOWN_FAIL**<br>`delayedQueuedBeforeDisable: true → false`<br>`pendingBeforeDisable: 5 → 1` | **KNOWN_FAIL**<br>`delayedQueuedBeforeDisable: true → false`<br>`pendingBeforeDisable: 5 → 1` |
| `lifecycle.schedule_for_disabled_plugin_rejected` | *all* | PASS | PASS | PASS |
| `lifecycle.task_outlives_scheduling_context` | *all* | PASS | **KNOWN_FAIL**<br>`alive.async-chat-ctx: true → false` | **EXPECTED_FOLIA_DIFFERENCE**<br>`alive.async-chat-ctx: true → false`<br>`alive.enable-sync: true → false`<br>`alive.region-ctx: true → false`<br>`async-chat-ctx.scheduleException: none → UnsupportedOperationException`<br>+2 more |

## Registered and unexplained differences

### lecithin · gp.legacy_repeating_per_player_shared_state — KNOWN_FAIL

a legacy sync repeating task per player, scheduled from each player's callback, runs 10x each and never overlaps on plugin-owned state

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (CRASH_OR_RACE): Legacy repeating tasks scheduled from each player's callback are redispatched to each caller's region and run in parallel on the same plugin-owned state. Needs plugin serialization (Managed Execution), not a scheduler routing change.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `concurrentEntry` `false` → `true`
- `paper-26_2`: `concurrentEntry` `false` → `true`
- `spigot-1_16_5`: `concurrentEntry` `false` → `true`
- diag (`legacy-1_8_8` on `lecithin`): `{"A.taskThread":"Folia Region Scheduler Thread #0","B.taskThread":"Folia Region Scheduler Thread #1","maxInside":2,"listFaults":0}`

### lecithin · gp.region_callbacks_shared_state — KNOWN_FAIL

two players' command-preprocess callbacks (different regions) touching one plugin-owned list never overlap, as on one main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (CRASH_OR_RACE): Two players' callbacks run in parallel on two region threads and are both inside the same plugin-owned critical section. Paper's single main thread serialized them; Lecithin has no plugin execution domain yet (GP 16.18.7 recentLoginLogoutNotifications shape).
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `concurrentEntry` `false` → `true`
- `paper-26_2`: `concurrentEntry` `false` → `true`
- `spigot-1_16_5`: `concurrentEntry` `false` → `true`
- diag (`legacy-1_8_8` on `lecithin`): `{"A.thread":"Folia Region Scheduler Thread #0","B.thread":"Folia Region Scheduler Thread #1","maxInside":2,"listFaults":0}`

### lecithin · lifecycle.disable_stops_all_tasks — KNOWN_FAIL

disablePlugin(victim) cancels every task it owns - sync, async, delayed and context-scheduled - and none runs again

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (REGRESSION): Redispatched legacy tasks are invisible to BukkitScheduler.getPendingTasks() and isQueued() (only the async task is listed: 1 of 5). Same root cause as sched.legacy_cancel_before_first_run. Disable cleanup itself is correct: no task runs after disablePlugin.
- Tracking: https://app.notion.com/p/3e572084219f81e38f10d827d4433d42
- `legacy-1_8_8`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- `paper-26_2`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- `spigot-1_16_5`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- diag (`legacy-1_8_8` on `lecithin`): `{"taskIds":"{enable-sync=14, async-chat-ctx=95, enable-delayed-long=15, enable-async=16, region-ctx=98}","disableThread":"Folia Region Scheduler Thread #1"}`

### lecithin · lifecycle.task_outlives_scheduling_context — KNOWN_FAIL

repeating tasks keep running after the player whose callback scheduled them has left (tasks belong to the plugin)

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (REGRESSION): A sync repeating task scheduled from an async PlayerEvent is redispatched to that player's EntityScheduler, so it is retired when the player quits. On Paper a Bukkit task belongs to the plugin and keeps running. The region-context task survives. Task owner vs task lifetime must be modelled separately.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `alive.async-chat-ctx` `true` → `false`
- `paper-26_2`: `alive.async-chat-ctx` `true` → `false`
- `spigot-1_16_5`: `alive.async-chat-ctx` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin`): `{"async-chat-ctx.scheduleThread":"Async Chat Thread - #1","region-ctx.scheduleThread":"Folia Region Scheduler Thread #1","runsIn1500ms.enable-sync":30,"runsIn1500ms.enable-async":30,"runsIn1500ms.region-ctx":30,"runsIn1500ms.async-chat-ctx":0}`

### lecithin · sched.legacy_cancel_before_first_run — KNOWN_FAIL

a delayed task cancelled by id before it is due never runs; isQueued is true before and false after

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (REGRESSION): BukkitScheduler.isQueued(id) is false for a redispatched legacy task. LecithinDispatchedTasks.isTracked() exists for exactly this but has no caller in any patch (paper-patches 0014 wires cancelById/cancelAll only). Cancel itself works: the task never runs.
- Tracking: https://app.notion.com/p/3e572084219f81e38f10d827d4433d42
- `legacy-1_8_8`: `queuedBeforeCancel` `true` → `false`
- `paper-26_2`: `queuedBeforeCancel` `true` → `false`
- `spigot-1_16_5`: `queuedBeforeCancel` `true` → `false`

### lecithin · tebex.completable_future_callback_to_legacy_sync — KNOWN_FAIL

a CompletableFuture.thenAccept stage on a plugin-owned executor calls runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): Same as the foreign-thread case: a CompletableFuture stage on a plugin-owned executor carries no provenance; runTask is rejected.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin`): `{"scheduleThread":"cfx-http-client-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin · tebex.foreign_thread_callback_to_legacy_sync — KNOWN_FAIL

a callback on a plugin-created thread (HTTP client) calls runTask; the task runs once on the main thread and can dispatch a console command

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): runTask from a plugin-created thread has no region/entity/global owner, so LecithinCallerContextDispatch falls through to stock Folia rejection (UnsupportedOperationException). Correct fail-closed behaviour under the current provenance model; closing the gap needs a server-control / plugin execution lane, not unknown->Global.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin`): `{"scheduleThread":"cfx-http-callback-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin · tebex.nested_async_to_legacy_sync — KNOWN_FAIL

an async task that starts another async task, which calls runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): LecithinExecutionProvenance bounds async inheritance to one hop; the second async task has no owner, so its runTask is rejected. The one-hop variant (tebex.bukkit_async_poll_to_legacy_sync) passes.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin`): `{"scheduleThread":"Craft Scheduler Thread - 1 - CompatFixture-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin-dispatch-off · ess.async_chat_to_call_sync_method — EXPECTED_FOLIA_DIFFERENCE

AsyncPlayerChatEvent handler blocks on callSyncMethod(...).get(); the callable runs on the main thread and can touch that player

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"B.eventThread":"Async Chat Thread - #1","A.eventThread":"Async Chat Thread - #0","A.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.callSyncMethod, delay=0, period=-1) is not supported under regionised threading","B.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.callSyncMethod, delay=0, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · ess.async_chat_to_legacy_delayed — EXPECTED_FOLIA_DIFFERENCE

AsyncPlayerChatEvent handler calls scheduleSyncDelayedTask(task, 2); the task runs once on the main thread and can touch that player

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"B.eventThread":"Async Chat Thread - #1","A.eventThread":"Async Chat Thread - #0","B.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncDelayedTask, delay=2, period=-1) is not supported under regionised threading","A.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncDelayedTask, delay=2, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · ess.async_chat_to_legacy_runtask — EXPECTED_FOLIA_DIFFERENCE

AsyncPlayerChatEvent handler calls runTask; the task runs once on the main thread and can read/write that player

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"B.eventThread":"Async Chat Thread - #1","A.eventThread":"Async Chat Thread - #0","B.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","A.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · ess.paper_async_chat_to_legacy_runtask — EXPECTED_FOLIA_DIFFERENCE

Paper AsyncChatEvent handler calls runTask; the task runs once on the main thread and can read/write that player

Fixtures: `paper-26_2`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `paper-26_2`: `A.playerAccess` `ok` → `not-run`, `A.primaryThread` `true` → `not-run`, `A.runs` `1` → `0`, `A.scheduleException` `none` → `UnsupportedOperationException`, `B.playerAccess` `ok` → `not-run`, `B.primaryThread` `true` → `not-run`, `B.runs` `1` → `0`, `B.scheduleException` `none` → `UnsupportedOperationException`
- diag (`paper-26_2` on `lecithin-dispatch-off`): `{"A.eventThread":"Async Chat Thread - #0","B.eventThread":"Async Chat Thread - #1","B.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-paper-26_2 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","A.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-paper-26_2 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · gp.legacy_repeating_per_player_shared_state — EXPECTED_FOLIA_DIFFERENCE

a legacy sync repeating task per player, scheduled from each player's callback, runs 10x each and never overlaps on plugin-owned state

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `A.scheduleException` `none` → `UnsupportedOperationException`, `B.scheduleException` `none` → `UnsupportedOperationException`, `totalRuns` `20` → `0`
- `paper-26_2`: `A.scheduleException` `none` → `UnsupportedOperationException`, `B.scheduleException` `none` → `UnsupportedOperationException`, `totalRuns` `20` → `0`
- `spigot-1_16_5`: `A.scheduleException` `none` → `UnsupportedOperationException`, `B.scheduleException` `none` → `UnsupportedOperationException`, `totalRuns` `20` → `0`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"B.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=1, period=1) is not supported under regionised threading","A.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=1, period=1) is not supported under regionised threading","maxInside":0,"listFaults":0}`

### lecithin-dispatch-off · gp.region_callbacks_shared_state — KNOWN_FAIL

two players' command-preprocess callbacks (different regions) touching one plugin-owned list never overlap, as on one main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (CRASH_OR_RACE): Two players' callbacks run in parallel on two region threads and are both inside the same plugin-owned critical section. Paper's single main thread serialized them; Lecithin has no plugin execution domain yet (GP 16.18.7 recentLoginLogoutNotifications shape).
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `concurrentEntry` `false` → `true`
- `paper-26_2`: `concurrentEntry` `false` → `true`
- `spigot-1_16_5`: `concurrentEntry` `false` → `true`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"A.thread":"Folia Region Scheduler Thread #0","B.thread":"Folia Region Scheduler Thread #1","maxInside":2,"listFaults":0}`

### lecithin-dispatch-off · lifecycle.disable_stops_all_tasks — KNOWN_FAIL

disablePlugin(victim) cancels every task it owns - sync, async, delayed and context-scheduled - and none runs again

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (REGRESSION): Redispatched legacy tasks are invisible to BukkitScheduler.getPendingTasks() and isQueued() (only the async task is listed: 1 of 5). Same root cause as sched.legacy_cancel_before_first_run. Disable cleanup itself is correct: no task runs after disablePlugin.
- Tracking: https://app.notion.com/p/3e572084219f81e38f10d827d4433d42
- `legacy-1_8_8`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- `paper-26_2`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- `spigot-1_16_5`: `delayedQueuedBeforeDisable` `true` → `false`, `pendingBeforeDisable` `5` → `1`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"taskIds":"{enable-async=16}","disableThread":"Folia Region Scheduler Thread #1"}`

### lecithin-dispatch-off · lifecycle.task_outlives_scheduling_context — EXPECTED_FOLIA_DIFFERENCE

repeating tasks keep running after the player whose callback scheduled them has left (tasks belong to the plugin)

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `alive.async-chat-ctx` `true` → `false`, `alive.enable-sync` `true` → `false`, `alive.region-ctx` `true` → `false`, `async-chat-ctx.scheduleException` `none` → `UnsupportedOperationException`, `region-ctx.scheduleException` `none` → `UnsupportedOperationException`, `victimEnableErrors` `none` → `{enable-sync=UnsupportedOperationException, enable-delayed-long=UnsupportedOperationException}`
- `paper-26_2`: `alive.async-chat-ctx` `true` → `false`, `alive.enable-sync` `true` → `false`, `alive.region-ctx` `true` → `false`, `async-chat-ctx.scheduleException` `none` → `UnsupportedOperationException`, `region-ctx.scheduleException` `none` → `UnsupportedOperationException`, `victimEnableErrors` `none` → `{enable-sync=UnsupportedOperationException, enable-delayed-long=UnsupportedOperationException}`
- `spigot-1_16_5`: `alive.async-chat-ctx` `true` → `false`, `alive.enable-sync` `true` → `false`, `alive.region-ctx` `true` → `false`, `async-chat-ctx.scheduleException` `none` → `UnsupportedOperationException`, `region-ctx.scheduleException` `none` → `UnsupportedOperationException`, `victimEnableErrors` `none` → `{enable-sync=UnsupportedOperationException, enable-delayed-long=UnsupportedOperationException}`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"async-chat-ctx.scheduleThread":"Async Chat Thread - #1","async-chat-ctx.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CfxVictim-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=1, period=1) is not supported under regionised threading","region-ctx.scheduleThread":"Folia Region Scheduler Thread #0","region-ctx.scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CfxVictim-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=1, period=1) is not supported under regionised threading","runsIn1500ms.enable-sync":0,"runsIn1500ms.enable-async":30,"runsIn1500ms.region-ctx":0,"runsIn1500ms.async-chat-ctx":0}`

### lecithin-dispatch-off · sched.bukkit_async_then_legacy_sync — EXPECTED_FOLIA_DIFFERENCE

a Bukkit async task started from a console command can hop back with runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `innerScheduleException` `none` → `UnsupportedOperationException`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`
- `paper-26_2`: `innerScheduleException` `none` → `UnsupportedOperationException`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`
- `spigot-1_16_5`: `innerScheduleException` `none` → `UnsupportedOperationException`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"innerMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","thread":null}`

### lecithin-dispatch-off · sched.bukkitrunnable_timer_self_cancel — EXPECTED_FOLIA_DIFFERENCE

BukkitRunnable.runTaskTimer(plugin, 1, 1) that calls cancel() on its 3rd run runs exactly 3 times

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (BukkitRunnable.runTaskTimer, delay=1, period=1) is not supported under regionised threading","thread":null}`

### lecithin-dispatch-off · sched.call_sync_method_from_bukkit_async — EXPECTED_FOLIA_DIFFERENCE

callSyncMethod from a Bukkit async task returns the callable's value, computed on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `callablePrimary` `true` → `not-run`, `exception` `none` → `UnsupportedOperationException`, `value` `42` → `not-run`
- `paper-26_2`: `callablePrimary` `true` → `not-run`, `exception` `none` → `UnsupportedOperationException`, `value` `42` → `not-run`
- `spigot-1_16_5`: `callablePrimary` `true` → `not-run`, `exception` `none` → `UnsupportedOperationException`, `value` `42` → `not-run`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"message":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.callSyncMethod, delay=0, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · sched.consumer_timer_self_cancel — EXPECTED_FOLIA_DIFFERENCE

runTaskTimer(plugin, Consumer<BukkitTask>, 1, 1) whose body cancels the handed task on run 3 runs exactly 3 times

Fixtures: `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `paper-26_2`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`paper-26_2` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-paper-26_2 (CraftScheduler.runTaskTimer, delay=1, period=1) is not supported under regionised threading"}`

### lecithin-dispatch-off · sched.legacy_cancel_before_first_run — EXPECTED_FOLIA_DIFFERENCE

a delayed task cancelled by id before it is due never runs; isQueued is true before and false after

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `queuedAfterCancel` `false` → `∅`, `queuedBeforeCancel` `true` → `∅`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `queuedAfterCancel` `false` → `∅`, `queuedBeforeCancel` `true` → `∅`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `queuedAfterCancel` `false` → `∅`, `queuedBeforeCancel` `true` → `∅`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncDelayedTask, delay=40, period=-1) is not supported under regionised threading"}`

### lecithin-dispatch-off · sched.legacy_delayed_from_onenable — EXPECTED_FOLIA_DIFFERENCE

scheduleSyncDelayedTask(plugin, task) during onEnable runs once on the main thread after startup

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncDelayedTask, delay=0, period=-1) is not supported under regionised threading","thread":null}`

### lecithin-dispatch-off · sched.legacy_repeating_cancel_by_id — EXPECTED_FOLIA_DIFFERENCE

scheduleSyncRepeatingTask(period 2) stops at exactly 5 runs after cancelTask(id), and isQueued(id) turns false

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `primaryThread` `true` → `not-run`, `queuedAfterCancel` `false` → `not-run`, `runs` `5` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `primaryThread` `true` → `not-run`, `queuedAfterCancel` `false` → `not-run`, `runs` `5` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `primaryThread` `true` → `not-run`, `queuedAfterCancel` `false` → `not-run`, `runs` `5` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=2, period=2) is not supported under regionised threading","thread":null}`

### lecithin-dispatch-off · sched.legacy_repeating_from_onenable — EXPECTED_FOLIA_DIFFERENCE

scheduleSyncRepeatingTask during onEnable repeats until cancelTask(id) stops it after 3 runs

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `primaryThread` `true` → `not-run`, `runs` `3` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncRepeatingTask, delay=1, period=1) is not supported under regionised threading","thread":null}`

### lecithin-dispatch-off · sched.legacy_sync_delayed — EXPECTED_FOLIA_DIFFERENCE

scheduleSyncDelayedTask(plugin, task, 10) from a console command runs once, on the main thread, no earlier than ~10 ticks

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `delayRespected` `true` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `paper-26_2`: `delayRespected` `true` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- `spigot-1_16_5`: `delayRespected` `true` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.scheduleSyncDelayedTask, delay=10, period=-1) is not supported under regionised threading","elapsedMs":-1,"thread":null}`

### lecithin-dispatch-off · tebex.bukkit_async_poll_to_legacy_sync — EXPECTED_FOLIA_DIFFERENCE

a Bukkit async task (started from a console command) calls runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **EXPECTED_FOLIA_DIFFERENCE**: Diagnostic variant: compat-config.caller-context-dispatch=false restores stock Folia rejection of legacy sync scheduling. Its differences show what the compatibility layer changes; they are not regressions of the default configuration.
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleThread":"Craft Scheduler Thread - 4 - CompatFixture-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin-dispatch-off · tebex.completable_future_callback_to_legacy_sync — KNOWN_FAIL

a CompletableFuture.thenAccept stage on a plugin-owned executor calls runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): Same as the foreign-thread case: a CompletableFuture stage on a plugin-owned executor carries no provenance; runTask is rejected.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleThread":"cfx-http-client-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin-dispatch-off · tebex.foreign_thread_callback_to_legacy_sync — KNOWN_FAIL

a callback on a plugin-created thread (HTTP client) calls runTask; the task runs once on the main thread and can dispatch a console command

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): runTask from a plugin-created thread has no region/entity/global owner, so LecithinCallerContextDispatch falls through to stock Folia rejection (UnsupportedOperationException). Correct fail-closed behaviour under the current provenance model; closing the gap needs a server-control / plugin execution lane, not unknown->Global.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleThread":"cfx-http-callback-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

### lecithin-dispatch-off · tebex.nested_async_to_legacy_sync — KNOWN_FAIL

an async task that starts another async task, which calls runTask; the task runs once on the main thread

Fixtures: `legacy-1_8_8`, `paper-26_2`, `spigot-1_16_5`

- Registered as **KNOWN_FAIL** (FAIL_CLOSED_NO_PROVENANCE): LecithinExecutionProvenance bounds async inheritance to one hop; the second async task has no owner, so its runTask is rejected. The one-hop variant (tebex.bukkit_async_poll_to_legacy_sync) passes.
- Tracking: https://app.notion.com/p/3e272084219f8172af63f9c46d5c0f1a
- `legacy-1_8_8`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `paper-26_2`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- `spigot-1_16_5`: `dispatchException` `none` → `not-run`, `dispatchReturned` `true` → `not-run`, `messageException` `none` → `not-run`, `primaryThread` `true` → `not-run`, `runs` `1` → `0`, `scheduleException` `none` → `UnsupportedOperationException`, `sinkInvoked` `true` → `false`
- diag (`legacy-1_8_8` on `lecithin-dispatch-off`): `{"scheduleThread":"Craft Scheduler Thread - 8 - CompatFixture-legacy-1_8_8","scheduleMessage":"java.lang.UnsupportedOperationException: sync Bukkit scheduler task from CompatFixture-legacy-1_8_8 (CraftScheduler.runTask, delay=0, period=-1) is not supported under regionised threading","sinkThread":null}`

## Harness notes and server log

**`paper`**

- harness: no notes

**`lecithin`**

- harness: no notes
- log `lecithinRedispatch`: 47 line(s)<br>`CompatFixture-spigot-1_16_5 SchedulerScenarios$DelayedFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-spigot-1_16_5 SchedulerScenarios$RepeatingFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-paper-26_2 SchedulerScenarios$DelayedFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-paper-26_2 SchedulerScenarios$RepeatingFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-legacy-1_8_8 SchedulerScenarios$DelayedFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-legacy-1_8_8 SchedulerScenarios$RepeatingFromOnEnable$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CfxVictim-spigot-1_16_5 VictimPlugin$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CfxVictim-paper-26_2 VictimPlugin$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CfxVictim-legacy-1_8_8 VictimPlugin$1 -> global region (scheduled during startup on the bootstrap thread)`<br>`CompatFixture-legacy-1_8_8 SchedulerScenarios$SyncDelayed$1 -> global region (the caller was the global region)`<br>`CompatFixture-legacy-1_8_8 SchedulerScenarios$RepeatingCancelById$1 -> global region (the caller was the global region)`<br>`CompatFixture-legacy-1_8_8 SchedulerScenarios$BukkitRunnableSelfCancel$1 -> global region (the caller was the global region)`

**`lecithin-dispatch-off`**

- harness: no notes

