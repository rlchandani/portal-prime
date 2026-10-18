<#
  Copyright (c) 2026 Starbright Lab.
  This source code is licensed under the MIT license found in the
  LICENSE file in the root directory of this source tree.
#>
param([string]$ProvisioningPath = (Join-Path $PSScriptRoot '../provisioning/provision.ps1'))
$ErrorActionPreference = 'Stop'
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path $ProvisioningPath).Path, [ref]$null, [ref]$errors)
if ($errors) { throw 'Provisioning script does not parse.' }
foreach ($name in @('Read-CallsAccess','Test-CallsAccessBoundService','Check-CallsAccess')) {
  $definition = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
  }, $true)
  if ($null -eq $definition) { throw "Missing production function: $name" }
  Invoke-Expression $definition.Extent.Text
}
$cfg = @{ PKG = 'com.immortal.launcher.debug' }
$component = "$($cfg['PKG'])/com.immortal.launcher.BarWatchService"
$permission = 'android.permission.WRITE_SECURE_SETTINGS: granted=true, flags=[]'
$bound = "Bound services:`n  id=$component`nBinding services:`nEnabled services:$component"
$cases = @(
  @{name='healthy'; expected=$true},
  @{name='missing permission'; expected=$false; permission='android.permission.WRITE_SECURE_SETTINGS: granted=false'},
  @{name='master disabled'; expected=$false; master='0'},
  @{name='not listed'; expected=$false; enabled='com.public.other/.Other'},
  @{name='wrong package'; expected=$false; enabled='com.public.other/com.immortal.launcher.BarWatchService'},
  @{name='enabled unbound'; expected=$false; bound="Bound services:`nBinding services:$component`nEnabled services:$component"},
  @{name='similar class'; expected=$false; bound="Bound services: ${component}Extra`nBinding services:"},
  @{name='installed only'; expected=$false; bound="Bound services:`nInstalled services:`n  id=$component"},
  @{name='outside bound'; expected=$false; bound="Bound services: com.public.other/.Service`nBinding services:$component`nEnabled services:$component"},
  @{name='read failure permission'; expected=$false; failed='shell dumpsys package com.immortal.launcher.debug'},
  @{name='read failure master'; expected=$false; failed='shell settings get secure accessibility_enabled'},
  @{name='read failure services'; expected=$false; failed='shell settings get secure enabled_accessibility_services'},
  @{name='read failure bound'; expected=$false; failed='shell dumpsys accessibility'},
  @{name='unknown diagnostic'; expected=$false; bound='Error: unknown diagnostic'},
  @{name='no raw values'; expected=$true; permission=$permission+"`nPRIVATE-SENTINEL"; bound=$bound+"`nPRIVATE-SENTINEL"},
  @{name='short component'; expected=$true; enabled="$($cfg['PKG'])/.BarWatchService"; bound="Bound services: $($cfg['PKG'])/.BarWatchService`nBinding services:"},
  @{name='AOSP inline'; expected=$true; bound="boundServices:{$component}, bindingServices:{}"}
)
$messages = New-Object 'System.Collections.Generic.List[string]'
$commands = New-Object 'System.Collections.Generic.List[string]'
function Step { }
function Ok($message) { [void]$messages.Add($message) }
function Warn($message) { [void]$messages.Add($message) }
function A {
  $key = $args -join ' '
  [void]$commands.Add($key)
  $global:LASTEXITCODE = 0
  if ($script:case.failed -eq $key) { $global:LASTEXITCODE = 1 }
  switch ($key) {
    'shell dumpsys package com.immortal.launcher.debug' { if ($script:case.ContainsKey('permission')) { $script:case.permission } else { $permission } }
    'shell settings get secure accessibility_enabled' { if ($script:case.ContainsKey('master')) { $script:case.master } else { '1' } }
    'shell settings get secure enabled_accessibility_services' { if ($script:case.ContainsKey('enabled')) { $script:case.enabled } else { $component } }
    'shell dumpsys accessibility' { if ($script:case.ContainsKey('bound')) { $script:case.bound } else { $bound } }
    default { $global:LASTEXITCODE = 91; 'UNKNOWN-COMMAND' }
  }
}
$allowed = @('shell dumpsys package com.immortal.launcher.debug','shell settings get secure accessibility_enabled',
  'shell settings get secure enabled_accessibility_services','shell dumpsys accessibility')
