param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'

try {
    $toolRoot = $PSScriptRoot
    $jarFile = Join-Path $toolRoot 'pdf-markdown-webapp.jar'
    if (-not (Test-Path -LiteralPath $jarFile)) {
        $jarFile = Join-Path $toolRoot 'target\pdf-markdown-webapp-0.0.1-SNAPSHOT.jar'
    }
    if (-not (Test-Path -LiteralPath $jarFile)) { throw '找不到程序 JAR，请先解压完整运行包，或构建项目。' }
    $port = 8083
    if ($env:PDF_MARKDOWN_PORT) { $port = [int]$env:PDF_MARKDOWN_PORT }
    if ($port -lt 1024 -or $port -gt 65535) { throw '端口应为 1024 至 65535。' }
    $toolUrl = "http://localhost:$port/"
    function Test-ToolReady {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 2
            # Windows PowerShell 对无 charset 的 HTML 可能采用旧编码，使用 ASCII 标记判断。
            return $response.StatusCode -eq 200 -and $response.Content.Contains('<title>PDF ') -and $response.Content.Contains('/app.js')
        } catch { return $false }
    }
    if (Test-ToolReady) {
        Write-Host "工具已在运行：$toolUrl"
        if (-not $NoBrowser) { Start-Process $toolUrl }
        exit 0
    }

    $javaFile = $null
    if ($env:JAVA_HOME) {
        $javaCandidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $javaCandidate) { $javaFile = $javaCandidate }
    }
    if (-not $javaFile) {
        $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($javaCommand) { $javaFile = $javaCommand.Source }
    }
    if (-not $javaFile) {
        foreach ($vendor in @('Microsoft', 'Eclipse Adoptium', 'Java')) {
            $vendorRoot = Join-Path $env:ProgramFiles $vendor
            if (-not (Test-Path -LiteralPath $vendorRoot)) { continue }
            foreach ($directory in (Get-ChildItem -LiteralPath $vendorRoot -Directory | Sort-Object Name -Descending)) {
                $javaCandidate = Join-Path $directory.FullName 'bin\java.exe'
                if (Test-Path -LiteralPath $javaCandidate) { $javaFile = $javaCandidate; break }
            }
            if ($javaFile) { break }
        }
    }
    if (-not $javaFile) { throw '未找到 Java。请安装 JDK 17 或更高版本，并配置 JAVA_HOME 或 PATH。' }

    # 运行包自带模型时优先使用，仍允许通过环境变量指定其他模型。
    foreach ($model in @(
        @('PDF_OCR_DETECTION_MODEL', 'PP-OCRv6_det_small.onnx'),
        @('PDF_OCR_RECOGNITION_MODEL', 'PP-OCRv6_rec_small.onnx'),
        @('PDF_OCR_DICTIONARY', 'ppocr_keys_v1.txt')
    )) {
        $modelPath = Join-Path (Join-Path $toolRoot 'models') $model[1]
        if (-not [Environment]::GetEnvironmentVariable($model[0]) -and (Test-Path -LiteralPath $modelPath)) {
            [Environment]::SetEnvironmentVariable($model[0], $modelPath, 'Process')
        }
    }
    $runtimeDir = Join-Path $toolRoot 'data'
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    $stdout = Join-Path $runtimeDir 'app.stdout.log'
    $stderr = Join-Path $runtimeDir 'app.stderr.log'
    $toolProcess = Start-Process -FilePath $javaFile -ArgumentList @('-jar', ('"{0}"' -f $jarFile)) -WorkingDirectory $toolRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $toolProcess.Id | Set-Content -LiteralPath (Join-Path $runtimeDir 'app.pid') -Encoding ASCII
    Write-Host '正在启动 PDF 转 Markdown 工具…'
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $toolProcess.Refresh()
        if ($toolProcess.HasExited) { throw "程序启动失败，请查看 $stdout 和 $stderr" }
        if (Test-ToolReady) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "等待启动超时，请查看 $stdout 和 $stderr" }
    Write-Host "启动完成：$toolUrl"
    Write-Host '转换结果保存在 data/conversions；停止服务请双击 Stop.cmd。'
    if (-not $NoBrowser) { Start-Process $toolUrl }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
