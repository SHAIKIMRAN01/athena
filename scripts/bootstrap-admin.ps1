<#
  Creates Athena's first administrator, only while there is none.
  Run it with scripts\bootstrap-admin.cmd, after scripts\setup-local.cmd.

  - Asks for the administrator's email, name and password (typed hidden,
    twice). They go to the app through environment variables of this one
    run: nothing is written to a file or shown on screen.
  - Runs the app once in a one-off mode with the database settings in .env:
    it prepares the database, creates the administrator and exits.
  - Does nothing if an administrator exists already, so it can never take
    over or reset one. Later administrators and auditors are added in the
    app by an administrator (Users page, "Add staff member").

  Non-interactive use: set BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD
  and optionally BOOTSTRAP_ADMIN_NAME before running it.
#>
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path (Join-Path $Root '.env')) -and -not $env:DB_URL) {
    Write-Host 'No .env file yet. Run scripts\setup-local.cmd first.'
    exit 1
}

function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$email = $env:BOOTSTRAP_ADMIN_EMAIL
if (-not $email) { $email = Read-Host 'Administrator email' }

$name = $env:BOOTSTRAP_ADMIN_NAME
if (-not $name) { $name = Read-Host 'Full name [Athena Administrator]' }
if (-not $name) { $name = 'Athena Administrator' }

$password = $env:BOOTSTRAP_ADMIN_PASSWORD
if (-not $password) {
    $password = Read-Secret 'Password (at least 12 characters, with a letter and a digit)'
    $again = Read-Secret 'The same password again'
    if ($password -cne $again) {
        Write-Host 'The two passwords differ; nothing was created.'
        exit 1
    }
}
if (-not $email -or -not $password) {
    Write-Host 'An email and a password are both needed; nothing was created.'
    exit 1
}

# For this run only: the app reads these, creates the administrator and exits.
$settings = [ordered]@{
    'BOOTSTRAP_ADMIN_EMAIL'            = $email
    'BOOTSTRAP_ADMIN_NAME'             = $name
    'BOOTSTRAP_ADMIN_PASSWORD'         = $password
    'BOOTSTRAP_ONLY'                   = 'true'
    # A free port of its own, so it never clashes with an Athena already running.
    'SERVER_PORT'                      = '0'
    'SPRING_MAIN_BANNER_MODE'          = 'off'
    'LOGGING_LEVEL_ROOT'               = 'WARN'
    'LOGGING_LEVEL_COM_ATHENA_CONFIG'  = 'INFO'
}
foreach ($key in $settings.Keys) { Set-Item "Env:$key" $settings[$key] }

Write-Host 'Preparing the database and creating the administrator (about a minute)...'
Push-Location (Join-Path $Root 'backend\athena')
try {
    # Java and Maven print notices on stderr; only the exit code means failure.
    $ErrorActionPreference = 'Continue'
    # Devtools' restarter is off for this run: it would hide a failed start
    # behind exit code 0.
    & .\mvnw.cmd -q spring-boot:run '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false'
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
    foreach ($key in $settings.Keys) { Remove-Item "Env:$key" -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($code -eq 0) {
    Write-Host 'Finished. The AdminBootstrap line above says whether the administrator was created'
    Write-Host 'or one existed already (then nothing was changed). Start the app with scripts\start-local.cmd.'
}
else {
    Write-Host 'No administrator was created: the reason is in the messages above.'
}
exit $code
