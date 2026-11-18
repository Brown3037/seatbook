param([ValidateRange(1, 65535)][int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $project '.env'

function New-RandomBase64([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes)
}

# CreateNew prevents accidental replacement, even if two generators run together.
$stream = [IO.File]::Open($envFile, [IO.FileMode]::CreateNew)
$writer = New-Object IO.StreamWriter($stream)
try {
    $writer.WriteLine("MYSQL_ROOT_PASSWORD=$(New-RandomBase64 24)")
    $writer.WriteLine("SEATBOOK_DB_PASSWORD=$(New-RandomBase64 24)")
    $writer.WriteLine("SEATBOOK_JWT_SECRET=$(New-RandomBase64 32)")
    $writer.WriteLine("SEATBOOK_ADMIN_PASSWORD=$(New-RandomBase64 18)")
    $writer.WriteLine("SEATBOOK_HOST_PORT=$Port")
    $writer.WriteLine("SEATBOOK_REDIS_PASSWORD=$(New-RandomBase64 24)")
    $writer.WriteLine("SEATBOOK_RABBITMQ_PASSWORD=$(New-RandomBase64 24)")
    $writer.WriteLine("SEATBOOK_RABBITMQ_UI_PORT=15672")
} finally {
    $writer.Dispose()
}
Write-Output "Created .env. Read SEATBOOK_ADMIN_PASSWORD there to sign in as admin."
