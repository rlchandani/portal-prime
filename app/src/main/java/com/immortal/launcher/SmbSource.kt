/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A photo source backed by an SMB/CIFS network share (a NAS) — the second self-hosted source.
 * Unlike the HTTP sources (Immich, iCloud/Google), SMB is a file protocol, so this connects to
 * the share, enumerates image files under a base path, and streams each file's bytes on demand.
 * Modern SMB 2/3 via smbj (verified against a TrueNAS share negotiating SMB 3.1.1).
 *
 * Stateful: [connect] opens the share for the screensaver session, [listImages] enumerates
 * relative paths (capped), [openStream] reads one file, and [close] tears the connection down.
 * Everything is best-effort — failures surface as null/empty and the caller falls back to the
 * default feed, so the frame is never blank. All calls must run off the main thread.
 */
class SmbSource(
    private val host: String,
    private val shareName: String,
    private val basePath: String,
    private val user: String,
    private val password: String,
    private val domain: String? = null,
) {
  @Volatile private var client: SMBClient? = null
  @Volatile private var connection: Connection? = null
  @Volatile private var session: Session? = null
  @Volatile private var share: DiskShare? = null

  /** Open the share. Returns true on success. */
  fun connect(): Boolean =
      runCatching {
            val c = if (PhotoSourceWork.ownsCurrentRequest()) SMBClient(
                SmbConfig.builder().withSocketFactory(PhotoSourceSocketFactory())
                    .withTimeout(8, TimeUnit.SECONDS).withSoTimeout(8, TimeUnit.SECONDS).build())
                else SMBClient()
            client = c
            PhotoSourceWork.checkpoint()
            val conn = c.connect(host)
            connection = conn
            PhotoSourceWork.checkpoint()
            val sess = conn.authenticate(AuthenticationContext(user, password.toCharArray(), domain))
            session = sess
            PhotoSourceWork.checkpoint()
            val sh = sess.connectShare(shareName) as DiskShare
            share = sh
            PhotoSourceWork.checkpoint()
            true
          }
          .getOrElse { close(); false }

  /** Recursively list image file paths (share-relative, backslash-separated), capped at [cap]. */
  fun listImages(cap: Int = 1000): List<String> = listImagesOrNull(cap).orEmpty()

  /** Null when the starting folder is unreadable; child folders remain best-effort. */
  fun listImagesOrNull(cap: Int = 1000): List<String>? {
    val sh = share ?: return null
    val out = ArrayList<String>()
    val stack = ArrayDeque<String>()
    val rootPath = normalize(basePath)
    stack.addLast(rootPath)
    while (stack.isNotEmpty() && out.size < cap) {
      PhotoSourceWork.checkpoint()
      val dir = stack.removeLast()
      val entries = runCatching { sh.list(dir) }.getOrNull() ?: if (dir == rootPath) return null else continue
      for (e in entries) {
        PhotoSourceWork.checkpoint()
        if (out.size >= cap) break
        val name = e.fileName
        if (name == "." || name == "..") continue
        val full = if (dir.isEmpty()) name else "$dir\\$name"
        val isDir =
            (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        if (isDir) {
          if (!name.startsWith(".")) stack.addLast(full)
        } else if (LocalMedia.classify(name) == LocalMedia.Kind.IMAGE) {
          out.add(full)
        }
      }
    }
    return out
  }

  /** Open a read stream for one file (share-relative path). Caller closes it (use `.use {}`). */
  fun openStream(path: String): InputStream? =
      runCatching {
            val sh = share ?: return null
            val file =
                sh.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null)
            file.inputStream // closing this stream closes the file handle
          }
          .getOrNull()

  fun close() {
    runCatching { share?.close() }
    runCatching { session?.close() }
    runCatching { connection?.close() }
    runCatching { client?.close() }
    share = null
    session = null
    connection = null
    client = null
  }

  /** Strip leading/trailing slashes and convert to SMB backslash separators. */
  private fun normalize(p: String): String =
      p.trim().trim('/', '\\').replace('/', '\\')
}
