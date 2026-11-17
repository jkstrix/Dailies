# Runs one scripted game run (runTestClient or runTestModded) for CI, writes its output to a log, and fails unless the
# log says DAILIES TEST PASSED. The modded game fails while loading mods about once in thirty launches, before Dailies
# runs at all; a run that ends without any DAILIES TEST line is tried once more for that reason, and only then.
param(
    [Parameter(Mandatory = $true)][string]$Task,
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][string]$GameDir
)

$extra = @()
if ($env:DAILIES_MODS_FOLDER) { $extra += "-Pdailies.modsFolder=$($env:DAILIES_MODS_FOLDER)" }
if ($env:DAILIES_TEST_MODS) { $extra += "-Pdailies.testMods=$($env:DAILIES_TEST_MODS)" }

for ($attempt = 1; $attempt -le 2; $attempt++) {
    Write-Host "== $Task, attempt $attempt"
    & ./gradlew.bat $Task --no-daemon @extra *>&1 | Tee-Object -FilePath $Log
    $text = Get-Content -Raw -ErrorAction SilentlyContinue $Log
    if ($text -match 'DAILIES TEST PASSED') {
        Write-Host "== $Task passed"
        exit 0
    }
    if ($text -match 'DAILIES TEST FAILED[^\r\n]*') {
        Write-Host "== $Task failed: $($Matches[0])"
        exit 1
    }
    Write-Host "== $Task ended without a verdict (the game may have failed while loading mods)"
}
Write-Host "== $Task ended twice without a verdict"
exit 1
