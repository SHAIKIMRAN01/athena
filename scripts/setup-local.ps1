<#
  One-time setup for running Athena on this computer (Windows).
  Run it with scripts\setup-local.cmd. It never overwrites a value you set.

  - Writes the project's .env file (git ignores it):
      DB_OWNER_USERNAME / DB_OWNER_PASSWORD  your PostgreSQL user (postgres) and its
                                             password: used only to create the tables
                                             and the app's own role;
      DB_USERNAME / DB_PASSWORD              athena_app and a new random password: the
                                             restricted role the app connects as (it
                                             cannot change or delete the ledger or the
                                             audit log); the app creates it at startup;
      JWT_SECRET                             a new random signing key.
    The backend reads .env when it starts, so none of these is typed again.
    Docker Compose reads the same file.
  - Optionally switches on the demo users with three new random passwords.
  - Creates the databases athena and athena_test if they are missing.
  - Installs the website's packages if they are missing.

  An .env from before the restricted role (DB_PASSWORD was the postgres
  password) is upgraded in place, after asking: that password moves to
  DB_OWNER_PASSWORD and the app gets athena_app with a new password.
  Non-interactive use: ATHENA_DB_PASSWORD, ATHENA_DEMO_USERS=y|n and
  ATHENA_UPGRADE_ENV=y|n answer the questions.
#>
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$SkipDatabases,
    [switch]$SkipNpm
)
$ErrorActionPreference = 'Stop'

$envFile = Join-Path $Root '.env'
$example = Join-Path $Root '.env.example'
$appRole = 'athena_app'

function New-RandomBytes([int]$Count) {
    $buffer = New-Object byte[] $Count
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return , $buffer
}

