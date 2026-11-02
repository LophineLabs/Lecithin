# Feature Patch Continue 工作流

本文说明 ComfreyWeight 1.0.5 的 feature-patch continue 在 Lecithin 里怎么用。路径和任务名都以当前 Lecithin 仓库为准；任务名有疑问时，用 `.\gradlew.bat tasks --all` 和 `.\gradlew.bat :lecithin-server:tasks --all` 核对。

Feature patch 按顺序逐张用 `git am` 应用。某一张冲突时，apply 会回滚这一张、记录可恢复状态并停下，再由 `continue` 组任务接手。File patch（`sources/`、`files/`、`resources/`）不走这套流程。

和 1.0.2 的区别：以前失败后生成仓库停在 `git am` 中途，用 `git am --continue` 接着做，再 rebuild。现在没有进行中的 `git am`，`git am --continue` 和 `git am --show-current-patch` 都用不了，失败的 patch 直接看 `features/` 里的文件。

命令均在 **Lecithin 主仓库根目录** 执行。示例用 PowerShell；Linux/macOS 把 `.\gradlew.bat` 换成 `./gradlew`。

## 先看这几条：会丢东西的操作

| 操作 | 后果 |
| --- | --- |
| 对中断的层跑 `rebuild…`（`rebuildAllServerPatches` 也算） | 主仓库里失败的 patch 和所有剩余 patch 被删除，删除还会被 `git add` 进主仓库的 index。任务本身显示成功。之后 continue 会因为找不到文件而失败。 |
| 对中断的层跑 `apply…`（`applyAllPatches` 也算） | 生成仓库重置，手工改动和中断状态一起消失，只能从头再来。 |
| Continue 成功后没导出就重跑 `applyAllPatches` | 解决结果只在生成仓库的提交里，apply 一重置就没了，同一张 patch 会再次失败。 |
| 一次 apply 失败后跑 `rebuildAllServerPatches`，即使 `listContinuable` 是空的 | 这次没轮到的层没有状态文件，树还是旧的。聚合 rebuild 会把旧树导出，上游更新时尤其危险。`listContinuable` 为空只代表没有中断，不代表每层都应用过。 |
| 工作区干净时跑 `continue…` | 不产生提交，等于丢弃失败的 patch，下一次 rebuild 时它就从主仓库消失。任务本身显示成功。 |
| 还有层没手工解决就跑 `continueAll` | 没解决的层工作区是干净的，同样被当作丢弃处理，任务同样显示成功。 |
| 工作区里有临时文件或残留的冲突标记时跑 `continue…` | `git add --all` 不挑文件也不检查内容，这些都会进入 patch。被 `.gitignore` 忽略的文件除外。 |

误删的 patch 可以从主仓库恢复：`git restore --staged --worktree -- <patch 目录>`，前提是这些 patch 已经提交过。这条命令会把该目录还原到 `HEAD`，未提交的 patch 修改也会一起还原。

## Lecithin 里哪些层有 continue

Lecithin 的继承链是 `Paper → Folia → Lophine → Lecithin`，可写的 feature-patch 层如下。

