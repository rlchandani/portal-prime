/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
const root=resolve(import.meta.dirname,'..'),source=readFileSync(join(root,'provisioning/provision.sh'),'utf8');
const pkg='com.immortal.launcher.debug',component=pkg+'/com.immortal.launcher.BarWatchService';
function fn(name){const match=source.match(new RegExp('^'+name+'\\(\\) \\{[\\s\\S]*?^\\}','m'));assert.ok(match,'Missing production '+name+' function');return match[0];}
const good={permission:'    android.permission.WRITE_SECURE_SETTINGS: granted=true, flags=[]',master:'1',enabled:component,
 bound:'     Bound services:\n                     Service[label=PUBLIC-FIXTURE]\n     id='+component+'\n     Binding services:\n     Enabled services:'+component};
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
function run(values={},failed=''){
 const v={...good,...values},temp=mkdtempSync(join(tmpdir(),'immortal-access-')),trace=join(temp,'commands');const body=`PKG=${quote(pkg)}; N=''; G=''; Y=''; B=''; D='';
 ok(){ printf 'OK:%s\\n' "$1"; }; warn(){ printf 'WARN:%s\\n' "$1"; }; step(){ :; };
 a(){ printf 'COMMAND:%s\\n' "$*" >>${quote(trace)}; case "$*" in
  'shell dumpsys package ${pkg}') printf '%s\\n' ${quote(v.permission)}; ${failed==='permission'?'return 1':'return 0'};;
  'shell settings get secure accessibility_enabled') printf '%s\\n' ${quote(v.master)}; ${failed==='master'?'return 1':'return 0'};;
  'shell settings get secure enabled_accessibility_services') printf '%s\\n' ${quote(v.enabled)}; ${failed==='enabled'?'return 1':'return 0'};;
  'shell dumpsys accessibility') printf '%s\\n' ${quote(v.bound)}; ${failed==='bound'?'return 1':'return 0'};;
  'shell dumpsys activity services ${component}') printf '%s\\n' ${quote(v.activity??'No services match')}; ${failed==='activity'?'return 1':'return 0'};;
  *) return 91;; esac; }
${source.includes('calls_activity_bound() {')?fn('calls_activity_bound'):''}
${fn('check_calls_access')}
check_calls_access
`;
 try{const r=spawnSync('bash',['-c',body],{encoding:'utf8',timeout:5000});r.commands=readFileSync(trace,'utf8').split('\n').filter(Boolean);return r;}finally{rmSync(temp,{recursive:true,force:true});}
}
test('healthy grant, enabled service and bound connection report readiness',()=>{const r=run();assert.equal(r.status,0,r.stdout+r.stderr);assert.match(r.stdout,/verified|ready|connected/i)});
for(const [name,values] of [
 ['missing secure permission',{permission:'android.permission.WRITE_SECURE_SETTINGS: granted=false'}],
 ['master disabled',{master:'0'}],['service not enabled',{enabled:'com.public.other/.Other'}],
 ['wrong package same class',{enabled:'com.public.other/com.immortal.launcher.BarWatchService'}],
 ['enabled but unbound',{bound:'Bound services:\nBinding services:'+component+'\nEnabled services:'+component}],
 ['similar class',{bound:'Bound services: '+component+'Extra\nBinding services:'}],
])test(name+' does not report controls readiness',()=>{const r=run(values);assert.equal(r.status,1,r.stdout+r.stderr);assert.match(r.stdout,/setup|Reconnect|not|missing|unable|could|check/i);assert.doesNotMatch(r.stdout,/access verified|controls ready/i)});
for(const failed of ['permission','master','enabled','bound'])test(failed+' read failure cannot masquerade as a healthy value',()=>{const r=run({},failed);assert.equal(r.status,1,r.stdout+r.stderr)});
test('bound component only counts inside the bound section',()=>{const r=run({bound:'Bound services: com.public.other/.Service\nBinding services:'+component+'\nEnabled services:'+component});assert.equal(r.status,1,r.stdout+r.stderr)});
test('read-only checker neither mutates settings nor prints raw diagnostics',()=>{const r=run({permission:good.permission+'\nPRIVATE-SENTINEL',enabled:'com.public.other/.Service:'+component,bound:good.bound+'\nPRIVATE-SENTINEL'});assert.equal(r.status,0,r.stdout+r.stderr);assert.doesNotMatch(r.stdout,/PRIVATE-SENTINEL|com.public.other|enabled_accessibility_services/);const commands=r.commands;assert.ok(commands.length>=4);assert.ok(commands.every(l=>/COMMAND:shell (dumpsys (package|accessibility)|settings get secure)/.test(l)))});
test('initial setup never claims verified completion after failed access check',()=>{
 const names=['resolve_adb','wait_for_device','install_client','start_shizuku','install_apps','push_assets','grant_perms','apply_system_tweaks','disable_verifier','disable_installer_overlay','disable_ota','disable_presence','snapshot_stock','set_launcher','set_screensaver','enable_fleet','configure_boot_apps','maybe_restore_alexa'];
 const mocks=names.map(n=>n+'(){ :; };').join('\n');const body=`N='';G='';Y='';B='';D=''; PKG=${quote(pkg)}; warn(){ printf '%s\\n' "$1"; }; ok(){ printf '%s\\n' "$1"; }; a(){ :; }; sleep(){ :; }; ${mocks}\ncheck_calls_access(){ return 1; }; ${fn('do_provision')}\ndo_provision`;const r=spawnSync('bash',['-c',body],{encoding:'utf8',timeout:5000});assert.equal(r.status,0,r.stdout+r.stderr);assert.doesNotMatch(r.stdout,/Done\. Your Portal is provisioned/);assert.match(r.stdout,/attention|not.*ready|incomplete/i)
});
test('unsupported read errors still leave a useful repair path',()=>{const r=run({bound:'Error: unknown diagnostic'},'bound');assert.equal(r.status,1);assert.match(r.stdout,/Device health|device health|Reconnect controls|setup/i)});

