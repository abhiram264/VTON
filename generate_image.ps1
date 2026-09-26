<#
.SYNOPSIS
    Generate images via Surplus API using qwen-edit-uncensored by default.

.EXAMPLE
    .\generate_image.ps1 -Prompt "A model wearing a silk jacket" -OutputFile "jacket.png"
    .\generate_image.ps1 "A portrait of an athlete in gym gear"
#>

param (
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Prompt,

    [Parameter(Mandatory = $false, Position = 1)]
    [string]$OutputFile = "",

    [Parameter(Mandatory = $false)]
    [string]$Model = "qwen-edit-uncensored"
)

if (-not $OutputFile) {
    $OutputFile = "generated_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + ".png"
}

$apiKey = [System.Environment]::GetEnvironmentVariable('SURPLUS_API_KEY', 'User')
if (-not $apiKey) { $apiKey = [System.Environment]::GetEnvironmentVariable('SURPLUS_API_KEY', 'Machine') }
if (-not $apiKey) { $apiKey = $env:SURPLUS_API_KEY }

if (-not $apiKey) {
    Write-Error "SURPLUS_API_KEY environment variable is not set."
    exit 1
}

$endpoint = "https://api.surplusintelligence.ai/v1/images/generations"
$headers = @{
    "Authorization" = "Bearer $apiKey"
    "Content-Type"  = "application/json"
}

$bodyObj = @{
    model  = $Model
    prompt = $Prompt
    n      = 1
    size   = "1024x1024"
}
$bodyJson = $bodyObj | ConvertTo-Json

Write-Host "[+] Submitting prompt to $Model..." -ForegroundColor Cyan
Write-Host "[+] Prompt: '$Prompt'"

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
function Test-ImageBytes {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -eq 0) { return $false }
    $head = $Bytes[0..([Math]::Min($Bytes.Length - 1, 11))]
    $hex = ($head | ForEach-Object { $_.ToString("X2") }) -join ""
    return $hex -match "^(89504E470D0A1A0A|FFD8FF|474946383[79]61|52494646.{8}57454250|424D)"
}


try {
    $res = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($bodyJson))
    $stopwatch.Stop()
    $elapsed = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)

    if ($res.data -and $res.data[0].b64_json) {
        $bytes = [System.Convert]::FromBase64String($res.data[0].b64_json)
        if (-not (Test-ImageBytes $bytes)) {
            Write-Error "[-] API response did not contain a recognized image."
            exit 1
        }
        $fullPath = Join-Path (Get-Location).Path $OutputFile
        [System.IO.File]::WriteAllBytes($fullPath, $bytes)
        $byteCount = $bytes.Length
        Write-Host "[OK] Success! Image saved to: $OutputFile ($byteCount bytes in $elapsed s)" -ForegroundColor Green
    } elseif ($res.data -and $res.data[0].url) {
        $client = New-Object System.Net.WebClient
        try {
            $bytes = $client.DownloadData($res.data[0].url)
        } finally {
            $client.Dispose()
        }
        if (-not (Test-ImageBytes $bytes)) {
            Write-Error "[-] Downloaded response did not contain a recognized image."
            exit 1
        }
        $fullPath = Join-Path (Get-Location).Path $OutputFile
        [System.IO.File]::WriteAllBytes($fullPath, $bytes)
        Write-Host "[OK] Success! Image downloaded to: $OutputFile in $elapsed s" -ForegroundColor Green
    } elseif ($res.error) {
        Write-Error "[-] API Rejected / Error: $($res.error.message)"
        exit 1
    } else {
        Write-Error "[-] Response did not contain image data."
        Write-Host ($res | ConvertTo-Json -Depth 4)
        exit 1
    }
} catch {
    $stream = $_.Exception.Response.GetResponseStream()
    if ($stream) {
        $reader = New-Object System.IO.StreamReader($stream)
        $errBody = $reader.ReadToEnd()
        Write-Host "[-] Server error response: $errBody" -ForegroundColor Red
    } else {
        Write-Error "[-] Request failed: $_"
    }
    exit 1
}
