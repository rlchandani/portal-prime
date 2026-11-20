/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tripwires over the source: every Portal↔Portal client (a file that both holds the household key
 * and opens an HTTP connection) reads its peer's reply through [BoundedRead], and every
 * household-signed peer route checks its header through [PeerRequestGate] before the body.
 * A new sync feature that copies the old `inputStream.readBytes()` pattern fails here.
 */
class PeerReadTripwireTest {
  private val unbounded = Regex("""(inputStream|errorStream)[^\n]*\.(readBytes|readText)\(\)|readBytes\(\)\s*\n\s*if \(buf\.size""")

  private fun sources(): List<File> {
    val rel = "src/main/java/com/immortal/launcher"
    var base: File? = File("").absoluteFile
    repeat(6) {
      val b = base ?: return@repeat
      for (cand in listOf(File(b, rel), File(b, "app/$rel"))) {
        if (cand.isDirectory) return cand.walkTopDown().filter { it.extension == "kt" }.toList()
      }
      base = b.parentFile
    }
    error("could not locate $rel from ${File("").absolutePath}")
  }

  @Test fun peerClientsReadRepliesThroughBoundedRead() {
    val peerClients = sources().filter { f ->
      val text = f.readText()
      "HouseholdKey.get" in text && "openConnection" in text
    }
    assertTrue("found the peer clients (${peerClients.size})", peerClients.size >= 7)
    for (f in peerClients) {
      val text = f.readText()
      assertTrue("${f.name} reads a peer reply without a streaming cap", !unbounded.containsMatchIn(text))
      assertTrue("${f.name} should read its peer reply through BoundedRead", "BoundedRead." in text)
    }
  }

  @Test fun signedPeerRoutesGateOnTheHeaderBeforeTheBody() {
    val routes = sources().filter { f ->
      val text = f.readText()
      "verifyRequest(req.header(" in text && "req.bodyText(" in text
    }
    assertTrue("found the signed peer routes (${routes.size})", routes.size >= 6)
    for (f in routes) {
      val text = f.readText()
      val gate = text.indexOf("PeerRequestGate.admits(")
      val body = text.indexOf("req.bodyText(", startIndex = maxOf(gate, 0))
      assertTrue("${f.name} must gate on the signed header before reading the body", gate >= 0 && body > gate)
    }
  }
}