| 层 | 编辑的生成仓库 | Patch 目录 | Continue | Drop 当前失败项 |
| --- | --- | --- | --- | --- |
| Minecraft Java | `lecithin-server/src/minecraft/java/` | `lecithin-server/minecraft-patches/features/` | `:lecithin-server:continueMinecraftFeaturePatches` | `:lecithin-server:continueDropCurrentMinecraftFeaturePatches` |
| Paper 服务端 | `paper-server/` | `lecithin-server/paper-patches/features/` | `:lecithin-server:continuePaperServerFeaturePatches` | `:lecithin-server:continueDropCurrentPaperServerFeaturePatches` |
| Folia 服务端 | `folia-server/` | `lecithin-server/folia-patches/features/` | `:lecithin-server:continueFoliaServerFeaturePatches` | `:lecithin-server:continueDropCurrentFoliaServerFeaturePatches` |
| Lophine 服务端 | `lophine-server/` | `lecithin-server/lophine-patches/features/` | `:lecithin-server:continueLophineServerFeaturePatches` | `:lecithin-server:continueDropCurrentLophineServerFeaturePatches` |
| Paper API | `paper-api/` | `lecithin-api/paper-patches/features/` | `:continuePaperApiFeaturePatches` | `:continueDropCurrentPaperApiFeaturePatches` |
| Folia API | `folia-api/` | `lecithin-api/folia-patches/features/` | `:continueFoliaApiFeaturePatches` | `:continueDropCurrentFoliaApiFeaturePatches` |
| Lophine API | `lophine-api/` | `lecithin-api/lophine-patches/features/` | `:continueLophineApiFeaturePatches` | `:continueDropCurrentLophineApiFeaturePatches` |
| Paper Checkstyle | `paper-checkstyle/` | `lecithin-checkstyle/paper-patches/features/` | `:continuePaperCheckstyleFeaturePatches` | `:continueDropCurrentPaperCheckstyleFeaturePatches` |
| Checkstyle 配置 | `.checkstyle/` | `lecithin-checkstyle/config-patches/features/` | `:continuePaperCheckstyleConfigFeaturePatches` | `:continueDropCurrentPaperCheckstyleConfigFeaturePatches` |

各层的 apply 任务把名字里的 `continue` 换成 `apply`，例如 `:lecithin-server:applyLophineServerFeaturePatches`、`:applyPaperApiFeaturePatches`。

服务端四层注册在 `:lecithin-server`（`paperweight-core`，`activeFork = lecithin`），其余注册在根项目（`paperweight-patcher`）。某层的 feature 目录可以是空的，任务依然存在，只是没有可应用的 `.patch`。

下面这些没有 continue：

- 只读的上游层（Lophine 自己的 patch、Folia、Paper）。它们由嵌套构建在上游检出里应用，用的是上游自己 `gradle.properties` 里的 weight 版本。失败说明 `lophineRef` 这个提交本身有问题，不是在 Lecithin 里续做的情况。
- 主仓库直接维护的 `lecithin-server/src/main/`、`lecithin-server/src/test/`。注意 `lophine-server/`、`lophine-api/` 在 Lecithin 里是生成仓库，和 Lophine 仓库里的含义相反。
- 单文件 patch：各模块的 `build.gradle.kts.patch`，用 `:rebuildLophineSingleFilePatches` 导出。
- File patch。冲突走 rejects、`apply…FilePatchesFuzzy` 或 `applyOrMove…FilePatches`（Minecraft 层是 `:lecithin-server:applyOrMoveMinecraftSourcePatches`）。

## 一次失败在工作区里长什么样

以 Lophine 服务端层为例。`applyAllPatches` 或 `:lecithin-server:applyLophineServerFeaturePatches` 在 `git am --3way` 失败后会：

1. 退出 `am`（必要时清掉 `.git/rebase-apply`、`.git/am`）。
2. `git reset --hard HEAD` 并 `git clean -fd`。`lophine-server/` 回到上一张成功 patch 的干净提交。
3. 写入 continue 状态。
4. 打印失败的 patch 文件名，以及：

   ```text
   ***     ./gradlew continueLophineServerFeaturePatches
   ***   Do not commit; the continue task commits the current working tree
   ***   using the original patch metadata.
   ```

5. 抛出 `PaperweightException`，整次 apply 停下。

提示里的任务名不带模块前缀。从根目录用它也能跑，因为 Gradle 会到子项目里找同名任务，但建议用上表带前缀的名字。

## 状态文件

每个生成仓库各存一份，位置是 `<生成仓库>/.git/paperweight-feature-patch-continue.json`，例如 `lophine-server/.git/paperweight-feature-patch-continue.json`。同目录还有 `.git/patch-apply-failed`（内容为 `1`）。有 JSON 才算“被中断”。

```json
{
  "failedPatch": "0006-continue-native-removal-of-unowned-players-at-their-.patch",
  "remainingPatches": ["0007-discard-packets-to-disconnected-players-instead-of-f.patch"]
}
```

