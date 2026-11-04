#Requires -Version 5.1
<#
.SYNOPSIS
    一键启动 APS 整栈（PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend）。

.DESCRIPTION
    等价 Bash 脚本：scripts/stack-up.sh
    使用 deploy/docker-compose.yml（与 dev 基础设施栈隔离，工程名固定 aps-stack）。
    流程：检查 Docker CLI / 守护进程 / Compose v2 -> 准备 .env -> 检查入口端口
          -> 拉取中间件镜像 -> 构建前后端镜像并等待健康检查 -> 初始化 MinIO 附件桶。
    兼容旧版 docker-compose 的理由：沿用 scripts/dev-*.ps1 的交互与错误提示风格，
    但 compose 文件、工程名、端口检查对象均不同，因此独立为 stack-* 而非复用 dev-*。

.PARAMETER SkipBuild
    使用已有镜像，不强制重新构建前后端（镜像不存在时 compose 仍会自动构建）。

.PARAMETER SkipPull
    跳过中间件镜像拉取（离线或镜像已缓存时使用）。

.PARAMETER NoWait
    只执行 up -d，不等待健康检查（也不会自动建桶，适合快速调试）。

.EXAMPLE
    .\scripts\stack-up.ps1
.EXAMPLE
    .\scripts\stack-up.ps1 -SkipPull
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipPull,
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    foreach ($hint in $Hints) {
        Write-Host "  - $hint" -ForegroundColor Yellow
    }
    exit 1
}

function Invoke-DockerCmd {
    param([string[]]$DockerArgs, [switch]$Quiet)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) {
            & docker @DockerArgs *> $null
        }
        else {
            & docker @DockerArgs | Out-Host
        }
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Get-EnvMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in Get-Content -Path $Path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $map[$matches[1]] = $matches[2].Trim()
        }
    }
    return $map
}

function Get-EnvInt {
    param($Map, [string]$Key, [int]$Default)
    if ($Map.ContainsKey($Key) -and $Map[$Key]) {
        $parsed = 0
        if ([int]::TryParse($Map[$Key], [ref]$parsed)) { return $parsed }
    }
    return $Default
}

function Get-PortConflict {
    param([int]$Port)
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($conn) {
            $procName = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            if ($procName) {
                return "端口已被 PID=$($conn.OwningProcess) ($procName) 占用"
            }
            return "端口已被 PID=$($conn.OwningProcess) 占用"
        }
    }
    if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
        return '端口已有监听（未能获取占用进程）'
    }
    return $null
}

