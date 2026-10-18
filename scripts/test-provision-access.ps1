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
foreach ($name in @('Read-CallsAccess','Test-CallsAccessBoundService','Test-CallsAccessActivityService','Check-CallsAccess')) {
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
# Public full-shape fixtures: the OEM accessibility dump has labels, not IDs.
$anonymousBound = @'
Accessibility Manager state:
User state[attributes:{id=0, currentUser=true, accessibilityEnabled=true}]
  Bound services:
    Service[label=Immortal, feedbackType=FEEDBACK_GENERIC, capabilities=1, eventTypes=TYPES_ALL_MASK]
    Service[label=System back, feedbackType=FEEDBACK_GENERIC, capabilities=1, eventTypes=TYPES_ALL_MASK]
    Service[label=Media controls, feedbackType=FEEDBACK_GENERIC, capabilities=1, eventTypes=TYPES_ALL_MASK]
    Service[label=Public helper, feedbackType=FEEDBACK_GENERIC, capabilities=1, eventTypes=TYPES_ALL_MASK]
  Binding services:
  Enabled services:
    Service[label=Immortal, feedbackType=FEEDBACK_GENERIC, capabilities=1, eventTypes=TYPES_ALL_MASK]
'@
$activity = @'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{a111 u0 com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    intent={cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    packageName=com.immortal.launcher.debug
    permission=android.permission.BIND_ACCESSIBILITY_SERVICE
    processName=com.immortal.launcher.debug
    app=ProcessRecord{a222 2345:com.immortal.launcher.debug/u0a123}
    createTime=-1m lastActivity=-1m
    Bindings:
    * IntentBindRecord{a333 CREATE}:
      intent={act=android.accessibilityservice.AccessibilityService cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=android.os.BinderProxy@a444
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{a555 ProcessRecord{a666 1234:system/1000}}
        Per-process Connections:
          ConnectionRecord{a777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@a888}
    All Connections:
      ConnectionRecord{a777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@a888}
'@
$splitRecords = @'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{a111 u0 com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    packageName=com.immortal.launcher.debug
    permission=android.permission.BIND_ACCESSIBILITY_SERVICE
    Bindings:
    * IntentBindRecord{a333 CREATE}:
      intent={act=android.accessibilityservice.AccessibilityService cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=null
      requested=true received=false hasBound=false doRebind=false
  * ServiceRecord{b111 u0 com.public.other/.OtherService}
    packageName=com.public.other
    Bindings:
    * IntentBindRecord{b333 CREATE}:
      intent={act=android.accessibilityservice.AccessibilityService cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=android.os.BinderProxy@b444
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{b555 ProcessRecord{b666 1234:system/1000}}
        Per-process Connections:
          ConnectionRecord{b777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@b888}
'@
$splitBindings = @'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{a111 u0 com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    packageName=com.immortal.launcher.debug
    permission=android.permission.BIND_ACCESSIBILITY_SERVICE
    Bindings:
    * IntentBindRecord{a333 CREATE}:
      intent={act=android.accessibilityservice.AccessibilityService cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=android.os.BinderProxy@a444
      requested=true received=false hasBound=false doRebind=false
    * IntentBindRecord{b333 CREATE}:
      intent={act=com.public.other.Action cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=android.os.BinderProxy@b444
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{b555 ProcessRecord{b666 1234:system/1000}}
        Per-process Connections:
          ConnectionRecord{b777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@b888}
'@
# Native API 29 can bind by component alone. The accessibility bind permission
# identifies the service role independently of an optional Intent action.
$componentOnlyActivity = @'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{c111 u0 com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    intent={cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
    packageName=com.immortal.launcher.debug
    processName=com.immortal.launcher.debug
    permission=android.permission.BIND_ACCESSIBILITY_SERVICE
    app=ProcessRecord{c222 2345:com.immortal.launcher.debug/u0a123}
    createTime=-1m lastActivity=-1m
    Bindings:
    * IntentBindRecord{c333 CREATE}:
      intent={cmp=com.immortal.launcher.debug/com.immortal.launcher.BarWatchService}
      binder=android.os.BinderProxy@c444
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{c555 ProcessRecord{c666 1234:system/1000}}
        Per-process Connections:
          ConnectionRecord{c777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@c888}
    All Connections:
      ConnectionRecord{c777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@c888}
'@
$permissionElsewhere = $activity.Replace('    permission=android.permission.BIND_ACCESSIBILITY_SERVICE', '') + @'

  * ServiceRecord{d111 u0 com.public.other/.OtherService}
    packageName=com.public.other
    permission=android.permission.BIND_ACCESSIBILITY_SERVICE
'@
# Two clients in the same intent: system role and live connection must stay
# within one AppBindRecord, even when a later client names the same service.
$otherClient = @'
      * Client AppBindRecord{b555 ProcessRecord{b666 2345:com.public.other/u0a456}}
        Per-process Connections:
          ConnectionRecord{b777 u0 CR com.immortal.launcher.debug/com.immortal.launcher.BarWatchService:@b888}
'@
$twoClients = $activity.Replace('    All Connections:', $otherClient + "`n    All Connections:")
$splitClients = $twoClients.Replace('ConnectionRecord{a777 u0 CR ', 'ConnectionRecord{a777 u0 CR DEAD ')
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
  @{name='AOSP inline'; expected=$true; bound="boundServices:{$component}, bindingServices:{}"},
  @{name='OEM anonymous bound healthy activity'; expected=$true; bound=$anonymousBound; activity=$activity; fallback=$true},
  @{name='API29 component-only accessibility binding'; expected=$true; bound=$anonymousBound; activity=$componentOnlyActivity; fallback=$true},
  @{name='activity missing bind permission'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('    permission=android.permission.BIND_ACCESSIBILITY_SERVICE',''); fallback=$true},
  @{name='activity wrong bind permission'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('permission=android.permission.BIND_ACCESSIBILITY_SERVICE','permission=android.permission.BIND_OTHER_SERVICE'); fallback=$true},
  @{name='activity bind permission other service'; expected=$false; bound=$anonymousBound; activity=$permissionElsewhere; fallback=$true},
  @{name='OEM anonymous short alias'; expected=$true; bound=$anonymousBound; activity=$activity.Replace($component,"$($cfg['PKG'])/.BarWatchService"); fallback=$true},
  @{name='activity wrong package'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('com.immortal.launcher.debug','com.public.other'); fallback=$true},
  @{name='activity other user'; expected=$false; bound=$anonymousBound; activity=$activity.Replace(' u0 ',' u10 '); fallback=$true},
  @{name='activity received false'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('received=true','received=false'); fallback=$true},
  @{name='activity hasBound false'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('hasBound=true','hasBound=false'); fallback=$true},
  @{name='activity requested false'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('requested=true','requested=false'); fallback=$true},
  @{name='activity binder null'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('binder=android.os.BinderProxy@a444','binder=null'); fallback=$true},
  @{name='activity other action'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('act=android.accessibilityservice.AccessibilityService','act=com.public.other.Action'); fallback=$true},
  @{name='activity dead connection'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('u0 CR ','u0 CR DEAD '); fallback=$true},
  @{name='activity split service records'; expected=$false; bound=$anonymousBound; activity=$splitRecords; fallback=$true},
  @{name='activity split intent records'; expected=$false; bound=$anonymousBound; activity=$splitBindings; fallback=$true},
  @{name='activity split client records'; expected=$false; bound=$anonymousBound; activity=$splitClients; fallback=$true},
  @{name='activity healthy system then other client'; expected=$true; bound=$anonymousBound; activity=$twoClients; fallback=$true},
  @{name='activity wrong intent component'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('act=android.accessibilityservice.AccessibilityService cmp='+$component,'act=android.accessibilityservice.AccessibilityService cmp=com.public.other/.OtherService'); fallback=$true},
  @{name='activity wrong connection component'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('CR '+$component,'CR com.public.other/.OtherService'); fallback=$true},
  @{name='activity no system client'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('1234:system/1000','2345:com.public.other/u0a456'); fallback=$true},
  @{name='activity no live connection'; expected=$false; bound=$anonymousBound; activity=$activity.Replace('ConnectionRecord{','OtherRecord{'); fallback=$true},
  @{name='activity unknown format'; expected=$false; bound=$anonymousBound; activity='Public unknown format'; fallback=$true},
  @{name='activity empty read'; expected=$false; bound=$anonymousBound; activity=''; fallback=$true},
  @{name='activity read failure'; expected=$false; bound=$anonymousBound; activity=$activity; failed=('shell dumpsys activity services '+$component); fallback=$true},
  @{name='activity no raw values'; expected=$true; bound=$anonymousBound; activity=$activity+"`nPRIVATE-SENTINEL"; fallback=$true}
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
    'shell dumpsys activity services com.immortal.launcher.debug/com.immortal.launcher.BarWatchService' { if ($script:case.ContainsKey('activity')) { $script:case.activity } else { '' } }
    default { $global:LASTEXITCODE = 91; 'UNKNOWN-COMMAND' }
  }
}
$allowed = @('shell dumpsys package com.immortal.launcher.debug','shell settings get secure accessibility_enabled',
  'shell settings get secure enabled_accessibility_services','shell dumpsys accessibility',
  ('shell dumpsys activity services '+$component))
$failures = New-Object 'System.Collections.Generic.List[string]'
foreach ($script:case in $cases) {
  $messages.Clear(); $commands.Clear()
  $actual = Check-CallsAccess
  if ($actual -isnot [bool] -or $actual -ne $script:case.expected) { [void]$failures.Add($script:case.name) }
  if (@($commands | Where-Object { $_ -notin $allowed }).Count -gt 0) { [void]$failures.Add($script:case.name + ': unexpected command') }
  if ($commands.Count -gt 5) { [void]$failures.Add($script:case.name + ': too many reads') }
  if ($script:case.fallback -and $commands.Count -ne 5) { [void]$failures.Add($script:case.name + ': missing fallback read') }
  if ($script:case.fallback -and -not $script:case.expected -and ($messages -join "`n") -notmatch 'connection could not be verified') { [void]$failures.Add($script:case.name + ': unsupported disconnect claim') }
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
