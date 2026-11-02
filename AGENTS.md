# Instructions for Coding Agents

These instructions apply throughout the Lecithin repository. Read the relevant parts of the
contribution guide ([English](docs/CONTRIBUTING_EN.md), [简体中文](docs/CONTRIBUTING.md),
[繁體中文](docs/CONTRIBUTING_ZH_TW.md)) before changing code; where they disagree with this file on
tasks or workflow, follow this file. Inspect the current checkout and Gradle task definitions when a
documented path or task does not match the working tree.

## Project and direction

Lecithin is a Minecraft server fork: `Paper → Folia → Lophine → Lecithin`. Its goal is that most
existing Paper, Spigot, and Bukkit plugins work on region threading. It gets there by fixing the
existing API, and it does not set out to add new plugin APIs.

Paper has one main thread, and the Bukkit API quietly assumes it. Folia breaks that assumption in
three ways, each with a different answer:

| Shape | What the plugin sees | The right answer |
| --- | --- | --- |
| Refused | An exception from an API Paper always answered | Ask whether this thread may do it, and do it when it may |
| Silent | The API returns, but the event never fires | Fire it at a point that already has what the event needs |
| Misleading | The API answers, and the answer is wrong | Answer at the scope the API's name promises |

Misleading is the worst of the three, because nothing reports it. Plugins that do not declare Folia
support run on the Legacy Paper Runtime
(`lecithin-server/src/main/java/fun/bm/lecithin/compat/legacy/`); read its javadoc before changing
how such plugins are entered or scheduled.

## Toolchain and upstream

Use the checked-in Gradle wrapper and JDK 25: `.\gradlew.bat` on Windows, `./gradlew` on Linux and
macOS. Keep paths and commands portable.

`gradle.properties` records `mcVersion`, `lophineRef`, and `weightVersion`. `lophineRef` is the only
upstream pin; the Folia and Paper revisions follow from it, so compare upstream behavior at those
revisions. Do not update these values as incidental cleanup.
`.github/workflows/auto-update-upstream.yml` proposes `lophineRef` updates through a PR.

The Gradle plugin is ComfreyWeight, a paperweight fork. When a patch task surprises you, read its
source at `weightVersion` rather than guessing.

## Locate the persistent editing target

Tracked Lecithin sources live in `lecithin-server/src/main/` and `lecithin-server/src/test/`; edit
them directly. Everything else is generated, and a change made only in a generated tree does not
persist.

| Layer | Generated repository | Persistent patches | Export feature patches |
| --- | --- | --- | --- |
| Minecraft Java | `lecithin-server/src/minecraft/java/` | `lecithin-server/minecraft-patches/{sources,features}/` | `:lecithin-server:rebuildMinecraftFeaturePatches` |
| Paper server | `paper-server/` | `lecithin-server/paper-patches/{files,features}/` | `:lecithin-server:rebuildPaperServerFeaturePatches` |
| Folia server | `folia-server/` | `lecithin-server/folia-patches/{files,features}/` | `:lecithin-server:rebuildFoliaServerFeaturePatches` |
| Lophine server | `lophine-server/` | `lecithin-server/lophine-patches/{files,features}/` | `:lecithin-server:rebuildLophineServerFeaturePatches` |
| Paper API | `paper-api/` | `lecithin-api/paper-patches/{files,features}/` | `:rebuildPaperApiFeaturePatches` |
| Folia API | `folia-api/` | `lecithin-api/folia-patches/{files,features}/` | `:rebuildFoliaApiFeaturePatches` |
| Lophine API | `lophine-api/` | `lecithin-api/lophine-patches/{files,features}/` | `:rebuildLophineApiFeaturePatches` |

Minecraft resources use `lecithin-server/minecraft-patches/resources/`, checkstyle uses
`lecithin-checkstyle/{paper,config}-patches/`, and module build scripts use
`lecithin-*/build.gradle.kts.patch`, exported with `:rebuildLophineSingleFilePatches`. Use
`tasks --all` and `:lecithin-server:tasks --all` to find the rest, and avoid abbreviated task names.

Every generated repository has its own Git history, and `file..HEAD` in it is that layer's feature
patches. Run `git rev-parse --show-toplevel` before editing history or committing.

`.gitignore` does not cover all generated output. `folia-api/`, `folia-server/`, `lophine-api/`,
`lophine-server/`, `lecithin-server/src/minecraft/`, and the optional `*.settings.gradle.kts` files
appear as untracked in the main repository, and they contain nested `.git` directories. Do not run
`git add -A` or `git add .` there; stage explicit paths.

