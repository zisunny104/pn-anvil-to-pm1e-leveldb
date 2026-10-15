# PR draft: Fix leaf persistence and decay flag handling

Status: prepared locally; not submitted. No production core or world has been modified.

## English

### Problem

Persistent leaves loaded from a valid palette state can decay. On PM1E project build 4511 (`release/1a973042fb91e3ef9cf2e249245fd534069f008e`), the mapping resolves an oak leaf with `persistent_bit=1, update_bit=0` to legacy ID 18, metadata 8. `BlockLeaves.isPersistent()` checks bit 4 and returns false, while `isCheckDecay()` checks bit 8 and returns true. A random block update then removes an isolated leaf without supporting logs, even though its persisted state explicitly requests persistence.

The base implementation also writes these two flags to the opposite positions. Separately, `setPersistent()` in azalea, cherry and mangrove leaves clears bit 1 instead of bit 2. This clears the update flag and can fail to clear persistence.

### Change

- Align `BlockLeaves` getters and setters with the current legacy mapper: persistence uses `0x08`; the update/decay-check flag uses `0x04`.
- Clear only the persistence bit (`~2`) in the three specialized leaf setters, preserving the update flag.
- Leave species bits and the existing natural decay logic unchanged. Flowering azalea inherits the corrected azalea setter.

### Reproduction and validation

Use the unmodified PM1E project release 4511 JAR, SHA-256 `67cec0084ed161bb558ab2c103dd15b0939a258f39a0ff5cb98e5de896a86acd`, in a fresh void-world fixture without production plugins or world data. Resolve registered leaf states through `BlockStateMapping`, construct the corresponding blocks, and compare persistence plus setter results with the original palette properties.

| Check | PM1E 4511 | Isolated candidate |
|---|---:|---:|
| Persistence mismatches across 40 registered leaf states | 12 | 0 |
| Setter mismatches across 80 flag-toggle cases | 60 | 0 |
| Persistent oak leaf after random update | Air (ID 0) | Leaves (ID 18) |

The candidate was compiled against the PM1E release JAR with Java 8 bytecode and loaded only in the isolated test process. Ordinary ice melts under block light 15; packed and blue ice remain. These three controls pass before and after the change.

Reproduction source and runner: `tools/ProbeBehavior.java`, `tools/BehaviorLauncher.java`, `scripts/test-behavior.py` in the converter project. Exit code 2 indicates detected leaf discrepancies. The candidate returns 0.

This does not establish that older releases have the same mapper mismatch, or that these flags explain client `Block` disconnects. Those require separate investigation. No world-file rewrite, network-version change, or pane/fence workaround is included in this proposal.

## 中文對照

### 問題

正確 palette 狀態中的永久保留樹葉仍可能腐爛。PM1E 專案發布的 4511 的映射把 `persistent_bit=1、update_bit=0` 的橡木樹葉轉成 legacy ID 18、metadata 8，但 `BlockLeaves.isPersistent()` 檢查 bit 4，回傳 false；`isCheckDecay()` 檢查 bit 8，回傳 true。沒有樹幹支撐的獨立樹葉，在隨機更新後會被移除，與儲存狀態不符。

基底實作的兩個 setter 也寫入相反的位元位置。另外，杜鵑、櫻花與紅樹林樹葉的 `setPersistent()` 清除了 bit 1，而不是 bit 2，導致更新旗標被清除，且可能無法取消永久保留。

### 修改

- 將基底樹葉的 getter／setter 對齊目前 mapper：永久保留使用 `0x08`，等待腐爛檢查使用 `0x04`。
- 三種特殊樹葉的 setter 改用 `~2`，只清除永久保留位元，保留更新旗標。
- 保留樹種位元與原本的自然腐爛流程；開花杜鵑繼承修正後的杜鵑實作。

### 驗證

使用未修改的PM1E 專案發布的 4511 JAR，在沒有正式插件與地圖資料的獨立虛空世界測試。40 種葉片狀態的永久保留判讀錯誤由 12 降為 0；80 個旗標切換案例的錯誤由 60 降為 0。永久保留的橡木樹葉在隨機更新後，由原版的消失改為保留。

修正候選只在測試程序載入，沒有安裝到正式伺服器。普通冰在方塊光 15 下融化，冰磚與藍冰不融化的對照測試，修正前後均通過。

歷史版本只有旗標程式碼比對，尚未逐版測試 mapper，因此不宣稱所有舊版本都有同一問題。這項修正也尚未證明與客戶端的 `Block` 斷線有關；世界檔案、版本標示與玻璃片／柵欄問題不納入這份 PR。
