# 一键构建 5 个镜像（镜像层写入 Docker 的 D 盘数据目录）
# 用法：pwsh deploy/docker/build-all.ps1 -Tag 1.0.0
param([string]$Tag = "1.0.0", [string]$Prefix = "skqa")

$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $root

Write-Host "==> 1/3 打包 Java（跳过测试）" -ForegroundColor Cyan
mvn -B -q -DskipTests package

$jarMap = @{
  "gateway"        = "gateway\target\gateway-0.0.1-SNAPSHOT.jar"
  "doc-service"    = "doc-service\target\doc-service-0.0.1-SNAPSHOT.jar"
  "search-service" = "search-service\target\search-service-0.0.1-SNAPSHOT.jar"
  "ai-service"     = "ai-service\target\ai-service-0.0.1-SNAPSHOT.jar"
}

Write-Host "==> 2/3 构建 Java 镜像" -ForegroundColor Cyan
foreach ($svc in $jarMap.Keys) {
  $jar = $jarMap[$svc]
  if (-not (Test-Path $jar)) { throw "缺少 $jar，请先 mvn package" }
  Write-Host "    building $Prefix/$svc`:$Tag"
  docker build -f deploy/docker/Dockerfile.java -t "$Prefix/$svc`:$Tag" --build-arg JAR_FILE=$jar .
  if ($LASTEXITCODE -ne 0) { throw "$svc 构建失败" }
}

Write-Host "==> 3/3 构建 metric-service 与 web" -ForegroundColor Cyan
docker build -f deploy/docker/Dockerfile.metric -t "$Prefix/metric-service:$Tag" .
if ($LASTEXITCODE -ne 0) { throw "metric-service 构建失败" }
docker build -f deploy/docker/Dockerfile.web -t "$Prefix/web:$Tag" .
if ($LASTEXITCODE -ne 0) { throw "web 构建失败" }

Write-Host "`n完成：" -ForegroundColor Green
docker images --format "{{.Repository}}:{{.Tag}}  {{.Size}}" | Select-String "^$Prefix/"