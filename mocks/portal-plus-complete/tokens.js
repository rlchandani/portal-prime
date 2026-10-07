/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const PORTAL_SPEC = Object.freeze({
  revision: "portal-plus-complete-04",
  target: {model:"Connected Portal+",api:29,densityDpi:180,pixelWidth:2160,pixelHeight:1440,dpWidth:1920,dpHeight:1280,scale:1.125,fontScale:1},
  typography: {family:"Portal Roboto",weights:[400,500,700],title:44,body:26,secondary:22,metadata:18,lineHeight:1.35},
  sizing: {pagePadding:56,sidebarWidth:368,settingsRowMinHeight:100,primaryTarget:68,cardRadius:24,innerRadius:16,dialogRadius:28,gap:24},
  light: {background:"#f2f3f7",sidebar:"#e9edf3",surface:"#ffffff",surface2:"#e5ebf2",text:"#182c39",secondary:"#536674",divider:"#dce3eb",accent:"#176abb",selected:"#d7e7f8",green:"#288b61"},
  dark: {background:"#14161b",sidebar:"#14161b",surface:"#2b2b2b",surface2:"#363636",text:"#ffffff",secondary:"#b8b8b8",divider:"#484848",accent:"#ffffff",selected:"#414141",green:"#66c49e",error:"#ff747d"},
  motion: {durationMs:160,easing:"ease-out",idleAnimation:false,liveBlur:false},
  implementation: {branchPrefix:"codex/",branchBeforeAndroidEdits:true,preserveExistingWork:true,visualAcceptance:"Compare the installed app against the approved reference screenshots on the connected Portal+."}
});