## Patch workflow

1. Inspect the branch, HEAD, staged, unstaged, and untracked files, in the main repository and in
   the generated repository you will touch. Read the relevant source, callers, and existing patches.
2. Put new logic in tracked source where you can, with compatibility code under
   `lecithin-server/src/main/java/fun/bm/lecithin/compat/`, and let patches only add the hooks that
   call it.
3. Prepare sources with `applyAllPatches` if they are missing. Applying rebuilds the generated trees
   from tracked patches, so unexported commits and uncommitted edits there are lost.
4. Keep one feature per commit in the generated repository. For an existing feature, fold the change
   into its owning commit (`git commit --fixup`, then `git rebase -i --autosquash`) rather than
   adding a later patch to undo or repair it.
5. Export with the precise layer task. `:lecithin-server:rebuildAllServerPatches` rewrites all four
   server layers. Do not edit `.patch` files by hand; the next rebuild overwrites them.
6. Rebuild tasks `git add` their output into the main repository's index. Review every exported file
   and the index, then confirm the patches reapply.

Rebuilds renumber patches, so refer to a patch by layer and subject. Mark edits to upstream code with
`// Lecithin - <reason>` or `// Lecithin start - <reason>` and `// Lecithin end - <reason>`, and
retain upstream comments, authorship, and licenses. Explain a non-obvious fix in the class javadoc or
the patch message: what upstream does, why that is wrong, and what was measured.

## When a feature patch fails to apply

ComfreyWeight rolls the failed patch back and records the interruption; there is no `git am` session
to resume. The full procedure is in
[docs/continue-feature-patches.md](docs/continue-feature-patches.md). These rules keep patches from
being lost:

- Check for interruptions with `:lecithin-server:listContinuable`. It covers server layers only; API
  and checkstyle layers have their continue tasks on the root project.
- Land the failed patch as **uncommitted** changes in the generated repository, then run that layer's
  `continue…FeaturePatches` task. It commits with the original patch metadata and applies the rest.
- **Do not run a `rebuild…` task that covers an interrupted layer**, including
  `rebuildAllServerPatches`. It reports success while deleting the failed patch and every remaining
  patch from the main repository.
- **Do not run an `apply…` task that covers an interrupted layer** unless you mean to discard your
  manual work.
- `continue…` on a clean tree reports success and drops the failed patch from this apply. So does
  `continueAll` for any interrupted layer not yet resolved by hand.
- `continue…` stages everything that is not ignored and does not look for conflict markers. Check
  `git status` and `git diff HEAD --check` in the generated repository first.
- A resolution exists only as a generated commit until it is exported. Once a layer has no
  interruption, export it with that layer's own `rebuild…FeaturePatches`, then run `applyAllPatches`
  again. Use aggregate rebuilds only after a full apply has finished cleanly: an empty
  `listContinuable` does not mean every layer was applied.

## Updating Lophine

Review every commit in `<old lophineRef>..<new lophineRef>`, not only the ones near a symptom.
Applying settles textual conflicts only, and an update can apply cleanly and still regress:

- **Tick cost.** Compare region mspt before and after under the same load. A config read that gained
  a stack walk once took most of the tick, because config reads sit on entity tick paths.
- **Lifecycle.** Run a real `stop` with plugins loaded, including ones that create NPC-like entities.
  New shutdown waits have hung on work that nothing would ever run.
- **Semantic overlap.** List Lecithin patches that upstream has superseded and patches whose premise
  upstream changed. For each, decide migrate, drop, or redesign. When dropping, name the upstream
  commit that replaced it and anything it does not cover.

Earlier runtime results do not carry over to a new Minecraft version. Failures in the read-only
Lophine, Folia, or Paper layers come from the upstream ref itself and have no continue task here.

## Compatibility rules

These get a patch rejected:

- **Per-plugin conditions:** plugin names, main classes, command names, jar hashes, version ranges,
  call-site allowlists, per-plugin dispatchers, or lists an operator fills in through configuration.
  A jar-hash table silently stops matching when a plugin updates, and only a core release can repair
  it. Key on what does not change with a plugin build: execution provenance, published API contracts,
  and Folia-native APIs. If the platform's own state cannot decide the answer, fail closed and say so.
- **Removing an ownership check.** Asking the platform (`TickThread.isTickThreadFor(...)`,
  `Bukkit.isOwnedByCurrentRegion(...)`) and taking another path is fine. Work that is actually illegal
  must still fail, loudly, at the access itself.
