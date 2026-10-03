# PN Anvil → PM1E LevelDB

將 **PowerNukkit (PN) 的 Anvil/MCA 世界**轉換成 **Nukkit PetteriM1 Edition (PM1E) 的 LevelDB 世界**。不是 PowerNukkitX (PNX) 轉換器，也不支援 Java Edition 世界。

目前為整理中的實驗版本。Java 轉換階段曾完成兩張實際世界共 753,582 個區塊；新的通用入口仍需在相同核心與小型世界上重新驗證。不能把歷史驗證結果視為所有來源版本的相容性保證。

## 需求

- Linux、Bash、Python 3.8 以上、Git、Maven、JDK 21 (`java` 與 `javac`)。
- 來源舊核心建置另需 JDK 8，以 `SOURCE_JAVA_HOME` 指定。
- 執行時從官方下載核心：目標預設 latest；來源固定在已驗證 commit 並編譯。本倉庫不分發核心 JAR。
- 首次驗證的來源為 PowerNukkit 1.6.1.0-PN-SNAPSHOT，commit `d2a0848f16efe1e3c53fc411c61af62d12a8c683`；目標為 PM1E build 4511。
- 現有 shell 入口為 Linux 使用；Windows 可以編輯程式，但原生 Windows 執行流程尚未完成。

## 工作目錄

```text
before/<world>/level.dat、region/*.mca   # 來源副本
after/<world>/level.dat、db/            # 輸出
runtime/batch/source.jar               # 來源核心
runtime/batch/target.jar               # 目標核心
runtime/batch/libs/*.jar               # 目標依賴
tools/                                # Java 轉換與驗證程式
scripts/                              # 通用入口
reports/                              # 執行報告與續跑標記
intermediate/                         # 暫存
backups/                              # 實體歸位前的記錄
```

請以停止寫入的來源副本操作，輸出資料庫不得被伺服器同時開啟。先用一個小型來源世界測試。空間需求會包含來源、輸出、驗證副本與暫存；來源壓縮檔大小不能直接當作輸出大小估計。

## 使用

把停止寫入的來源世界副本放入 `before/<world>`，指定 JDK 8 與 21 後：

```bash
export SOURCE_JAVA_HOME=/path/to/jdk8
export JAVA_HOME=/path/to/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
bash convert.sh MyWorld AnotherWorld
```

第一次執行自動取得核心。最新版失敗時詢問是否回退至已驗證的 build 4511；確認後保留失敗輸出並重新開始。也可使用 `bash convert.sh --verified MyWorld` 明確選擇 4511。[核心下載與驗證紀錄](docs/runtime.md)

同一指令可接續中斷的轉換，續跑不更新核心。完成區域以來源 SHA-256 與執行版本指紋驗證；來源、程式或核心改變時停止，不會悄悄混用舊結果。

入口依序執行來源盤點、精簡匯出、狀態遷移、寫入讀回、跨區塊實體歸位與完整座標稽核。空白實體 ID 保留且另列警告，不猜測實體種類。部署與正式核心載入測試不會自動執行。

轉換結果不是自動部署許可。先在獨立伺服器副本上驗證，再備份正式環境，以新的世界資料夾名稱部署。不要對運行中的 LevelDB 做複製或寫入。

## 文件

- [格式與限制](docs/limitations.md)
- [歷史驗證結果](docs/validation.md)
- [自動下載、版本紀錄與回退](docs/runtime.md)

## 授權

本工具採用 [MIT License](LICENSE)。核心與相依套件依各自授權使用，不包含在本倉庫授權範圍內。