- `failedPatch`：当前失败、需要人工落地的文件名（相对该层 `features/`）。
- `remainingPatches`：失败项之后尚未应用的文件名。

Apply 开始或成功结束时会清掉它们。Continue 在提交（或丢弃）失败项之后、应用剩余 patch 之前也会清掉，剩余 patch 再次失败会写入新状态。

## 任务怎么用

Gradle group 均为 `continue`。任务不跟踪状态，每次请求都会跑。

| 任务 | 行为 |
| --- | --- |
| `continue…FeaturePatches` | 工作区有改动则用失败 patch 的元数据提交，再应用剩余 patch。工作区干净则跳过提交，相当于丢弃。该层没有中断状态时任务失败，提示先跑 apply。 |
| `continueDropCurrent…FeaturePatches` | 丢弃失败 patch：`reset --hard HEAD` + `clean -fd`，不提交，再应用剩余 patch。 |
| `:lecithin-server:listContinuable` | 列出有中断的服务端层：失败 patch、剩余数量、建议命令。没有中断时打印 `No interrupted feature patch applies`。 |
| `:lecithin-server:continueAll` | 按 Minecraft → Folia → Lophine → Paper 依次处理有中断的服务端层，没有中断的跳过，结束后执行 `listContinuable`。 |

`listContinuable` 和 `continueAll` 只看服务端四层，也只认 JSON。根项目没有注册它们，从根目录不带前缀运行时，Gradle 找到的是 `:lecithin-server` 上的任务。API 和 Checkstyle 层的中断要自己看状态文件，再跑根项目对应的 continue 任务。

`continueAll` 走的是“提交后继续”，不是 drop 变体。但它和单层 continue 一样，遇到干净的工作区就跳过提交，所以只能在所有中断的服务端层都解决之后用。某一层的剩余 patch 再次失败时它会抛错并停下，后面的层不再处理，`listContinuable` 仍会打印当时的中断情况。它不调用各层的 Gradle 任务，而是直接对每层跑同一套逻辑。

失败提示通常不会提到 drop 变体，但有一个例外：`continueDropCurrent…` 应用剩余 patch 时如果又有一张失败，提示里的任务名就是 `continueDropCurrent…`。照抄会把这张新失败的 patch 也丢掉，要保留它就手工解决后改跑 `continue…`。

## 操作步骤

根项目的 `applyAllPatches` 先应用根项目的层（API、Checkstyle、单文件 patch），再用第二次 Gradle 调用执行 `:lecithin-server:applyAllServerPatches`。所以根项目的层失败时，服务端的层根本还没开始。

1. 从主仓库根目录跑 apply：

   ```powershell
   .\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
   ```

   或只跑一层，例如：

   ```powershell
   .\gradlew.bat :lecithin-server:applyLophineServerFeaturePatches --console=plain --stacktrace --no-daemon
   ```

2. 失败后看日志里的 patch 文件名和任务名。确认服务端有哪些层中断：

   ```powershell
   .\gradlew.bat :lecithin-server:listContinuable --console=plain --no-daemon
   ```

3. 确认进的是生成仓库而不是主仓库，再把失败 patch 的改动落地，保持未提交。想拿到冲突标记而不是从零改，可以让 git 重放这张 patch：

   ```powershell
   git -C lophine-server rev-parse --show-toplevel
   git -C lophine-server apply --3way ../lecithin-server/lophine-patches/features/0006-….patch
   ```

   解决冲突后检查一遍。`git diff --check` 能查出残留的冲突标记：

   ```powershell
   git -C lophine-server status --short
   git -C lophine-server diff HEAD --check
   ```

4. 跑该层 continue：

   ```powershell
   .\gradlew.bat :lecithin-server:continueLophineServerFeaturePatches --console=plain --stacktrace --no-daemon
   ```

   服务端多层都中断、且**都已手工解决**时可以用 `:lecithin-server:continueAll`。API 层例如 `:continuePaperApiFeaturePatches`。

