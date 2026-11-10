$ErrorActionPreference = 'Stop'
try {
    $pidFile = Join-Path $PSScriptRoot 'data\app.pid'
    if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host '没有找到本运行包启动的服务。'; exit 0 }
    $toolProcessId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $toolProcessId"
    if ($processInfo) {
        $jarFile = Join-Path $PSScriptRoot 'pdf-markdown-webapp.jar'
        if (-not (Test-Path -LiteralPath $jarFile)) {
            $jarFile = Join-Path $PSScriptRoot 'target\pdf-markdown-webapp-0.0.1-SNAPSHOT.jar'
        }
        # 防止 PID 被复用后误停其他程序。
        if ($processInfo.Name -ne 'java.exe' -or -not $processInfo.CommandLine -or $processInfo.CommandLine.IndexOf($jarFile, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw '进程与本工具不匹配，已取消停止操作。'
        }
        Stop-Process -Id $toolProcessId
        Write-Host 'PDF 转 Markdown 服务已停止。'
    } else { Write-Host '服务已退出。' }
    Remove-Item -LiteralPath $pidFile
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