try {
    Write-Step '检查 Docker CLI'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithHelp '未找到 docker 命令' @(
            '安装 Docker Desktop（含 Compose v2）：https://docs.docker.com/desktop/install/windows-install/',
            '安装完成后重新打开终端再试'
        )
    }

    Write-Step '检查 Docker 守护进程'
    if ((Invoke-DockerCmd -DockerArgs @('info') -Quiet) -ne 0) {
        Stop-WithHelp 'Docker 守护进程未运行（docker info 失败）' @(
            '启动 Docker Desktop，等待鲸鱼图标变为绿色（或 WSL2 中 docker-desktop 处于 Running）',
            '若刚启动，通常需要 1~2 分钟；可稍后重试',
            '公司安全软件可能拦截 Docker 的 npipe，需加白名单'
        )
    }

    Write-Step '检查 Docker Compose v2'
    if ((Invoke-DockerCmd -DockerArgs @('compose', 'version') -Quiet) -ne 0) {
        Stop-WithHelp '未检测到 docker compose v2 插件' @(
            'Docker Desktop 自带 compose v2；Linux 请安装 docker-compose-plugin',
            '不要使用旧版 docker-compose（v1）执行本项目脚本'
        )
    }

    Write-Step '准备 .env'
    $envPath = Join-Path $repoRoot '.env'
    $envExamplePath = Join-Path $repoRoot '.env.example'
    if (-not (Test-Path $envPath)) {
        if (-not (Test-Path $envExamplePath)) {
            Stop-WithHelp '缺少 .env 与 .env.example，无法启动' @('请从版本库恢复 .env.example 后重试')
        }
        Copy-Item $envExamplePath $envPath
        Write-Warn '未找到 .env，已从 .env.example 复制；上线前务必替换所有默认密码与 JWT secret'
    }

    # 显式 --project-name：根 .env 的 COMPOSE_PROJECT_NAME=aps-dev 会覆盖 compose 顶层 name，
    # 不指定会与 dev 栈共享 project label（down --remove-orphans 有误删风险）。
    $composeArgs = @(
        'compose', '--env-file', '.env',
        '--project-name', 'aps-stack',
        '-f', 'deploy/docker-compose.yml'
    )

    Write-Step '检查入口端口占用'
    $envMap = Get-EnvMap -Path $envPath
    $frontendPort = Get-EnvInt -Map $envMap -Key 'APS_FRONTEND_PORT' -Default 80
    $backendPort = Get-EnvInt -Map $envMap -Key 'APS_BACKEND_PORT' -Default 8080
    $conflicts = @()
    foreach ($spec in @(
            @{ Name = '前端入口'; Port = $frontendPort },
            @{ Name = '后端 API'; Port = $backendPort }
        )) {
        $owner = Get-PortConflict -Port $spec.Port
        if ($owner) {
            $conflicts += "$($spec.Name) $($spec.Port) -> $owner"
        }
        else {
            Write-Host "    $($spec.Name) $($spec.Port) 可用" -ForegroundColor DarkGray
        }
    }
    if ($conflicts.Count -gt 0) {
        Stop-WithHelp '入口端口冲突，已中止启动（未改动任何运行中的容器）' ($conflicts + @(
                '在 .env 中设置 APS_FRONTEND_PORT / APS_BACKEND_PORT 换用其他端口后重试',
                '或停止占用该端口的程序（Windows 上 80 端口常被 IIS / http.sys 占用）'
            ))
    }

    if (-not $SkipPull) {
        Write-Step '拉取中间件镜像（首次执行耗时较长）'
        $pullArgs = $composeArgs + @('pull', 'postgres', 'redis', 'minio')
        if ((Invoke-DockerCmd -DockerArgs $pullArgs) -ne 0) {
            Stop-WithHelp '中间件镜像拉取失败' @(
                '国内网络请为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890',
                'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE',
                '镜像已缓存时，可加 -SkipPull 跳过拉取'
            )
        }
    }

    # 只对长期运行的服务做 --wait；minio-init 是 profiles: init 的一次性任务，启动后单独执行
    $upArgs = $composeArgs + @('up', '-d')
    if (-not $SkipBuild) {
        $upArgs += '--build'
    }
    if (-not $NoWait) {
        $upArgs += @('--wait', '--wait-timeout', '300')
    }
    $upArgs += @('postgres', 'redis', 'minio', 'aps-backend', 'aps-frontend')

    Write-Step '构建镜像、启动容器并等待健康检查'
    if ((Invoke-DockerCmd -DockerArgs $upArgs) -ne 0) {
        Stop-WithHelp '整栈启动或健康检查未通过' @(
            '查看状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps',
            '查看日志：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs postgres redis minio aps-backend aps-frontend',
            '后端启动失败优先看 Flyway 迁移日志，以及 aps-admin/Dockerfile 是否把 db/migration 打进镜像',
            '镜像拉取失败请检查 Docker Desktop 代理（http://127.0.0.1:7890）或 MINIO_IMAGE'
        )
    }

    if (-not $NoWait) {
        Write-Step '初始化 MinIO 附件桶'
        $initArgs = $composeArgs + @('--profile', 'init', 'run', '--rm', '--no-deps', '-T', 'minio-init')
        if ((Invoke-DockerCmd -DockerArgs $initArgs) -ne 0) {
            Write-Warn 'MinIO 附件桶初始化失败（不影响其他服务运行）'
            Write-Warn '可手动重试：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml --profile init run --rm minio-init'
        }
    }

    Write-Host ''
    Write-Host 'APS 整栈已启动' -ForegroundColor Green
    Write-Host "  前端入口   http://localhost:$frontendPort"
    Write-Host "  后端健康   http://localhost:$backendPort/api/v1/health"
    Write-Host '  中间件     postgres:5432 / redis:6379 / minio:9000（仅容器内网）'
    if ($NoWait) {
        Write-Host '  当前未等待健康检查，也未初始化 MinIO 桶；请查看 ps 状态后再做验收。' -ForegroundColor Yellow
    }
    Write-Host ''
    Write-Host '状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps'
    Write-Host '停止：.\scripts\stack-down.ps1            （保留数据卷）'
    Write-Host '清数据：.\scripts\stack-down.ps1 -RemoveVolumes'
}
finally {
    Pop-Location
}
