/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * A tiny official IFrame API page that only *cues* a public playlist and reports its video ids
 * (`cuePlaylist({list, listType:'playlist'})` then `getPlaylist()`), for Portals with no account
 * signed in. It never plays. Pure strings + a parser for what it posts back.
 */
internal object YtPlaylistPage {
  const val BRIDGE_NAME = "portalYtList"
  const val MAX_MESSAGE_CHARS = 8_192
  private val LIST_ID = Regex("[A-Za-z0-9_-]{10,64}")

  /** The page for [listId] at [origin] (`YtPlayerPage.baseUrl`); null for an id we won't embed. */
  fun html(origin: String, listId: String): String? {
    if (!LIST_ID.matches(listId) || !origin.startsWith("https://") || origin.any { it == '\'' || it == '\\' }) return null
    return """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><div id="p"></div><script>
(function(){
function post(o){o.v=1;try{window.$BRIDGE_NAME.postMessage(JSON.stringify(o));}catch(e){}}
function poll(pl,n){var ids=null;try{ids=pl.getPlaylist();}catch(e){}
if(ids&&ids.length){post({e:'ids',ids:ids.slice(0,${MusicShareQueue.MAX_TRACKS})});return;}
if(n>48){post({e:'error',c:-1});return;}setTimeout(function(){poll(pl,n+1);},250);}
window.onYouTubeIframeAPIReady=function(){
var pl=new YT.Player('p',{host:'https://www.youtube-nocookie.com',width:320,height:320,
playerVars:{autoplay:0,controls:0,enablejsapi:1,origin:'$origin'},
events:{onReady:function(){pl.cuePlaylist({list:'$listId',listType:'playlist'});poll(pl,0);},
onError:function(ev){post({e:'error',c:ev.data});}}});};
var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';
tag.onerror=function(){post({e:'error',c:-2});};document.head.appendChild(tag);
})();
</script></body></html>"""
  }

  sealed interface Message {
    data class Ids(val videoIds: List<String>) : Message
    data class Failed(val code: Int) : Message
  }

  /** Untrusted page message → valid, distinct, capped ids, or a failure; null for junk. */
  fun parse(raw: String?): Message? {
    if (raw.isNullOrBlank() || raw.length > MAX_MESSAGE_CHARS) return null
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (obj.optInt("v") != 1) return null
    return when (obj.optString("e")) {
      "ids" -> {
        val array = obj.optJSONArray("ids") ?: return null
        val ids = (0 until array.length()).mapNotNull { array.opt(it) as? String }
            .filter(YtTrack::isValidVideoId).distinct().take(MusicShareQueue.MAX_TRACKS)
        if (ids.isEmpty()) Message.Failed(-1) else Message.Ids(ids)
      }
      "error" -> Message.Failed(obj.optInt("c", -1))
      else -> null
    }
  }
}
