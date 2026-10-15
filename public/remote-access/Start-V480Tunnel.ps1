[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')]
    [string]$TabletAddress,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9_][a-zA-Z0-9_.-]*$')]
    [string]$TermuxUser,

    [ValidateRange(1024, 65535)]
    [int]$LocalPort = 8332
)

$ErrorActionPreference = 'Stop'
$sshPath = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
if (-not (Test-Path -LiteralPath $sshPath)) {
    throw 'No se encontro el cliente OpenSSH de Windows.'
}

$sshArguments = @(
    '-4', '-p', '8022', '-N', '-T',
    '-L', "localhost:${LocalPort}:localhost:8332",
    '-o', 'StrictHostKeyChecking=ask',
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3',
    "${TermuxUser}@${TabletAddress}"
)

Write-Host 'En la primera conexion, verificar la huella contra la propia tablet.'
Write-Host "RPC local para Sparrow: localhost:$LocalPort. Mantener esta ventana abierta."
& $sshPath @sshArguments
exit $LASTEXITCODE
