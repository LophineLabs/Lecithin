# Lecithin Plugin Compatibility Suite

用**真正不同年代編譯**的 Bukkit／Spigot／Paper fixture 插件，在 reference Paper 與 Lecithin 上跑同一批案例，
輸出可機讀結果與 differential summary。它回答的問題是：

- 一顆沒有 `folia-supported` 的舊插件在 Lecithin 上，觀察得到的語意是否和 Paper 一樣？
- 不一樣的地方是已登記的差異（known fail／預期的 Folia 差異），還是新的 regression？
- 某個 compat 修正有沒有真的修到 contract，而不是只讓某顆正式插件剛好不炸？

這是證據層，不是修正層：已知的核心相容缺口會以 `KNOWN_FAIL` 留在報告裡，不會為了全綠被藏起來。

## 快速開始（clean checkout）

需求：JDK 25（編譯 1.8.8／1.16.5 fixture 用 `--release 8`，modern fixture 用 `--release 25`）、第一次執行需要網路
（Paper／Lecithin jar、Mojang server jar、Maven 依賴）、`127.0.0.1:25720-25724` 沒被佔用。

```bash
# 在 repo 根目錄；compat-suite 是獨立 Gradle build，不會觸發 paperweight
./gradlew -p compat-suite fixtures            # 建出 build/fixtures/ 下各年代的 fixture + victim jar
./gradlew -p compat-suite :runner:test        # runner 的 focused 單元測試（不開 server）
./gradlew -p compat-suite runSuite            # reference Paper + Lecithin，約 5–8 分鐘
```

常用變化：

```bash
# 測本地 build 的 Lecithin，而不是 suite.properties 釘住的 CI pre-release
./gradlew -p compat-suite runSuite -Pcompat.lecithinJar=../lecithin-server/build/libs/lecithin-paperclip-26.2.local-SNAPSHOT.jar

# 加跑「同一顆 Lecithin，但把 legacy scheduler 相容層關掉」，看 compat feature ON/OFF 的差異
./gradlew -p compat-suite runSuite -Pcompat.targets=paper,lecithin,lecithin-dispatch-off

# 改了 expectations.json 之後，不重跑 server，只重新判定與產生報告
./gradlew -p compat-suite reportSuite -Pcompat.run=build/compat-runs/<run-id>
```

產物（`build/compat-runs/<run-id>/`，最新一次的 id 在 `build/compat-runs/latest.txt`）：

| 檔案 | 內容 |
|---|---|
| `summary.md` | 人讀的 differential summary：targets、fixture 年代、總覽、按 archetype 分組的結果、每個差異的欄位與登記理由 |
| `results.json` | 全部可機讀資料：每個 target 的 fixture／case record、reference 自檢、differential verdict |
| `targets/<id>/console.log` | 該 server 完整 console |
| `targets/<id>/results/*.jsonl` | fixture 原始輸出（每行一筆 JSON） |

`runSuite` 的 exit code：有 `REGRESSION`／`MISSING`／`HARNESS_ERROR`／`REFERENCE_INVALID` 或任一 target 沒開起來時失敗；
已登記的差異（`KNOWN_FAIL` 等）不讓它失敗，但一定列在報告裡。

## 結構

```
compat-suite/
├─ suite.properties            targets（jar 來源＋sha256、port、config 覆寫）、reference、protocol profile
├─ expectations.json           已登記差異（KNOWN_FAIL / EXPECTED_FOLIA_DIFFERENCE / ...）
├─ fixture-common/src/<layer>/ scenario 原始碼，按「需要的最舊 API」分層
│  ├─ base/                    Bukkit 1.8.8 API 就有的東西：harness、scheduler、Tebex/Essentials/GP、lifecycle、victim
│  ├─ pre_flattening/          只在 1.13 以前存在的 API（Material.WOOL）
│  ├─ since_1_13/              1.13 之後才有的 API（Consumer<BukkitTask> 排程 overload）
│  └─ paper_modern/            Paper 專屬 API（AsyncChatEvent）
├─ fixtures/<era>/fixture.properties   一個 API 年代：編譯用 artifact、--release、api-version、要編哪些 layer
└─ runner/                     server 啟停、無頭 26.2 客戶端、phase 驅動、differential、報告
```