function New-RandomText([int]$Length) {
    # Letters and digits only: nothing to escape in .env, a URL or a shell.
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    return -join ((New-RandomBytes $Length) | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function New-DemoPassword {
    # 19 characters: letters and digits, always with at least one of each.
    return 'Demo' + (New-RandomText 14) + '7'
}

# The value of an active KEY=value line, or $null.
function Get-EnvValue([string[]]$Lines, [string]$Key) {
    foreach ($line in $Lines) {
        if ($line -like "$Key=*") { return $line.Substring($Key.Length + 1) }
    }
    return $null
}

# Sets each key on its active line, or adds the line at the end.
function Set-EnvValues([string[]]$Lines, [Collections.IDictionary]$Values) {
    $seen = @{}
    [string[]]$result = @(foreach ($line in $Lines) {
            $out = $line
            foreach ($key in $Values.Keys) {
                if ($line -like "$key=*") { $out = "$key=$($Values[$key])"; $seen[$key] = $true }
            }
            $out
        })
    $missing = @($Values.Keys | Where-Object { -not $seen.ContainsKey($_) })
    if ($missing.Count -gt 0) {
        $result += ''
        $result += '# Added by scripts\setup-local.cmd'
        foreach ($key in $missing) { $result += "$key=$($Values[$key])" }
    }
    return , $result
}

function Save-EnvFile([string[]]$Lines) {
    # Written next to .env first, then swapped in, so .env is never half-written.
    $temporary = "$envFile.new"
    [IO.File]::WriteAllLines($temporary, $Lines)  # UTF-8 without a byte-order mark
    Move-Item -LiteralPath $temporary -Destination $envFile -Force
}

# ------------------------------------------------------------ existing .env
if (Test-Path $envFile) {
    $lines = [IO.File]::ReadAllLines($envFile)
    if (Get-EnvValue $lines 'DB_OWNER_USERNAME') {
        Write-Host ".env is already set up: $envFile"
        Write-Host 'Nothing was changed. To edit a value, open it with: notepad .env'
        exit 0
    }

    $ownerPassword = Get-EnvValue $lines 'DB_PASSWORD'
    $owner = Get-EnvValue $lines 'DB_USERNAME'
    if (-not $owner) { $owner = 'postgres' }
    if (-not $ownerPassword -or $owner -eq $appRole) {
        Write-Host ".env exists but has no database owner to upgrade from: $envFile"
        Write-Host 'Nothing was changed. Fill in DB_OWNER_USERNAME and DB_OWNER_PASSWORD with: notepad .env'
        exit 1
    }

    $answer = $env:ATHENA_UPGRADE_ENV
    if (-not $answer) {
        Write-Host 'Your .env is from before Athena connected as its own restricted database role.'
        Write-Host "Upgrading keeps $owner as the tables' owner and gives the app the role $appRole"
        Write-Host 'with a new random password; the app creates the role at its next start.'
        $answer = Read-Host 'Upgrade .env now? [Y/n]'
    }
    if ($answer -match '^(n|no)$') {
        Write-Host 'Nothing was changed. The app keeps connecting as the owner, with full rights.'
        exit 0
    }

    $values = [ordered]@{
        'DB_OWNER_USERNAME' = $owner
        'DB_OWNER_PASSWORD' = $ownerPassword   # kept exactly as written (already escaped)
        'DB_USERNAME'       = $appRole
        'DB_PASSWORD'       = New-RandomText 32
    }
    Save-EnvFile (Set-EnvValues $lines $values)
    Write-Host "Upgraded $envFile (git ignores it): your $owner password is now DB_OWNER_PASSWORD,"
    Write-Host "and the app connects as $appRole from its next start. Start it as usual: scripts\start-local.cmd"
    exit 0
}

# ------------------------------------------------------------------ new .env
# The password: typed (hidden), or taken from ATHENA_DB_PASSWORD when a script runs this.
$password = $env:ATHENA_DB_PASSWORD
if (-not $password) {
    $secure = Read-Host 'Your PostgreSQL password (for the postgres user)' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
if (-not $password) {
    Write-Host 'No password entered; nothing was written.'
    exit 1
}

$answer = $env:ATHENA_DEMO_USERS
if (-not $answer) { $answer = Read-Host 'Also create the demo users (admin, auditor, alice, bob)? [y/N]' }
$demoUsers = $answer -match '^(y|yes)$'

# Java reads .env like a properties file, where \ starts an escape.
$values = [ordered]@{
    'DB_OWNER_USERNAME' = 'postgres'
    'DB_OWNER_PASSWORD' = $password.Replace('\', '\\')
    'DB_USERNAME'       = $appRole
    'DB_PASSWORD'       = New-RandomText 32
    'JWT_SECRET'        = [Convert]::ToBase64String((New-RandomBytes 48))
}
if ($demoUsers) {
    $values['DEMO_DATA_ENABLED'] = 'true'
    $values['DEMO_ADMIN_PASSWORD'] = New-DemoPassword
    $values['DEMO_AUDITOR_PASSWORD'] = New-DemoPassword
    $values['DEMO_USER_PASSWORD'] = New-DemoPassword
}

Save-EnvFile (Set-EnvValues ([IO.File]::ReadAllLines($example)) $values)
Write-Host "Saved your database password, the app's own database role and a new JWT key in $envFile (git ignores it)."

if (-not $SkipDatabases) {
    $psql = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $psql) {
        Write-Warning 'psql.exe was not found, so the databases were not created. See README, Quick start.'
    }
    else {
        $env:PGPASSWORD = $password
        try {
            foreach ($name in 'athena', 'athena_test') {
                $found = & $psql.FullName -U postgres -h localhost -tAc "SELECT 1 FROM pg_database WHERE datname = '$name'"
                if ($LASTEXITCODE -ne 0) { throw 'psql could not sign in' }
                if ("$found".Trim() -eq '1') {
                    Write-Host "Database $name already exists."
                }
                else {
                    & $psql.FullName -U postgres -h localhost -c "CREATE DATABASE $name" | Out-Null
                    if ($LASTEXITCODE -ne 0) { throw "could not create $name" }
                    Write-Host "Created database $name."
                }
            }
        }
        catch {
            Write-Warning "The databases were not checked: $($_.Exception.Message)."
            Write-Warning 'If the password was wrong, fix DB_OWNER_PASSWORD with: notepad .env  (then run this again after deleting .env, or create the databases as in README).'
        }
        finally {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        }
    }
}

if (-not $SkipNpm -and -not (Test-Path (Join-Path $Root 'frontend\node_modules'))) {
    Write-Host 'Installing the website packages (npm install), once...'
    Push-Location (Join-Path $Root 'frontend')
    try { & npm install } finally { Pop-Location }
}

Write-Host ''
if ($demoUsers) {
    Write-Host 'Demo sign-ins (also in .env):'
    Write-Host "  admin@athena.demo    $($values['DEMO_ADMIN_PASSWORD'])"
    Write-Host "  auditor@athena.demo  $($values['DEMO_AUDITOR_PASSWORD'])"
    Write-Host "  alice@athena.demo and bob@athena.demo  $($values['DEMO_USER_PASSWORD'])"
    Write-Host ''
}
Write-Host 'Done. From now on, start everything with: scripts\start-local.cmd'
Write-Host 'To create your own first administrator: scripts\bootstrap-admin.cmd'