- **Blocking cross-region or global-region waits.** Regions and the global region share a small worker
  pool, so a `join()` on the global region can deadlock through starvation.
- **A timeout over a structural hang.** It turns a deadlock into a delayed failure and hides the defect.
  A bound is acceptable only around code the core does not control, such as plugin work at shutdown;
  its expiry must log what it was waiting for and where that was registered, and saves must still run.
- **`-D` JVM properties as kill switches.** Nothing reads them, so the flag looks accepted and changes
  nothing. Kill switches are config keys.
- **"Paper does X" from memory.** Read the shipped jar with `javap -p -c`, or run the same case on both.

These are required:

- **Treat regressions as ours.** A plugin that works on Paper or on an earlier Lecithin and misbehaves
  here is this project's problem to solve with a general mechanism. "The plugin should change" and
  "upstream has the same bug" attribute a defect; they do not close it. Leaving it in the plugin takes a
  concrete technical reason.
- **Fix the contract.** A patch names the Bukkit or NMS symbol whose behavior is wrong and fixes that, so
  every plugin touching the symbol benefits.
- **Give each behavior a kill switch** in `compat-config` or `event-config`, defaulting to the compatible
  behavior. Turning it off restores upstream, so before and after can be compared with one jar.
- **Refuse attributably:** which thread, which region, and why. Never quietly return a wrong value. A
  thread that is not a tick thread at all keeps upstream's failure, because there the exception is a real
  diagnostic; a read with no ownership question, like `getTPS()`, is the exception.
- **State the divergence.** Where restored behavior cannot be exactly Paper's, say how it differs and by
  how much in the patch message and the config comment.
- **Fire restored events on the owning thread, before the state a cancel would have to undo,** where the
  platform allows it. When it does not, state the divergence. An internal reposition must not look like a
  teleport to plugins.

## Investigating plugin misbehavior

1. **Suspect a rerouted path first.** Folia reroutes many paths Paper fired events from, and the old site
   becomes dead code with no error: teleport, portal, respawn, and spawn-location events all went missing
   this way. The mirror image happens too, as with a cancelled `PlayerMoveEvent` rolled back through a real
   teleport that a second plugin can cancel.
2. **Confirm the build that actually ran,** from the log's version line. An earlier profile does not prove
   which version an incident happened on.
3. **Compare the last working version with the broken one** on that path to find the regression.
4. **A correct refusal is only the last step.** Keep tracing what put the plugin in that state. Changes
   in timing matter here: pausing regions while global tasks keep running can make a brief transient
   state last long enough for a plugin to observe it.
5. **Prove the trigger before changing global behavior** like reloads or scheduling, with a runtime stack or
   diagnostic log. Reading source only gives a hypothesis. If the premise falls, or the problem does not
   reproduce under realistic conditions, revert the fix.
6. **Read stacks before theorizing about a stall.** Top frames that keep moving mean CPU, not a deadlock; do
   not link two stalled threads without evidence. Take a thread dump before killing a hang.
7. **Separate what logs prove from what you infer,** and say which is which.

## Region threading

Follow surrounding Java style and retain annotations and contracts. For each change, identify which region
owns every entity, chunk, and mutable object, and trace scheduling and completion through unload, retirement,
and shutdown. Running asynchronously is not a thread-safety argument. Preserve plugin events, native operation
order, and persisted formats. Lessons from past fixes:

- **Worlds do not share regions.** Every `ServerLevel` has its own regionizer, so a thread ticking world A is
  never the tick thread for world B. "Is this a tick thread" and "is this a tick thread of this world" are
  different questions; confusing them leaked chunk tickets. A cross-world read has no legal thread, and a
  cross-world move is a handover that completes in a callback.
- **Parked work needs something driving it.** An `EntityScheduler` runs only while its entity is registered,
  and only a player registry retires a player's scheduler. An unregistered player-class entity that no registry
  owns will never run what was parked on it, and anything waiting on that work hangs.
- **Shutdown waits must be able to finish after a region failure.** The tick threads may already be gone.
  `/stop force` and `Bukkit.shutdown()` pass through the same waits, and `onDisable` and saves must still run.
- **Unload callbacks complete synchronously,** so they cannot be dispatched to another region without a
  cross-region wait.
- **State shared between global and regions needs safe publication,** such as copy-on-write with a volatile
  read. Touch a player's recipe book or advancements only on that player's owner.