5. 确认这张 patch 不再需要（比如上游已经做了同样的修改）时，改跑对应的 `continueDropCurrent…`。工作区回到失败前的提交，这张 patch 不进入当前这次 apply，下一次 rebuild 时它会从 patch 目录消失，不需要手工删除。还想保留这张 patch 的话，就不要 rebuild。

6. Continue 成功后该仓库的 JSON 消失。这一层后面又有一张失败就重复第 2 到 5 步。

7. 这一层没有中断之后，**马上用这一层自己的任务导出**。解决冲突的结果在这之前只存在于生成仓库的提交里：

   ```powershell
   .\gradlew.bat :lecithin-server:rebuildLophineServerFeaturePatches --console=plain --no-daemon
   ```

   单层 rebuild 只动这一层的 patch 目录，其它层还在中断也不受影响。

8. 再跑一次 `applyAllPatches`，让这次没轮到的层也应用上。还有失败就回到第 2 步。整次 apply 干净通过之后，才可以用 `rebuildAllServerPatches` 这类聚合任务。

## Continue 内部步骤

`FeaturePatchOperations.continueApplyingPatches`：

1. 检查 Git、仓库、patch 目录和 JSON 状态。空闲且 `skipIfIdle` 时直接返回（仅 `continueAll`），否则报错。
2. `git am --quit`。
3. 校验 `failedPatch` 在该层 `features/` 中是普通文件。
4. `dropCurrent`：硬重置并清理工作区。否则：工作区有改动就 `git add --all`，用原 patch 的 `From`、`Date`、`Subject` 和正文生成提交（author 与 committer 相同）；干净则跳过提交并打日志。
5. 校验每条 `remainingPatches` 都是普通文件。缺文件时任务在这里失败，此时第 4 步的提交已经做了。
6. 删除 JSON 与 `patch-apply-failed`。
7. 没有剩余 patch 则结束。
8. 对剩余列表逐张走 `git am --3way --ignore-whitespace --committer-date-is-author-date`。失败则回滚、写入新状态、提示对应任务。

提交说明来自 patch 头：去掉 `[PATCH…]` 前缀的 Subject，加上 `---` 或 `diff --git` 之前的正文。缺少 author email 或 subject 会直接失败。

## 为什么 rebuild 会删 patch

`RebuildGitPatches` 只有看到 `.git/rebase-apply` 才做部分保存；否则它删除整个 patch 目录，再用 `git format-patch file` 导出 `file..HEAD`。1.0.5 失败时已经回滚并清掉 `rebase-apply`，生成仓库里只有失败之前的提交，于是失败项和剩余项都不在导出范围内。

## 实现入口（ComfreyWeight）

| 职责 | 位置 |
| --- | --- |
| Apply、continue、回滚、状态读写 | `paperweight-core/.../core/tasks/patching/ApplyFeaturePatches.kt`（`FeaturePatchOperations`） |
| `continueAll`、`listContinuable` | `paperweight-core/.../comfreyweight/core/tasks/patching/ContinueAllPatches.kt` |
| 聚合注册与 target 收集 | `paperweight-core/.../core/taskcontainers/CoreTasks.kt` |
| Minecraft continue 任务名与仓库 | `paperweight-core/.../core/taskcontainers/MinecraftPatchingTasks.kt` |
| 上游 directory patch set continue 任务 | `paperweight-core/.../core/taskcontainers/PatchingTasks.kt` |
| 上游 continue target 转发 | `paperweight-core/.../core/taskcontainers/UpstreamConfigTasks.kt` |
| Rebuild | `paperweight-lib/.../tasks/RebuildGitPatches.kt` |

Lecithin 侧配置：

- 根项目上游（API、Checkstyle）：`build.gradle.kts` 的 `paperweight { upstreams.register("lophine") { … } }`
- 服务端 fork 与 Minecraft：`lecithin-server/build.gradle.kts.patch` 里的 `forks.register("lecithin")`，`activeFork = lecithin`
