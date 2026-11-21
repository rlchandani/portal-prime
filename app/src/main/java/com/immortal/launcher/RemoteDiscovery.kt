/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * mDNS/NSD advertising + discovery for the multi-device remote. Each Portal's [FleetAgentService]
 * advertises itself as `_immortal-remote._tcp` (friendly name + agent port) and simultaneously
 * browses for its peers, keeping a resolved list. The phone can't do mDNS itself, so [RemoteRoutes]
 * exposes the discovered peers at `/remote/devices`; the phone then pairs each (with its on-screen
 * PIN) and keeps the tokens locally. A Portal never mints or shares another's token itself; the
 * phone is the only courier — it may back its own roster up to each Portal ([RemoteRoster]) so
 * pairing one restores the rest, but that backup is auth-gated by the same on-screen PIN.
 *
 * Best-effort: registration/resolve hiccups are logged and skipped, never fatal to the agent.
 */
object RemoteDiscovery {
  private const val TAG = "ImmortalRemote"
  private const val TYPE = "_immortal-remote._tcp."
  /** Attempts per sighting when the platform reports its one resolve slot busy. */
  private const val MAX_RESOLVE_ATTEMPTS = 3

  private val lock = Any()
  private var session: Any? = null
  /** Duplicate Found callbacks share an identity until Lost retires that service occurrence. */
  private val sightings = mutableMapOf<String, Any>()
  private var nsd: NsdManager? = null
  private var regListener: NsdManager.RegistrationListener? = null
  private var discListener: NsdManager.DiscoveryListener? = null
  private var selfName: String = ""
  /**
   * Android 10's NsdService allows one in-flight resolve per client and fails a second with
   * FAILURE_ALREADY_ACTIVE, so sightings resolve one at a time. Guarded by [lock]; per session.
   */
  private val waiting = ArrayDeque<Pending>()
  private var resolving: Pending? = null

  private class Pending(val service: NsdServiceInfo, val owner: Any, val occurrence: Any, val attempt: Int = 1)

  /** Resolved peers, keyed by their advertised service name (self excluded). */
  private val peers = ConcurrentHashMap<String, Peer>()

  private data class Peer(val name: String, val host: String, val port: Int)

  @Synchronized
  fun start(context: Context, name: String, port: Int) {
    val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
    stop()
    val owner = Any()
    synchronized(lock) {
      session = owner
      nsd = mgr
      selfName = name
    }
    register(mgr, name, port, owner)
    discover(mgr, owner)
  }

  @Synchronized
  fun stop() {
    val retired =
        synchronized(lock) {
          // Retire ownership before platform teardown can deliver any more callbacks.
          session = null
          sightings.clear()
          peers.clear()
          waiting.clear()
          resolving = null
          val state = Triple(nsd, regListener, discListener)
          regListener = null
          discListener = null
          nsd = null
          state
        }
    val mgr = retired.first ?: return
    retired.second?.let { runCatching { mgr.unregisterService(it) } }
    retired.third?.let { runCatching { mgr.stopServiceDiscovery(it) } }
  }

  /** Discovered peers as `[{name, host, port}]` (this device excluded). */
  fun peersJson(): JSONArray {
    val arr = JSONArray()
    for (p in synchronized(lock) { peers.values.toList() }) {
      arr.put(JSONObject().put("name", p.name).put("host", p.host).put("port", p.port))
    }
    return arr
  }

  // --- registration -----------------------------------------------------------

  private fun register(mgr: NsdManager, name: String, port: Int, owner: Any) {
    val info =
        NsdServiceInfo().apply {
          serviceName = name
          serviceType = TYPE
          setPort(port)
        }
    val listener =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(s: NsdServiceInfo) {
            // The system may de-conflict our name (e.g. "Living Room (2)"); track the actual
            // registered name so we don't list ourselves as a peer.
            synchronized(lock) {
              if (session === owner) selfName = s.serviceName ?: name
            }
          }
          override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
            Log.w(TAG, "NSD register failed: $code")
          }
          override fun onServiceUnregistered(s: NsdServiceInfo) {}
          override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
    synchronized(lock) { regListener = listener }
    runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure { Log.w(TAG, "NSD registerService threw", it) }
  }

  // --- discovery --------------------------------------------------------------

  private fun discover(mgr: NsdManager, owner: Any) {
    val listener =
        object : NsdManager.DiscoveryListener {
          override fun onDiscoveryStarted(serviceType: String) {}
          override fun onServiceFound(s: NsdServiceInfo) {
            val occurrence =
                synchronized(lock) {
                  if (session !== owner || s.serviceName == selfName) return
                  sightings.getOrPut(s.serviceName) { Any() }
                }
            enqueue(mgr, Pending(s, owner, occurrence))
          }
          override fun onServiceLost(s: NsdServiceInfo) {
            synchronized(lock) {
              if (session !== owner) return
              sightings.remove(s.serviceName)
              peers.remove(s.serviceName)
            }
          }
          override fun onDiscoveryStopped(serviceType: String) {}
          override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
            Log.w(TAG, "NSD discovery start failed: $code")
          }
          override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
        }
    synchronized(lock) { discListener = listener }
    runCatching { mgr.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure { Log.w(TAG, "NSD discoverServices threw", it) }
  }

  private fun enqueue(mgr: NsdManager, pending: Pending) {
    val now =
        synchronized(lock) {
          if (session !== pending.owner) return
          // A duplicate Found shares its sighting's identity, so it rides the queued/in-flight attempt.
          val occurrence = pending.occurrence
          if (resolving?.occurrence === occurrence || waiting.any { it.occurrence === occurrence }) return
          if (resolving != null) {
            waiting.addLast(pending)
            return
          }
          resolving = pending
          pending
        }
    resolve(mgr, now)
  }

  /** [done] freed the slot: queue its [retry], then start the next sighting still current. */
  private fun finish(mgr: NsdManager, done: Pending, retry: Pending? = null) {
    val next =
        synchronized(lock) {
          if (session !== done.owner || resolving !== done) return
          retry?.let { waiting.addLast(it) }
          var candidate = waiting.removeFirstOrNull()
          while (candidate != null && sightings[candidate.service.serviceName] !== candidate.occurrence) {
            candidate = waiting.removeFirstOrNull()
          }
          resolving = candidate
          candidate
        } ?: return
    resolve(mgr, next)
  }

  private fun resolve(mgr: NsdManager, pending: Pending) {
    val name = pending.service.serviceName
    // A fresh ResolveListener per resolve — NsdManager rejects a reused/in-flight listener.
    val listener =
        object : NsdManager.ResolveListener {
          override fun onServiceResolved(s: NsdServiceInfo) {
            synchronized(lock) {
              val host = s.host?.hostAddress
              if (session === pending.owner && sightings[name] === pending.occurrence &&
                  s.serviceName != selfName && host != null) {
                peers[s.serviceName] = Peer(s.serviceName, host, s.port)
              }
            }
            finish(mgr, pending)
          }
          override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
            val retry =
                if (code == NsdManager.FAILURE_ALREADY_ACTIVE && pending.attempt < MAX_RESOLVE_ATTEMPTS)
                    Pending(pending.service, pending.owner, pending.occurrence, pending.attempt + 1)
                else null
            if (retry == null) Log.w(TAG, "NSD resolve failed for ${s.serviceName}: $code")
            finish(mgr, pending, retry)
          }
        }
    runCatching { mgr.resolveService(pending.service, listener) }
        .onFailure {
          Log.w(TAG, "NSD resolveService threw", it)
          finish(mgr, pending)
        }
  }
}
