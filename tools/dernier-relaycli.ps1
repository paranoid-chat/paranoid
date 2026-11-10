# Télécharge le dernier relaycli Windows compilé par la CI dans le dossier courant.
param([string]$Repo = "matteovaccari/paranoid")
$ErrorActionPreference = "Stop"
$id = gh run list -R $Repo --workflow relay.yml --branch main --status success --limit 1 --json databaseId -q '.[0].databaseId'
if (-not $id) { throw "Aucune compilation du relais réussie trouvée." }
$dir = Join-Path $env:TEMP "paranoid-relais-$id"
if (-not (Test-Path $dir)) { gh run download $id -R $Repo -n relais-binaires -D $dir | Out-Null }
Copy-Item (Join-Path $dir "relaycli-windows-amd64.exe") ".\relaycli.exe" -Force
Unblock-File ".\relaycli.exe"
Write-Host "relaycli.exe mis à jour (exécution $id)."
