/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale

/**
 * The inline host page for the official YouTube IFrame Player API, and the fixed command scripts
 * that drive it. Pure strings, no network host page.
 *
 * Device facts (Portal, Android 10, WebView 138): www.youtube.com is rewritten to m.youtube.com,
 * which refuses framing, so the player uses `host: https://www.youtube-nocookie.com`. The page is
 * loaded with base URL `https://<packageName>` so the HTTP Referer identifies the app (YouTube RMF),
 * and the same value is passed as the `origin` player var.
 *
 * The player fills the page with YouTube's own controls turned off (official `controls:0`, no
 * fullscreen, no annotations) because the Portal draws its own transport; nothing is drawn over it.
 */
object YtPlayerPage {
  /** Name of the `@JavascriptInterface` object the page posts events to. */
  const val BRIDGE_NAME = "portalYtPlayer"
  private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
  private const val CALL = "window.__portalYt&&window.__portalYt."

  /** `https://<packageName>`; [packageName] is validated so it is safe to embed in the page. */
  fun baseUrl(packageName: String): String {
    require(PACKAGE.matches(packageName)) { "not a package name" }
    return "https://$packageName"
  }

  val PLAY = "${CALL}play();"
  val PAUSE = "${CALL}pause();"
  val STOP = "${CALL}stop();"

  /** loadVideoById (autoplays). Null for an invalid video id. */
  fun load(videoId: String, startMs: Long): String? = videoCall("load", videoId, startMs)

  /** cueVideoById (shows the video paused). Null for an invalid video id. */
  fun cue(videoId: String, startMs: Long): String? = videoCall("cue", videoId, startMs)

  fun seek(positionMs: Long): String = "${CALL}seek(${seconds(positionMs)});"

  /** Player volume, clamped to the IFrame API's 0–100. */
  fun volume(level: Int): String = "${CALL}volume(${level.coerceIn(0, 100)});"

  private fun videoCall(name: String, videoId: String, startMs: Long): String? {
    if (!YtTrack.isValidVideoId(videoId)) return null
    return "${CALL}$name('$videoId',${seconds(startMs)});"
  }

  /** Seconds as a compact JS number literal ("12.5", "61", "0"). */
  private fun seconds(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    return if (clamped % 1_000 == 0L) (clamped / 1_000).toString()
    else String.format(Locale.ROOT, "%.3f", clamped / 1_000.0).trimEnd('0')
  }

  /** The complete host page for [origin] (from [baseUrl]; validated again here). */
  fun html(origin: String): String {
    require(origin.startsWith("https://") && PACKAGE.matches(origin.removePrefix("https://"))) {
      "origin must come from baseUrl"
    }
    return page(origin)
  }

  private fun page(origin: String): String = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
#player{position:absolute;inset:0;width:100%;height:100%}</style>
</head><body><div id="player"></div>
<script>
(function(){
var player=null,poll=null,blockTimer=null,wanted=false;
function post(o){o.v=1;try{window.$BRIDGE_NAME.postMessage(JSON.stringify(o));}catch(e){}}
function data(){try{return player.getVideoData()||{};}catch(e){return {};}}
function cut(v){return (typeof v==='string')?v.slice(0,100):'';}
function progress(){if(!player||!player.getCurrentTime)return;var d=data();
post({e:'progress',t:player.getCurrentTime()||0,d:player.getDuration()||0,id:d.video_id||'',ti:cut(d.title),au:cut(d.author)});}
function startPoll(){if(!poll)poll=setInterval(progress,1000);}
function stopPoll(){if(poll){clearInterval(poll);poll=null;}}
function armBlockCheck(){clearTimeout(blockTimer);blockTimer=setTimeout(function(){
var s=player&&player.getPlayerState?player.getPlayerState():-1;
if(wanted&&(s===-1||s===5))post({e:'autoplayBlocked'});},4000);}
function ok(){return player&&player.loadVideoById;}
window.onYouTubeIframeAPIReady=function(){
player=new YT.Player('player',{host:'https://www.youtube-nocookie.com',width:'100%',height:'100%',
playerVars:{playsinline:1,controls:0,fs:0,disablekb:1,iv_load_policy:3,rel:0,enablejsapi:1,origin:'$origin'},
events:{
onReady:function(){post({e:'ready'});},
onStateChange:function(ev){var s=ev.data;if(s===1){wanted=false;startPoll();}else{stopPoll();}
post({e:'state',s:s});progress();},
onError:function(ev){wanted=false;stopPoll();post({e:'error',c:ev.data});},
onAutoplayBlocked:function(){post({e:'autoplayBlocked'});}
}});};
window.__portalYt={
load:function(id,s){if(!ok())return false;wanted=true;player.loadVideoById({videoId:id,startSeconds:s});armBlockCheck();return true;},
cue:function(id,s){if(!ok())return false;wanted=false;player.cueVideoById({videoId:id,startSeconds:s});return true;},
play:function(){if(!ok())return false;wanted=true;player.playVideo();armBlockCheck();return true;},
pause:function(){if(!ok())return false;wanted=false;player.pauseVideo();return true;},
stop:function(){if(!ok())return false;wanted=false;stopPoll();player.stopVideo();return true;},
seek:function(s){if(!ok())return false;player.seekTo(s,true);progress();return true;},
volume:function(v){if(!ok())return false;player.setVolume(v);return true;}
};
var tag=document.createElement('script');
tag.src='https://www.youtube.com/iframe_api';
tag.onerror=function(){post({e:'apiError'});};
document.head.appendChild(tag);
})();
</script></body></html>"""
}