- **Prefer signal-driven waits to timed ones.** On Windows a `parkNanos` under 1 ms can last a full timer period
  (15.6 ms by default), and a thread holding a domain keeps everyone queued behind it for that long. macOS does
  not show this, so measure on the platform it matters on.
- **Tick paths multiply cost by entity count.** Config reads there must be plain lookups. Scans over nearby
  entities need a bound: dense items that cannot merge made `ItemEntity#mergeWithNeighbours` quadratic and held a
  region for minutes. When bounding, keep the contract: merged totals, maximum stacks, and when events fire.

Explain allocation and computational cost on hot paths, and keep predictions separate from measurements.
Distinguish confirmed defects from risks, and attribute findings to Lecithin, upstream, unsupported usage, or
unknown origin, with source evidence.

## Testing on a real server

Use a separate test directory and explicit configuration. Do not accept the EULA for someone or operate an
existing server or world as an incidental step. Each of these has produced a misleading result:

- **Check the tick thread count.** With `threaded-regions.threads: -1`, Folia uses one tick thread when half the
  core count is 4 or less, and a race cannot happen on one thread. Set it to at least 4 for concurrency work.
- **Force concurrency** with a `CyclicBarrier` in the test plugin. A barrier timeout means that case did not run
  concurrently.
- **Read a cold target once, then check for leftovers.** A probe that reads twice can take the side effect of a
  failed first read for success on the second.
- **Read the oracle from the server,** on the player's own region thread or with `execute if entity`, never from
  what a client believes.
- **Regions need a real anchor.** `forceload` does not make a region tick with no player online. Confirm the region
  runs tasks, and compute cross-region targets as offsets from the player's live position, since absolute
  coordinates can end up in one region.
- **TPS only drops above 50 ms per tick,** so a 30 ms load shows nothing.
- **Watch `connection-throttle` in `bukkit.yml`.** It rejects reconnects within 4 seconds from one address, so a fast
  loop tests only the throttle and still looks like zero failures.
- **Measure shutdown with a plain `stop`** and wait for the JVM to exit. A service wrapper's timed kill hides a hang.
- **Mind console commands.** Coordinate-range selectors make a region thread query an area it does not own, and
  RCON just returns an empty string; filter by tag or type. Plugins may take over `/kill` and `/tp`, so use
  `minecraft:kill`.
- **Set a build number for runtime tests.** Some plugins parse the server version and fail on a local build's string;
  build with `BUILD_NUMBER=<n>` in the environment. That failure is not a Lecithin defect.

## Configuration and localization

Config modules live in `lecithin-server/src/main/java/fun/bm/lecithin/config/modules/`. A comment resolves
`lecithin.<config-class-name>.<key-name>.comment` from
`lecithin-server/src/main/resources/assets/lecithin/lang/<locale>.json`, so a new key needs its `@ConfigInfo` field,
a javadoc comment, and language entries. Cover loading, defaults, runtime edits, and persistence, and use
`@DoNotLoad(when = EnumLoadType.RELOAD)` for a key that cannot safely change on reload. Preserve translation
placeholders. `sortLangKeys` rewrites the order of all language files; run it only when sorting is called for.

## Validation

Choose checks that exercise the changed behavior. Typical commands from the repository root:

```powershell
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lecithin-server:compileJava --console=plain --stacktrace --no-daemon
.\gradlew.bat :lecithin-server:test --tests '*RelevantTestSuite' --console=plain --no-daemon
.\gradlew.bat createPaperclipJar --console=plain --no-daemon
git diff --check
```

Only classes named `*TestSuite` run, so name new unit tests accordingly and use a real name in the filter. CI builds
the jar but does not run these tests, so a green CI is not test evidence. Documentation-only changes need a review of
links, paths, commands, and the diff, not server compilation.

Patch application, compilation, unit tests, a clean boot, API semantics, and real gameplay are separate claims, and
none stands in for another. Record the commands actually run and their outcomes, and state which paths went untested.

## Git and communication

Preserve unrelated changes. Stage, commit, or push only when the user requests it, and publish or send project-facing
messages only when requested. Treat a push as publishing: `.github/workflows/build.yml` runs for pushes to any branch
that touch the module directories, workflows, `gradle.properties`, or the root build scripts, and it can publish a
release or pre-release according to `release` in `gradle.properties`. Logs from a real server can contain player data;
scrub them before they reach an issue or PR. Do not invent validation or speak for maintainers.

At completion, identify changed files, behavior, validation results, remaining checks, and whether the main repository
was committed or pushed. A suggested commit message is not evidence that a commit was made.