test('short component identity also confirms the bound service',()=>{const short=pkg+'/.BarWatchService';const r=run({enabled:short,bound:'Bound services: '+short+'\nBinding services:'});assert.equal(r.status,0,r.stdout+r.stderr)});
test('AOSP boundServices block confirms the service without using enabledServices',()=>{const r=run({bound:'boundServices:{'+component+'}, bindingServices:{}, enabledServices:{}'});assert.equal(r.status,0,r.stdout+r.stderr)});
test('an AOSP enabledServices match outside the bound block does not count',()=>{const r=run({bound:'boundServices:{}, bindingServices:{}, enabledServices:{'+component+'}'});assert.equal(r.status,1,r.stdout+r.stderr)});

for (const ready of [true,false]) test('check-access dispatch is read-only and preserves '+(ready?'success':'failure')+' exit code',()=>{
 const dispatch=source.slice(source.indexOf('case "${1:-}" in'));
 assert.ok(dispatch.startsWith('case '));
 const body=`resolve_adb(){ :; }; wait_for_device(){ :; }; check_calls_access(){ printf 'CHECKED\\n'; return ${ready?0:1}; }; do_provision(){ printf 'UNEXPECTED-SETUP\\n'; exit 91; }; ${dispatch}`;
 const r=spawnSync('bash',['-c',body,'access-test','--check-access'],{encoding:'utf8',timeout:5000});
 assert.equal(r.status,ready?0:1,r.stdout+r.stderr);assert.equal(r.stdout.trim(),'CHECKED');
});