### Fixture 年代

同一份 scenario 原始碼對每個年代**各自編譯**，bytecode、method descriptor、`api-version` 都是那個年代的；
`CompatFixture-<era>.jar` 啟動時回報自己的 class file major version 當證據。

| era | 編譯 API | class file | `api-version` | 用意 |
|---|---|---|---|---|
| `legacy-1_8_8` | `org.spigotmc:spigot-api:1.8.8-R0.1`（2016-02 snapshot） | 52 | 無 | 走 legacy plugin 載入與 Commodore 重寫 |
| `spigot-1_16_5` | `org.spigotmc:spigot-api:1.16.5-R0.1`（2021-06 snapshot） | 52 | `1.16` | flattening 之後、仍是 Java 8 的年代 |
| `paper-26_2` | `io.papermc.paper:paper-api:26.2.build.118-stable` | 69 | `26.2` | 現代 Paper 插件，但仍不是 Folia-aware |

所有 fixture 與 victim 都**沒有** `folia-supported`。

### 一次 run 的流程（每個 target 各跑一次）

1. 清空 instance（保留 paperclip 的 `cache/ libraries/ versions/`），寫入固定的 `server.properties`（flat、離線、
   `enforce-secure-profile=false`）、fixture＋victim jar、target 的 config 覆寫；啟動，等 `Done (`。
2. `server` phase：不需要玩家的 scheduler 案例。
3. 兩個無頭玩家 `CompatA`、`CompatB` 登入；console `tp CompatB 4096.5 -60 4096.5`，讓兩人不可能同一個 region。
4. `players` phase：Tebex 案例從 console 啟動；兩人同時聊天 `cfx ess`（async chat event）、同時送 `/cfxtrigger gp`；
   B 再送 `cfx life` 與 `/cfxtrigger life`，從自己的兩種 context 替 victim 排 repeating task。
5. B 離線；`lifecycle-liveness`（B 排的 task 還活著嗎）→ `lifecycle-disable`（disable victim，所有 task 都停了嗎）。
6. `stop`，收 `compat-results/*.jsonl` 與 console log。

每個 phase 由 fixture 自己的 watchdog 執行緒計時收尾，**不依賴被測的 Bukkit scheduler**。

### Fixture 協定

- 控制：console 指令 `cfx-<era> phase <name> [args]`；server-control 案例的落點是 `cfx-<era> sink <tag>`。
- 輸出：`<server>/compat-results/<era>.jsonl`，三種 record：`fixture`（啟用時，含 server version、class file major）、
  `case`（phase 結束時）、`phase`（started／done）。
- 每個 case record：

```json
{"type":"case","fixture":"legacy-1_8_8","case":"tebex.foreign_thread_callback_to_legacy_sync",
 "archetype":"tebex","phase":"players","status":"FAIL",
 "expected":{"runs":1,"scheduleException":"none", "...": "..."},
 "observed":{"runs":0,"scheduleException":"UnsupportedOperationException", "...": "..."},
 "diag":{"scheduleThread":"cfx-http-callback-legacy-1_8_8", "...": "..."},"error":null}
```

- `expected`：scenario 作者寫下的 Paper contract。`status` 是 fixture 自己對它的判定（`PASS`／`FAIL`／`OBSERVED`／`ERROR`）。
- `observed`：**只放在正確平台上必然穩定的值**（次數、布林、例外類別的 simple name）。differential 只比這一欄。
- `diag`：執行緒名、毫秒、例外訊息等不穩定的輔助資訊，永遠不比。

### Differential 與分類

每個 candidate 對 reference 逐欄比 `observed`：

