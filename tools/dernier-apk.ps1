# Télécharge le dernier APK compilé avec succès par la CI, vérifie sa somme et l'installe (adb).
# Usage : .\tools\dernier-apk.ps1            (installe)
#         .\tools\dernier-apk.ps1 -NoInstall (télécharge seulement)
param(
    [string]$Repo = "matteovaccari/paranoid",
    [switch]$NoInstall
)
$ErrorActionPreference = "Stop"
$id = gh run list -R $Repo --workflow android.yml --branch main --status success --limit 1 --json databaseId -q '.[0].databaseId'
if (-not $id) { throw "Aucune compilation Android réussie trouvée." }
$dir = Join-Path $env:TEMP "paranoid-apk-$id"
if (-not (Test-Path $dir)) { gh run download $id -R $Repo -n paranoid-apk -D $dir | Out-Null }
$apk = Get-ChildItem $dir -Filter *.apk | Select-Object -First 1
$attendu = (Get-Content (Join-Path $dir "SHA256SUMS")).Split(" ")[0].ToUpper()
$obtenu = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash
if ($attendu -ne $obtenu) { throw "Somme SHA-256 incorrecte pour $($apk.Name)" }
Write-Host "APK : $($apk.FullName) (SHA-256 vérifiée)"
if (-not $NoInstall) { adb install -r $apk.FullName }
