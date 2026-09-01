[CmdletBinding()]
param(
    [Parameter(Mandatory=$false)]
    [switch]$Clean
)

Set-Location $PSScriptRoot

Write-Host "========================================" -ForegroundColor Magenta
Write-Host "   開始執行 4G Router Android APK 編譯" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta
Write-Host ""

Write-Host "開始使用 Gradle 打包 APK (Debug版)..." -ForegroundColor Cyan

# 全自動動態檢測並設定有效的 JAVA_HOME (適用於任何電腦)
$jdkPath = $env:JAVA_HOME
if (-not ($jdkPath -and (Test-Path "$jdkPath\bin\javac.exe"))) {
    # 1. 嘗試從系統 PATH 中的 javac.exe 指令位置反推
    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) {
        $possibleJdk = Split-Path -Parent (Split-Path -Parent $javacCmd.Source)
        if (Test-Path "$possibleJdk\bin\javac.exe") {
            $jdkPath = $possibleJdk
        }
    }
    
    # 2. 若 PATH 無 javac，則動態掃描常見 JDK / JBR 安裝根目錄
    if (-not $jdkPath) {
        $searchRoots = @(
            "C:\Program Files\Java",
            "C:\Program Files\Microsoft",
            "C:\Program Files\Eclipse Adoptium",
            "C:\Program Files\Amazon Corretto",
            "C:\Program Files\Zulu",
            "C:\Program Files\Android\Android Studio\jbr",
            "$env:LOCALAPPDATA\Programs\Java"
        )
        foreach ($root in $searchRoots) {
            if (Test-Path "$root\bin\javac.exe") {
                $jdkPath = $root
                break
            }
            if (Test-Path $root) {
                $foundDir = Get-ChildItem -Path $root -ErrorAction SilentlyContinue | Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Select-Object -First 1 -ExpandProperty FullName
                if ($foundDir) {
                    $jdkPath = $foundDir
                    break
                }
            }
        }
    }
}

if ($jdkPath -and (Test-Path "$jdkPath\bin\javac.exe")) {
    $env:JAVA_HOME = $jdkPath
    Write-Host "使用 JDK: $env:JAVA_HOME" -ForegroundColor Green
} else {
    Write-Warning "未找到有效的 JDK，將嘗試使用預設環境變數。"
}

# 自動檢測並設定有效的 ANDROID_HOME (Android SDK)
$sdkPath = $env:ANDROID_HOME
if (-not ($sdkPath -and (Test-Path $sdkPath))) {
    $sdkCandidates = @(
        "C:\Android\Sdk",
        "$env:LOCALAPPDATA\Android\Sdk"
    )
    foreach ($cand in $sdkCandidates) {
        if ($cand -and (Test-Path $cand)) {
            $sdkPath = $cand
            break
        }
    }
}

if ($sdkPath -and (Test-Path $sdkPath)) {
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_SDK_ROOT = $sdkPath
    Write-Host "使用 Android SDK: $env:ANDROID_HOME" -ForegroundColor Green
    
    # 確保 local.properties 存在且設定正確 sdk.dir
    $localPropsPath = "local.properties"
    $escapedSdk = $sdkPath.Replace('\', '/')
    "sdk.dir=$escapedSdk" | Out-File -FilePath $localPropsPath -Encoding utf8 -Force

    # 將 platform-tools 加入 PATH，確保 adb 指令可用
    $platformTools = Join-Path $sdkPath "platform-tools"
    if ((Test-Path $platformTools) -and ($env:PATH -notmatch [regex]::Escape($platformTools))) {
        $env:PATH = "$platformTools;$env:PATH"
    }
} else {
    Write-Warning "未找到有效的 Android SDK 路徑。"
}

# 檢查 gradlew.bat 是否存在
if (-not (Test-Path "gradlew.bat")) {
    Write-Error "找不到 gradlew.bat，請確認 Gradle Wrapper 是否正確產生。"
    exit 1
}

if ($Clean) {
    Write-Host "執行清理 (clean)..." -ForegroundColor Cyan
    .\gradlew clean
}

.\gradlew assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Error "Gradle Build Failed!"; exit 1 }

Write-Host "複製 APK 檔案..." -ForegroundColor Cyan
$apkDir = "app\build\outputs\apk\debug"

$buildGradle = Get-Content -Path "app\build.gradle.kts" -Raw -Encoding UTF8
if ($buildGradle -match 'versionName\s*=\s*"([^"]+)"') {
    $appVersion = $matches[1]
} else {
    $appVersion = "1.0.0"
}

if (Test-Path $apkDir) {
    # 複製前清理專案根目錄舊的 APK 產物
    Remove-Item "PerApp4GRouter_*.apk" -Force -ErrorAction SilentlyContinue

    $apks = Get-ChildItem -Path $apkDir -Filter "*.apk"
    $primaryApk = $null
    foreach ($apk in $apks) {
        $destApkName = "PerApp4GRouter_${appVersion}.apk"
        Copy-Item -Path $apk.FullName -Destination $destApkName -Force
        Write-Host "成功複製 APK: $destApkName" -ForegroundColor Green
        $primaryApk = $destApkName
    }

    # 嘗試安裝 APK 到連接的手機 (安全偵測，無裝置時自動略過)
    if ($primaryApk -and (Test-Path $primaryApk)) {
        try {
            $devices = & adb devices 2>$null | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" }
            if ($devices) {
                Write-Host "偵測到已連接手機，正在嘗試安裝 APK ($primaryApk)..." -ForegroundColor Cyan
                $prevEAP = $ErrorActionPreference
                $ErrorActionPreference = "Continue"
                $installOutput = & adb install -r $primaryApk 2>&1
                $adbCode = $LASTEXITCODE
                $ErrorActionPreference = $prevEAP

                if ($adbCode -eq 0) {
                    Write-Host "安裝成功！你現在可以打開手機查看 App 了。" -ForegroundColor Green
                } else {
                    Write-Host ($installOutput -join "`n") -ForegroundColor DarkGray
                    if ("$installOutput" -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                        Write-Warning "偵測到手機上已有不同簽名之舊版 App，正在嘗試卸載舊版並重新安裝..."
                        & adb uninstall com.tw.perapp4grouter 2>$null
                        & adb install -r $primaryApk 2>$null
                    } elseif ("$installOutput" -match "INSTALL_FAILED_USER_RESTRICTED") {
                        Write-Warning "手機端攔截安裝！請解鎖手機螢幕並在跳出的提示點擊「允許安裝」，或至『開發人員選項』開啟『USB 安裝』。"
                    } else {
                        Write-Warning "自動安裝未成功，可手動傳送至手機或確認螢幕是否允許安裝。"
                    }
                }
            } else {
                Write-Host "ℹ️ 目前未連接 Android 手機/模擬器，已自動略過安裝步驟。" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "ℹ️ 略過手機自動安裝。" -ForegroundColor DarkGray
        }
    }
} else {
    Write-Warning "未找到編譯出的 APK 目錄 ($apkDir)"
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "   🎉 APK 建置任務完成！" -ForegroundColor Magenta
$apkResults = Get-ChildItem -Path . -Filter "PerApp4GRouter_*.apk" -ErrorAction SilentlyContinue
foreach ($apk in $apkResults) {
    Write-Host "   APK: $pwd\$($apk.Name)" -ForegroundColor Green
}
Write-Host "========================================" -ForegroundColor Magenta
