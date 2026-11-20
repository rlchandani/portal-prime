/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import com.immortal.launcher.FleetBackupArchive.PrefValue
import com.immortal.launcher.settings.SettingsSyncCatalog
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Sealed settings backups over the fleet API (fleet token checked by [FleetRoutes] first).
 *
 * - `GET /backup/key` — the active backup key's id and any pending one. `POST /backup/key
 *   {"publicKey"}` — propose the Mac's backup public key; it waits for USB confirmation
 *   (`202 pending`) unless it is already the active key ([FleetBackupKeyStore]).
 * - `POST /backup/export {"keyId"?}` — this Portal's preferences, data files and household
 *   settings as one archive, sealed in memory to the enrolled key ([FleetBackupCrypto]). Nothing
 *   is written to storage, and the plaintext never leaves the process.
 * - `POST /backup/import {"op":"begin"}` — a one-time import key (RSA-3072, memory only, 5 min).
 *   `{"op":"apply","session","backup","crossDevice"?,"sameSerial"?,"dryRun"?}` — the archive's
 *   envelope with the data key re-wrapped to that one-time key. Opened, validated in full and
 *   scoped ([FleetBackupArchive.plan]) before anything is written.
 *
 * The device binding guards against restoring one room's secrets onto another by mistake. It is
 * not a boundary against the fleet token, which could seal an archive of its own anyway.
 *
 * Rate-limited per route ([FleetBackupLimiter]) and one export or import at a time.
 */
internal class FleetBackupRoutes(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val platform: Platform = AndroidPlatform,
) {
  /** The Android pieces the routes use, replaceable so the routes run in JVM tests. */
  interface Platform {
    fun androidId(c: Context): String?
    fun log(message: String)
    /** The file's bytes (recovering an interrupted atomic write), or null when there is none. */
    fun read(file: File): ByteArray?
    fun write(file: File, bytes: ByteArray)
    fun household(c: Context): Map<String, Any>
    fun householdWellFormed(key: String, value: Any?): Any?
    fun applyHousehold(c: Context, values: Map<String, Any>): Set<String>
  }

  object AndroidPlatform : Platform {
    override fun androidId(c: Context): String? = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID)
    override fun log(message: String) { Log.i(TAG, message) }
    override fun read(file: File): ByteArray? = try {
      AtomicFile(file).openRead().use { input ->
        if (input.channel.size() > FleetBackupArchive.MAX_DATA_FILE_BYTES) throw FleetBackupArchive.ArchiveException("backup_too_large")
        input.readBytes()
      }
    } catch (_: FileNotFoundException) {
      null
    }
    override fun write(file: File, bytes: ByteArray) {
      file.parentFile?.mkdirs()
      val atomic = AtomicFile(file)
      val out = atomic.startWrite()
      try {
        out.write(bytes)
        atomic.finishWrite(out)
      } catch (t: Throwable) {
        atomic.failWrite(out)
        throw t
      }
    }
    override fun household(c: Context): Map<String, Any> = SettingsSyncCatalog.household.read(c)
    override fun householdWellFormed(key: String, value: Any?): Any? = SettingsSyncCatalog.household.wellFormed(key, value)
    override fun applyHousehold(c: Context, values: Map<String, Any>): Set<String> = SettingsSyncCatalog.household.apply(c, values)
  }

  private val limiter = FleetBackupLimiter(clock = clock)
  private val busy = AtomicBoolean(false)
  private val random = SecureRandom()
  private var pending: ImportSession? = null

  private class ImportSession(val id: String, val key: PrivateKey, val expiresAt: Long)

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val allowed = when (req.path) {
      KEY_PATH -> req.method == "GET" || req.method == "POST"
      EXPORT_PATH, IMPORT_PATH -> req.method == "POST"
      else -> return resp(404, err("not_found"))
    }
    if (!allowed) return resp(405, err("method_not_allowed"))
    // An import's two steps follow each other at once, so each step is limited on its own.
    if (req.path != IMPORT_PATH) limited(req.path + ":" + req.method)?.let { return it }
    return try {
      when (req.path) {
        KEY_PATH -> if (req.method == "GET") keyStatus() else enrol(req)
        EXPORT_PATH -> exclusive { export(req) }
        else -> importBackup(req)
      }
    } catch (_: FleetHttpServer.RequestBodyTooLarge) {
      resp(413, err("backup_too_large"))
    } catch (e: FleetBackupCrypto.SealException) {
      resp(statusFor(e.code), err(e.code))
    } catch (e: FleetBackupArchive.ArchiveException) {
      resp(statusFor(e.code), err(e.code))
    } catch (t: Throwable) {
      // Only the type: a message could quote archive content.
      platform.log("backup route failed (${t.javaClass.simpleName})")
      resp(500, err("backup_failed"))
    }
  }

  // --- key --------------------------------------------------------------------------------

  private fun keyStatus(): FleetHttpServer.Response {
    val spki = FleetBackupKeyStore.get(context)
    return resp(200, ok().put("enrolled", spki != null)
        .put("keyId", spki?.let(FleetBackupCrypto::keyId) ?: JSONObject.NULL)
        .put("pendingKeyId", FleetBackupKeyStore.pending(context)?.let(FleetBackupCrypto::keyId) ?: JSONObject.NULL))
  }

  private fun enrol(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText(SMALL_BODY)) ?: return resp(400, err("bad_json"))
    val spki = decode(body.opt("publicKey")) ?: return resp(400, err("bad_public_key"))
    FleetBackupCrypto.parsePublicKey(spki)
    val outcome = FleetBackupKeyStore.enrol(context, spki)
    if (outcome == FleetBackupKeyStore.Outcome.MISMATCH) {
      val current = FleetBackupKeyStore.get(context)?.let(FleetBackupCrypto::keyId)
      return resp(409, err("backup_key_mismatch").put("keyId", current ?: JSONObject.NULL))
    }
    platform.log("backup key ${outcome.name.lowercase()}")
    val reply = ok().put("result", outcome.name.lowercase()).put("keyId", FleetBackupCrypto.keyId(spki))
    // Pending: activate it over USB (FleetBackupKeyAdbReceiver) by this id.
    return if (outcome == FleetBackupKeyStore.Outcome.PENDING) resp(202, reply.put("confirm", "usb")) else resp(200, reply)
  }

  // --- export -----------------------------------------------------------------------------

  private fun export(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = req.bodyText(SMALL_BODY).let { if (it.isBlank()) JSONObject() else parseJson(it) } ?: return resp(400, err("bad_json"))
    val spki = FleetBackupKeyStore.get(context) ?: return resp(409, err("backup_key_not_enrolled"))
    val keyId = FleetBackupCrypto.keyId(spki)
    if (body.has("keyId") && body.opt("keyId") != keyId) {
      return resp(409, err("backup_key_mismatch").put("keyId", keyId))
    }
    val device = deviceId() ?: return resp(503, err("device_identity_unavailable"))
    val (versionCode, versionName) = appVersion()
    val archive = FleetBackupArchive.Archive(device, System.currentTimeMillis(), versionCode, versionName,
        collectPrefs(), collectFiles(), platform.household(context))
    val plain = FleetBackupArchive.encode(archive)
    try {
      if (plain.size > FleetBackupArchive.MAX_PLAIN_BYTES) return resp(413, err("backup_too_large"))
      // An export this Portal's own import would refuse is worthless; check before sealing.
      FleetBackupArchive.decode(plain, platform::householdWellFormed)
      val header = JSONObject().put("format", FleetBackupCrypto.FORMAT).put("version", FleetBackupCrypto.VERSION)
          .put("alg", FleetBackupCrypto.ALG).put("keyId", keyId).put("device", device)
          .put("createdAt", archive.createdAt).put("versionCode", versionCode)
          .toString().toByteArray(Charsets.UTF_8)
      val sealed = FleetBackupCrypto.seal(plain, header, FleetBackupCrypto.parsePublicKey(spki))
      platform.log("backup exported: ${archive.prefs.size} preference files, ${archive.files.size} data files")
      return resp(200, ok().put("keyId", keyId).put("device", device).put("backup", sealed.toJson())
          .put("summary", JSONObject().put("prefFiles", archive.prefs.size).put("dataFiles", archive.files.size)
              .put("householdSettings", archive.household.size)))
    } finally {
      plain.fill(0)
    }
  }

  private fun collectPrefs(): Map<String, Map<String, PrefValue>> {
    val dir = File(context.applicationInfo.dataDir, "shared_prefs")
    val names = dir.list().orEmpty().filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }
        .filter(FleetBackupArchive::isExportablePref).sorted()
    if (names.size > FleetBackupArchive.MAX_PREF_FILES) throw FleetBackupArchive.ArchiveException("backup_too_large")
    return names.associateWith { name ->
      val out = sortedMapOf<String, PrefValue>()
      context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, raw) ->
        val value = FleetBackupArchive.prefValue(raw)
        if (value != null && key.isNotEmpty() && key.length <= FleetBackupArchive.MAX_KEY_CHARS) out[key] = value
      }
      out
    }
  }

  private fun collectFiles(): Map<String, ByteArray> {
    val out = sortedMapOf<String, ByteArray>()
    for (path in FleetBackupArchive.DATA_FILES) platform.read(dataFile(path))?.let { out[path] = it }
    return out
  }

  // --- import -----------------------------------------------------------------------------

  private fun importBackup(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText(MAX_IMPORT_BODY)) ?: return resp(400, err("bad_json"))
    val op = body.optString("op")
    if (op != "begin" && op != "apply") return resp(400, err("bad_op"))
    limited("$IMPORT_PATH:$op")?.let { return it }
    return if (op == "begin") begin() else exclusive { apply(body) }
  }

  private fun limited(key: String): FleetHttpServer.Response? = limiter.acquire(key)?.let { wait ->
    FleetHttpServer.Response.json(429, err("backup_rate_limited").put("retryAfterSec", wait).toString(),
        mapOf("Retry-After" to wait.toString()))
  }

  private fun begin(): FleetHttpServer.Response {
    val pair = FleetBackupCrypto.newImportKeyPair()
    val id = FleetBackupCrypto.hex(ByteArray(16).also(random::nextBytes))
    synchronized(this) { pending = ImportSession(id, pair.private, clock() + SESSION_MS) }
    return resp(200, ok().put("session", id).put("publicKey", FleetBackupCrypto.b64(pair.public.encoded))
        .put("expiresInSec", SESSION_MS / 1000))
  }

  private fun apply(body: JSONObject): FleetHttpServer.Response {
    val session = synchronized(this) {
      pending?.takeIf { it.id == body.optString("session") }?.also { pending = null }
    } ?: return resp(409, err("import_session_unknown"))
    if (clock() > session.expiresAt) return resp(410, err("import_session_expired"))
    val envelope = FleetBackupCrypto.parseEnvelope(body.optJSONObject("backup"), MAX_SEALED_BYTES)
    val headerDevice = envelope.headerJson().opt("device")
    val plain = FleetBackupCrypto.open(envelope, session.key, FleetBackupArchive.MAX_PLAIN_BYTES)
    val archive = try {
      FleetBackupArchive.decode(plain, platform::householdWellFormed)
    } finally {
      plain.fill(0)
    }
    if (archive.device != headerDevice) throw FleetBackupArchive.ArchiveException("bad_archive")
    val here = deviceId() ?: return resp(503, err("device_identity_unavailable"))
    val crossDevice = body.opt("crossDevice") == true
    val confirmed = body.opt("sameSerial") == true
    if (crossDevice && confirmed) return resp(400, err("bad_scope"))
    val plan = FleetBackupArchive.plan(archive, here, crossDevice, operatorConfirmed = confirmed)
    val result = ok().put("sameDevice", archive.device == here).put("plan", plan.summary())
    if (body.opt("dryRun") == true) return resp(200, result.put("dryRun", true))
    val written = write(plan)
    platform.log("backup imported: ${plan.prefs.size} preference files, ${plan.files.size} data files, " +
        "${plan.household.size} household settings (sameDevice=${archive.device == here})")
    return resp(200, result.put("dryRun", false).put("applied", written).put("rebootRecommended", true))
  }

  /** Writes [plan]. Every input is already validated, so a failure here is a storage failure. */
  private fun write(plan: FleetBackupArchive.Plan): JSONObject {
    var prefFiles = 0
    plan.prefs.forEach { (name, values) ->
      // Through the SharedPreferences API, so the in-memory copy the app reads changes with the
      // file and a later commit elsewhere can't write the old values back.
      val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
      values.forEach { (key, value) ->
        when (value) {
          is PrefValue.Str -> editor.putString(key, value.v)
          is PrefValue.IntV -> editor.putInt(key, value.v)
          is PrefValue.LongV -> editor.putLong(key, value.v)
          is PrefValue.FloatV -> editor.putFloat(key, value.v)
          is PrefValue.Bool -> editor.putBoolean(key, value.v)
          is PrefValue.StrSet -> editor.putStringSet(key, value.v)
        }
      }
      if (!editor.commit()) throw IllegalStateException("preference file not stored")
      prefFiles++
    }
    var dataFiles = 0
    // Under the companion store's lock, so no reader can re-memoize its identity mid-replacement.
    synchronized(RemoteCompanionStore.mutationLock) {
      plan.files.forEach { (path, bytes) ->
        platform.write(dataFile(path), bytes)
        dataFiles++
      }
      RemoteCompanionStore.forgetCachedId()
    }
    val settings = if (plan.household.isEmpty()) 0 else platform.applyHousehold(context, plan.household).size
    return JSONObject().put("prefFiles", prefFiles).put("dataFiles", dataFiles).put("householdSettings", settings)
  }

  // --- helpers ----------------------------------------------------------------------------

  private inline fun exclusive(block: () -> FleetHttpServer.Response): FleetHttpServer.Response {
    if (!busy.compareAndSet(false, true)) return resp(409, err("backup_busy"))
    try {
      return block()
    } finally {
      busy.set(false)
    }
  }

  /** Stable across reinstalls with the same signing key; hashed so the raw id never leaves. */
  private fun deviceId(): String? = platform.androidId(context)?.takeIf { it.isNotBlank() }?.let { deviceIdOf(it) }

  private fun dataFile(path: String): File {
    val (root, name) = path.split('/', limit = 2)
    return File(if (root == "no_backup") context.noBackupFilesDir else context.filesDir, name)
  }

  private fun appVersion(): Pair<Long, String> = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    code to (info.versionName ?: "")
  }.getOrDefault(0L to "")

  private fun decode(raw: Any?): ByteArray? =
      (raw as? String)?.takeIf { it.length <= 4096 }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

  private fun ok(): JSONObject = JSONObject().put("ok", true)
  private fun err(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)
  private fun parseJson(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()
  private fun resp(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  companion object {
    private const val TAG = "HearthpaneBackup"
    const val KEY_PATH = "/backup/key"
    const val EXPORT_PATH = "/backup/export"
    const val IMPORT_PATH = "/backup/import"
    val PATHS = setOf(KEY_PATH, EXPORT_PATH, IMPORT_PATH)
    private const val SMALL_BODY = 16 * 1024
    const val MAX_IMPORT_BODY = 12 * 1024 * 1024
    private const val MAX_SEALED_BYTES = FleetBackupArchive.MAX_PLAIN_BYTES + 64 * 1024
    const val SESSION_MS = 5 * 60_000L

    fun deviceIdOf(androidId: String): String = FleetBackupCrypto.hex(
        MessageDigest.getInstance("SHA-256").digest("hearthpane-backup-device-v1|$androidId".toByteArray(Charsets.UTF_8))).take(32)

    internal fun statusFor(code: String): Int = when (code) {
      "backup_too_large" -> 413
      "different_device", "backup_key_mismatch" -> 409
      "wrong_key", "seal_broken" -> 422
      else -> 400
    }
  }
}
