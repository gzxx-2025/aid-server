param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$')]
    [string]$CatalogVersion,

    [Parameter(Mandatory = $true)]
    [string]$MinimumAppVersion,

    [Parameter(Mandatory = $true)]
    [string]$SigningKeyPath,

    [string]$GoPath = 'go',

    [string]$NodePath = 'node',

    [switch]$ReplaceUnpublished
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resourceRoot = Join-Path $repositoryRoot 'aid-interface/aid-interface-main/src/main/resources/tokendance/catalog'
$outputRoot = Join-Path $PSScriptRoot 'tokendance'
$bundleName = "tokendance-catalog-$CatalogVersion.json"
$bundlePath = Join-Path $outputRoot $bundleName
$manifestPath = Join-Path $outputRoot 'latest.json'
$publishedAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')

function Read-Json([string]$Path) {
    return Get-Content -LiteralPath $Path -Encoding utf8 -Raw | ConvertFrom-Json
}

function Write-Json([string]$Path, [object]$Value) {
    $json = ($Value | ConvertTo-Json -Depth 100).Replace("`r`n", "`n")
    [IO.File]::WriteAllText($Path, $json + "`n", [Text.UTF8Encoding]::new($false))
}

if (-not (Test-Path -LiteralPath $SigningKeyPath -PathType Leaf)) {
    throw 'Model catalog signing key does not exist.'
}
if ((Test-Path -LiteralPath $bundlePath) -and -not $ReplaceUnpublished) {
    throw 'Immutable catalog bundle already exists. Use a new catalog version.'
}

$cost = Read-Json (Join-Path $resourceRoot 'cost-snapshot.json')
$capabilities = Read-Json (Join-Path $resourceRoot 'verified-capabilities.json')
$sources = Read-Json (Join-Path $resourceRoot 'official-source-index.json')
$videoEstimates = Read-Json (Join-Path $resourceRoot 'verified-video-token-estimates.json')
$compatibility = Read-Json (Join-Path $resourceRoot 'compatibility.json')
$aidPolicy = Read-Json (Join-Path $resourceRoot 'incompatibility-policy.json')
if ($aidPolicy.schemaVersion -ne 1 -or -not $aidPolicy.models) {
    throw 'AID compatibility policy is invalid.'
}
# Project deny decisions to deprecated for clients that do not understand the new policy.
foreach ($property in $aidPolicy.models.PSObject.Properties) {
    if ($property.Value.importBlocked -ne $true) { continue }
    $modelPolicy = $compatibility.models.PSObject.Properties[$property.Name]
    if (-not $modelPolicy) {
        $compatibility.models | Add-Member -NotePropertyName $property.Name -NotePropertyValue ([pscustomobject]@{})
    }
    $compatibility.models.($property.Name) | Add-Member -NotePropertyName 'deprecated' -NotePropertyValue $true -Force
    $compatibility.models.($property.Name) | Add-Member -NotePropertyName 'reason' -NotePropertyValue $property.Value.reason -Force
    foreach ($protocolPolicy in $compatibility.models.($property.Name).protocols.PSObject.Properties) {
        $protocolPolicy.Value | Add-Member -NotePropertyName 'deprecated' -NotePropertyValue $true -Force
    }
}
$compatibility.minimumAppVersion = $MinimumAppVersion

$modelIds = @($cost.models | ForEach-Object { [string]$_.modelId })
if ($modelIds.Count -eq 0 -or ($modelIds | Sort-Object -Unique).Count -ne $modelIds.Count) {
    throw 'Model catalog is empty or contains duplicate model IDs.'
}
foreach ($model in $cost.models) {
    if (-not $model.supportedProtocols -or @($model.supportedProtocols).Count -eq 0) {
        throw "Model protocol list is empty: $($model.modelId)"
    }
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$bundle = [ordered]@{
    schemaVersion = 1
    catalogId = 'tokendance'
    catalogVersion = $CatalogVersion
    publishedAt = $publishedAt
    minimumAppVersion = $MinimumAppVersion
    capabilityContractVersion = [int]$compatibility.capabilityContractVersion
    billingContractVersion = [int]$compatibility.billingContractVersion
    compatibility = $compatibility
    aidCompatibilityPolicy = $aidPolicy
    costSnapshot = $cost
    verifiedCapabilities = $capabilities
    officialSourceIndex = $sources
    verifiedVideoTokenEstimates = $videoEstimates
}
Write-Json $bundlePath $bundle

$bundleHash = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
$bundleSize = (Get-Item -LiteralPath $bundlePath).Length
$giteeBase = 'https://gitee.com/gzxx-2025/aid-server/raw/master/model-catalog/tokendance/'
$githubBase = 'https://raw.githubusercontent.com/gzxx-2025/aid-server/master/model-catalog/tokendance/'
$manifest = [ordered]@{
    schemaVersion = 1
    catalogId = 'tokendance'
    catalogVersion = $CatalogVersion
    publishedAt = $publishedAt
    minimumAppVersion = $MinimumAppVersion
    bundle = [ordered]@{
        url = $giteeBase + $bundleName
        mirrors = @($githubBase + $bundleName)
        sha256 = $bundleHash
        size = $bundleSize
    }
}
Write-Json $manifestPath $manifest

Push-Location $repositoryRoot
try {
    if (Get-Command $GoPath -ErrorAction SilentlyContinue) {
        & $GoPath run ./deploy/updater/cmd/manifest-tool -mode sign -key $SigningKeyPath -manifest $manifestPath
        if ($LASTEXITCODE -ne 0) { throw 'Model catalog signing failed.' }
        & $GoPath run ./deploy/updater/cmd/manifest-tool -mode verify -key $SigningKeyPath -manifest $manifestPath
        if ($LASTEXITCODE -ne 0) { throw 'Model catalog signature verification failed.' }
    } else {
        & $NodePath ./model-catalog/sign-manifest.mjs $SigningKeyPath $manifestPath
        if ($LASTEXITCODE -ne 0) { throw 'Model catalog signing failed.' }
    }
} finally {
    Pop-Location
}

Write-Host "TokenDance model catalog generated: $bundleName ($bundleSize bytes)"