const anonymousBound='Bound services:\n Service[label=Public controls, feedbackType[FEEDBACK_GENERIC]]\nBinding services:\nEnabled services:'+component;
const activityBound=`ACTIVITY MANAGER SERVICES (dumpsys activity services)
 User 0 active services:
 * ServiceRecord{1a2b u0 ${component}}
   permission=android.permission.BIND_ACCESSIBILITY_SERVICE
   intent={act=android.accessibilityservice.AccessibilityService cmp=${component}}
   app=ProcessRecord{1a2b 123:${pkg}/10001}
   Bindings:
   * IntentBindRecord{1a2b CREATE}:
     intent={act=android.accessibilityservice.AccessibilityService cmp=${component}}
     binder=android.os.BinderProxy@1a2b
     requested=true received=true hasBound=true doRebind=false
     * Client AppBindRecord{1a2b ProcessRecord{1a2b 456:system/1000}}
       Per-process Connections:
         ConnectionRecord{1a2b u0 CR ${component}:@1a2b}
`;
test('anonymous Meta bound descriptions use the exact received live service binding',()=>{const r=run({bound:anonymousBound,activity:activityBound});assert.equal(r.status,0,r.stdout+r.stderr);assert.ok(r.commands.includes('COMMAND:shell dumpsys activity services '+component));assert.doesNotMatch(r.stdout,/ServiceRecord|BinderProxy|ProcessRecord/)});
for(const [name,activity] of [
 ['wrong component',activityBound.replaceAll(component,'com.public.other/.Service')],
 ['other user',activityBound.replaceAll(' u0 ',' u10 ')],
 ['binder not received',activityBound.replace('received=true','received=false')],
 ['not bound',activityBound.replace('hasBound=true','hasBound=false')],
 ['null binder',activityBound.replace('binder=android.os.BinderProxy@1a2b','binder=null')],
 ['wrong binding action',activityBound.replaceAll('android.accessibilityservice.AccessibilityService','com.public.other.ACTION')],
 ['dead connection',activityBound.replace(' u0 CR ',' u0 DEAD CR ')],
 ['non-system client',activityBound.replace('system/1000','com.public.other/10002')],
 ['live non-system connection cannot complete a dead system client',activityBound.replace(' u0 CR ',' u0 DEAD CR ')+`     * Client AppBindRecord{3a4b ProcessRecord{3a4b 789:com.public.other/10002}}\n       ConnectionRecord{3a4b u0 CR ${component}:@3a4b}\n`],
 ['other-user connection',activityBound.replace(' u0 CR ',' u10 CR ')],
 ['component in unrelated intent field',activityBound.replaceAll('cmp='+component,'cmp=com.public.other/.Service extra='+component)],
 ['mixed binding records',activityBound.replace('     requested=true','   * IntentBindRecord{3a4b CREATE}:\n     requested=true')],
 ['mixed service records',activityBound.replace('     requested=true',` * ServiceRecord{3a4b u0 ${component}}\n     requested=true`)],
])test('anonymous service fallback rejects '+name,()=>{const r=run({bound:anonymousBound,activity});assert.equal(r.status,1,r.stdout+r.stderr);assert.doesNotMatch(r.stdout,/access verified|controls ready/i)});
test('failed activity binding read cannot use a healthy-looking body',()=>{const r=run({bound:anonymousBound,activity:activityBound},'activity');assert.equal(r.status,1,r.stdout+r.stderr)});

test('Android 9 and 10 component-only accessibility binding is verified',()=>{const r=run({bound:anonymousBound,activity:activityBound.replaceAll('act=android.accessibilityservice.AccessibilityService ','')});assert.equal(r.status,0,r.stdout+r.stderr)});
for(const [name,activity] of [
 ['missing binding permission',activityBound.replace('   permission=android.permission.BIND_ACCESSIBILITY_SERVICE\n','')],
 ['wrong binding permission',activityBound.replace('permission=android.permission.BIND_ACCESSIBILITY_SERVICE','permission=com.public.other.PERMISSION')],
])test('component-bound fallback rejects '+name,()=>{const r=run({bound:anonymousBound,activity});assert.equal(r.status,1,r.stdout+r.stderr)});
