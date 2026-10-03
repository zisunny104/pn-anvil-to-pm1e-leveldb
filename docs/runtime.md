# 核心下載與驗證紀錄

## 執行方式

```bash
# 預設：官方最新版 PM1E；失敗才詢問是否回退
bash convert.sh MyWorld

# 明確選擇歷史驗證版本
bash convert.sh --verified MyWorld

# 只準備核心
bash convert.sh --prepare-only
```

轉換途中續跑固定使用先前的核心與 SHA-256，不會途中抓最新版。完成的工作目錄也不直接升級核心：要測試新版本請使用新的工作目錄，避免改寫既有成果。

## 來源 PowerNukkit

官方 GitHub Release 沒有我們實際使用的 `1.6.1.0-PN-SNAPSHOT` commit 對應 JAR，不能下載較舊 Release 卻宣稱同樣經過驗證。

工具從官方倉庫取得固定 commit `d2a0848f16efe1e3c53fc411c61af62d12a8c683`，以 Maven 編譯 shaded JAR。需要 Git、Maven 與 JDK 8；舊核心的 Lombok 版本不直接支援用 JDK 21 編譯。以 `SOURCE_JAVA_HOME` 指定 JDK 8，轉換工具本身仍使用 JDK 21：

```bash
export SOURCE_JAVA_HOME=/path/to/jdk8
export JAVA_HOME=/path/to/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
bash convert.sh MyWorld
```

來源核心有固定 commit 的歷史驗證，重新編譯檔案不是原 VPS 的相同 binary；紀錄不會把兩者混為一談。編譯失敗就停止，不替換來源版本。來源建置略過上游測試，因此建置成功不等於上游測試通過。

## 目標 PM1E

首次執行向官方 GitHub Releases 查詢 latest，只接受唯一的 `Nukkit-PM1E.jar`。檢查官方 asset SHA-256、ZIP CRC 與必要 class，再存入 runtime 快取。官方檔案未提供 SHA-256 時停止，讓使用者決定是否回退。

已驗證回退版本：build `4511`，SHA-256：

```text
67cec0084ed161bb558ab2c103dd15b0939a258f39a0ff5cb98e5de896a86acd
```

官方發布的是 shaded JAR；不帶入其他核心版本的 libs。下載版本的實際相容性由編譯、palette、逐位置讀回與座標稽核檢查；正式伺服器載入與遊戲行為仍需獨立抽查。

## 回退

最新版下載、編譯或轉換失敗時，互動入口詢問是否使用 4511。預設不回退；非互動模式直接以失敗退出。確認後把 after、reports、intermediate 移到 backups/failed-attempt-*，從來源重新開始；不混用不同核心產生的區域。

任何錯誤都不保證能靠回退解決。來源檔案損壞、來源核心建置失敗及不支援的狀態仍會停止。

`runtime/active.json` 與 `reports/runtime.json` 記錄版本、commit、SHA-256、來源網址及時間；[歷史驗證結果](validation.md) 記錄已完成的測試範圍。核心、快取與本機執行紀錄不提交至 Git。