| 分類 | 意思 |
|---|---|
| `PASS` | 每個 observed 欄位都和 reference 相同 |
| `REGRESSION` | 有欄位不同，且 `expectations.json` 沒有解釋 |
| `KNOWN_FAIL` / `EXPECTED_FOLIA_DIFFERENCE` / `LECITHIN_EXTENSION` / `UNSUPPORTED_BY_DESIGN` / `SEMANTICALLY_EQUIVALENT` | 已登記的差異；登記可以限定 `fields`，其他欄位不同仍是 `REGRESSION` |
| `UNEXPECTED_PASS` | 登記的差異已經不存在，該更新登記（通常代表修好了） |
| `MISSING` | reference 有這個 case，candidate 沒有（插件沒載入、phase 沒完成、server 掛了） |
| `HARNESS_ERROR` | scenario 程式碼自己在 candidate 上丟例外 |
| `REFERENCE_INVALID` | reference 自己不符合 scenario 寫的 Paper contract：是測試寫錯，不是平台錯 |

`expectations.json` 一筆的格式：

```json
{"target": "lecithin*", "fixture": "*", "case": "tebex.foreign_thread_callback_to_legacy_sync",
 "classification": "KNOWN_FAIL", "category": "REGRESSION",
 "fields": ["scheduleException", "runs", "primaryThread", "dispatchReturned", "sinkInvoked", "dispatchException", "messageException"],
 "reason": "為什麼不同、根因在哪", "tracking": "Notion / issue 連結"}
```

`target`／`fixture`／`case` 支援 `*`。判定時取**第一筆**符合 target／fixture／case、且 `fields` 涵蓋所有不同欄位的登記；
只涵蓋部分欄位的登記不算解釋。`"optional": true` 用於診斷 variant（例如 `lecithin-dispatch-off`）：差異出現時套用該分類，
沒出現時就是 `PASS` 而不是 `UNEXPECTED_PASS`。登記只是「承認並追蹤」，不是豁免：報告照樣列出每個欄位差異。

目前登記的 `KNOWN_FAIL` 與其根因見 `expectations.json`；26.2 的第一份基準結果在 `baselines/26.2/`。

## 新增一個 compatibility bug 的 fixture

1. **先縮成最小 execution shape**：誰觸發（console／玩家事件／async callback／startup）、在哪條執行緒、呼叫哪個
   legacy API、Paper 上觀察得到什麼。不要依賴真實插件或外部帳號。
2. **挑最舊能表達它的 layer**：只用 Bukkit 1.8.8 就有的 API → `base`；需要新 API → 對應 layer（或新增一個 layer，
   並在需要的年代的 `fixture.properties` 的 `layers` 列上它）。
3. **寫 scenario**：在 `fixture-common/src/<layer>/java/fun/bm/lecithin/compatsuite/fixture/scenario/` 加一個類別，
   並在該 layer 的 `install` 裡 `h.add(...)`。

```java
static final class MyBug extends Scenario {
    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicReference<String> failure = new AtomicReference<>();

    MyBug() {
        super("sched.my_bug_shape",            // 穩定的 case id，所有年代共用
              "scheduler",                     // 報告分組
              PLAYERS,                         // 在這個 phase 結束時收尾
              "一句話：這是 Paper 的哪個 contract");
    }

    @Override
    public void onTrigger(Harness h, Player p, String role, String trigger) {
        if (!"mybug".equals(trigger)) return;           // runner 送 /cfxtrigger mybug（見第 4 步）
        result.expect("scheduleException", Probe.NONE).expect("runs", 1);
        try {
            Bukkit.getScheduler().runTask(h.plugin, () -> runs.incrementAndGet());
            failure.set(Probe.NONE);
        } catch (Throwable t) {                         // 被測 API 丟的例外是 observation，不是 ERROR
            failure.set(Probe.exName(t));
            result.diag("message", Probe.exMessage(t));
        }
    }

    @Override
    public void finish(Harness h) {                     // watchdog 執行緒；在這裡取最終值
        result.observe("scheduleException", Probe.orNotRun(failure.get()))
              .observe("runs", runs.get());
    }
}
```

   規則：`observed` 只放穩定值；等待、逾時、取樣一律用 `h.watchdog()`，不要用 Bukkit scheduler；
   `base` layer 的程式碼必須能在 Java 8 與 Bukkit 1.8.8 API 下編譯（`fixtures` task 會替你驗證）。
