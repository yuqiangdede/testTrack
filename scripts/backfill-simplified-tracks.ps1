$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "Starting simplified track backfill from $root"
mvn spring-boot:run "-Dspring-boot.run.arguments=--ship.simplify.backfill=true"