$failures = New-Object 'System.Collections.Generic.List[string]'
foreach ($script:case in $cases) {
  $messages.Clear(); $commands.Clear()
  $actual = Check-CallsAccess
  if ($actual -isnot [bool] -or $actual -ne $script:case.expected) { [void]$failures.Add($script:case.name) }
  if (@($commands | Where-Object { $_ -notin $allowed }).Count -gt 0) { [void]$failures.Add($script:case.name + ': unexpected command') }
  if (-not $script:case.expected -and ($messages -join "`n") -notmatch 'Reconnect controls|[Dd]evice health|setup') { [void]$failures.Add($script:case.name + ': missing recovery guidance') }
  if (($messages -join "`n") -match 'PRIVATE-SENTINEL|UNKNOWN-COMMAND|com.public.other') { [void]$failures.Add($script:case.name + ': raw output') }
}
# Execute the real command route and final summary in a child so exit codes are observable.
$accessRoute = $ast.Find({param($n)
  $n -is [System.Management.Automation.Language.IfStatementAst] -and $n.Clauses[0].Item1.Extent.Text -eq '$CheckAccess'
}, $true)
if ($null -eq $accessRoute) { throw 'Missing production CheckAccess route.' }
$summaryStart = @($ast.EndBlock.Statements | Where-Object {
  $_ -is [System.Management.Automation.Language.AssignmentStatementAst] -and $_.Left.Extent.Text -eq '$controlsReady'
})[0]
if ($null -eq $summaryStart) { throw 'Missing production initial-setup summary.' }
$sourceText = [System.IO.File]::ReadAllText((Resolve-Path $ProvisioningPath).Path)
$summaryText = $sourceText.Substring($summaryStart.Extent.StartOffset)
$integrationCases = @(
  @{name='access success exit'; route=$true; readyAfter=1; expectedExit=0; attempts=1},
  @{name='access failure exit'; route=$true; readyAfter=99; expectedExit=1; attempts=1},
  @{name='setup healthy'; route=$false; readyAfter=1; expectedExit=0; attempts=1; complete=$true},
  @{name='setup incomplete'; route=$false; readyAfter=99; expectedExit=0; attempts=3; complete=$false},
  @{name='setup warm third attempt'; route=$false; readyAfter=3; expectedExit=0; attempts=3; complete=$true}
)
$executable = (Get-Process -Id $PID).Path
foreach ($case in $integrationCases) {
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString()+'.ps1')
  $mock = @'
$ErrorActionPreference='Stop'
$CheckAccess=$true
$script:calls=0
function Wait-Device {}
function Start-Sleep {}
function Warn($text) { Write-Host $text }
function Check-CallsAccess {
 $script:calls++
 Write-Host 'CHECKED'
 return $script:calls -ge READY_AFTER
}
'@ 
  $mock = $mock.Replace('READY_AFTER',[string]$case.readyAfter)
  $body = $mock + "`n" + $(if ($case.route) {$accessRoute.Extent.Text} else {$summaryText})
  try {
    [System.IO.File]::WriteAllText($temp,$body,[System.Text.Encoding]::ASCII)
    $output = (& $executable -NoProfile -NonInteractive -File $temp 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $attempts = [regex]::Matches($output,'(?m)^CHECKED\r?$').Count
    if ($code -ne $case.expectedExit -or $attempts -ne $case.attempts) { [void]$failures.Add($case.name) }
    if (-not $case.route) {
      $complete = $output -match 'Done\. Your Portal is provisioned'
      if ($complete -ne $case.complete) { [void]$failures.Add($case.name+': completion claim') }
      if (-not $case.complete -and $output -notmatch 'Reconnect controls') { [void]$failures.Add($case.name+': recovery guidance') }
    }
  } finally { Remove-Item -LiteralPath $temp -Force }
}
[pscustomobject]@{tests=($cases.Count+$integrationCases.Count); failures=$failures.Count; failedCases=@($failures); parserPassed=$true; readOnly=$true; publicFixturesOnly=$true} | ConvertTo-Json -Compress
if ($failures.Count -gt 0) { exit 1 }