4. **需要新的玩家動作**時，在 `Orchestrator#drive` 的 `players` phase 加一行（`a.command("cfxtrigger mybug")`、
   `b.chat("cfx mybug")`）。現有動作能表達就不要加。
5. **先在未修的 Lecithin 上跑出穩定 FAIL**，再決定：是 core 要修的缺口 → 在 `expectations.json` 登記 `KNOWN_FAIL`
   （附根因與追蹤連結），修好後它會變成 `UNEXPECTED_PASS`，刪掉登記即可；是設計上的差異 → 登記對應分類並寫理由。
   reference 是 `REFERENCE_INVALID` 時表示 contract 寫錯，先修 scenario。

## 新增一個 API 年代

在 `fixtures/` 下加一個目錄與 `fixture.properties`（`label`、`api`、`release`、`apiVersion`、`layers`），
`settings.gradle.kts` 會自動納入。`api` 盡量用帶時間戳的 snapshot 版本以固定內容。

## 移植到 26.3（或任何新版本）

testcase 的業務語意不需要改；需要換的只有「平台座標」：

1. `runner/src/main/resources/protocol/<版本>.properties`：新版本的 protocol number 與本客戶端用到的 packet id
   （來源：該版本的 minecraft-data `protocol.json` 或 vanilla 報告）；`suite.properties` 的 `suite.protocol`、`suite.minecraft`。
2. `suite.properties` 的 `target.paper.jar`：換成新版本的 Paper build 與 sha256。**選 Lecithin 上游 Folia 的 `paperRef`
   所在的 Paper build**，讓差異只歸因到 Folia／Lecithin，而不是 Paper 版本差。
3. `target.lecithin.jar`：26.3 的 Lecithin jar（CI release 或 `-Pcompat.lecithinJar=` 本地 build）。
4. 新增 `fixtures/paper-26_3/`（`api=io.papermc.paper:paper-api:<26.3 build>`、`apiVersion=26.3`），保留 `paper-26_2`；
   舊年代 fixture 不動——它們本來就是要在新平台上重跑的舊插件。
5. 跑 `runSuite`；和 `baselines/26.2/results.json` 逐 case 對照（同一個 fixture × case 的 `observed` 與分類），
   差異就是 port regression 證據。確認後把新結果存成 `baselines/26.3/`。

若某個新 JDK 不再支援 `--release 8`，把 1.8.8／1.16.5 年代的編譯改用 Gradle toolchain 指定較舊 JDK，
不要把它們改成用較新 release 編譯（那會失去「真的舊 bytecode」這個前提）。

## 目前的範圍與限制

- 這是第一個垂直切片：loading、legacy scheduler、Tebex／Essentials／GP 三個 archetype、task lifetime 與 disable。
  events／commands／world／entity 等 domain 尚未加入。
- reference 只有 Paper；Spigot reference target 尚未加入（`paper:` 以外的 jar 來源格式已可擴充）。
- GP 案例用「有界的停留視窗」讓並行可被確定地觀察到；它量的是「同一插件的兩個 callback 是否同時在臨界區內」，
  不是資料競態會不會剛好造成例外。Paper 上單一 main thread 使它必然為 `false`。
- 無頭客戶端只實作登入、keep-alive、傳送確認、chunk batch ack、聊天與指令；它不解析世界，
  所有判定都在 server 端由 fixture 觀察。
- 不需要、也不使用任何 Tebex／商店帳號；Tebex 的 execution shape 以合成的 HTTP callback 執行緒重現。
