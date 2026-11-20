/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Crypto price widget: a compact list of the user's tracked coins (symbol · price · 24h change),
 * refreshed from CoinGecko on the standard 30-min success / 1-min retry cadence. Tapping opens the
 * coin editor. Change is green/red like the stock convention. Re-reads the coin list on resume so
 * an edit applies on return home.
 */
@Composable
internal fun ImmortalCryptoWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var coins by remember { mutableStateOf(CryptoConfig.load(context)) }
  OnResume { coins = CryptoConfig.load(context) }
  val quotes by
      produceHomeState<List<CryptoFeed.Quote>?>(initialValue = null, coins) {
        while (true) {
          value = withContext(Dispatchers.IO) { CryptoFeed.fetch(context, coins) }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("crypto") }
  ImmortalWidgetShell(title = "Crypto", openPage = "crypto", modifier = modifier.tvFocusableRow { open() },
      scrollContent = false) {
    val rows = quotes
    when {
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("Add coins to track")
      // Fit whole rows to the card (it shrinks when a shared note shows on Home) and say how
      // many coins are hidden, instead of clipping the last row mid-line.
      else -> BoxWithConstraints(Modifier.fillMaxSize()) {
        val visible = remember(rows.size, maxHeight, maxRows) {
          homeRowsVisibleCount(rows.size, maxHeight.value, CRYPTO_ROW_DP, CRYPTO_GAP_DP, CRYPTO_FOOTER_DP, maxRows)
        }
        Column(verticalArrangement = Arrangement.spacedBy(CRYPTO_GAP_DP.dp)) {
          rows.take(visible).forEach { q -> CryptoRow(q) }
          val hidden = minOf(rows.size, maxRows) - visible
          if (hidden > 0) Text(if (visible == 0) "$hidden ${if (hidden == 1) "coin" else "coins"}"
              else "$hidden more ${if (hidden == 1) "coin" else "coins"}",
              color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1)
        }
      }
    }
  }
}

// Crypto row budget: 35sp price line + 8dp + 32dp pill row; 16dp between rows; 24sp footer.
private const val CRYPTO_ROW_DP = 76f
private const val CRYPTO_GAP_DP = 16f
private const val CRYPTO_FOOTER_DP = 24f

/** One coin line: symbol · 7d sparkline · price · tinted change pill (Dusk Aurora). */
@Composable
private fun CryptoRow(q: CryptoFeed.Quote) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text(q.symbol, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f))
      Text(formatUsd(q.usd), color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          maxLines = 1)
    }
    if (q.spark.size >= 2 || q.change24h != null) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically) {
        if (q.spark.size >= 2) Sparkline(q.spark, up = (q.change24h ?: 0.0) >= 0,
            modifier = Modifier.size(width = 64.dp, height = 22.dp))
        q.change24h?.let { ChangePill(it) }
      }
    }
  }
}

/**
 * A tinted percent-change pill: soft green/red fill with a saturated label (the mock's `.chg`).
 * Fixed min-width keeps columns of pills aligned.
 */
@Composable
private fun ChangePill(pct: Double, modifier: Modifier = Modifier) {
  val up = pct >= 0
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val tint = if (up) palette.green else if (palette.dark) Color(0xFFFFC8B9) else palette.error
  Text(
      "%s%.1f%%".format(if (up) "+" else "−", kotlin.math.abs(pct)),
      color = tint,
      fontSize = 18.sp,
      lineHeight = 24.sp,
      fontWeight = FontWeight.Medium,
      textAlign = androidx.compose.ui.text.style.TextAlign.End,
      modifier =
          modifier
              .clip(RoundedCornerShape(7.dp))
              .background(tint.copy(alpha = .14f))
              .padding(horizontal = 8.dp, vertical = 4.dp)
              .widthIn(min = 56.dp),
  )
}

/** A tiny price polyline, tinted by direction — the widget-row mini chart from the mock. */
@Composable
private fun Sparkline(points: List<Double>, up: Boolean, modifier: Modifier = Modifier) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val color = if (up) palette.green else if (palette.dark) Color(0xFFFFC8B9) else palette.error
  Canvas(modifier) {
    val min = points.min()
    val max = points.max()
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val stepX = size.width / (points.size - 1)
    val path = androidx.compose.ui.graphics.Path()
    points.forEachIndexed { i, p ->
      val x = i * stepX
      val y = (size.height * (1f - ((p - min) / range).toFloat())).coerceIn(0f, size.height)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
  }
}

/** Compact USD formatting: whole dollars for big prices, cents for sub-$100 (e.g. altcoins). */
private fun formatUsd(v: Double): String =
    when {
      v >= 1000 -> "$%,.0f".format(v)
      v >= 1 -> "$%,.2f".format(v)
      else -> "$%.4f".format(v)
    }

/**
 * News headlines widget: aggregates the latest titles across the user's configured RSS/Atom feeds
 * ([NewsConfig]) on the standard 30-min / 1-min cadence. Tapping a headline opens its article link
 * in the browser; tapping the chrome (or when empty) opens the feed editor. Re-reads feeds on resume.
 */
@Composable
internal fun ImmortalNewsWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var feeds by remember { mutableStateOf(NewsConfig.load(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) feeds = NewsConfig.load(context) }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val headlines by
      produceHomeState<List<NewsFeed.Headline>?>(initialValue = null, feeds) {
        while (true) {
          value =
              withContext(Dispatchers.IO) {
                // Interleave feeds so no single source dominates the (short) list.
                feeds.map {
                  kotlinx.coroutines.currentCoroutineContext().ensureActive()
                  NewsFeed.fetch(context, it.url)
                }.let(::interleave)
              }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val openEditor: () -> Unit = { context.openApprovedHomeSettings("news") }
  val openArticle: (String?) -> Unit = { link ->
    if (link.isNullOrBlank()) openEditor()
    else runCatching {
      context.startActivity(
          Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
  ImmortalWidgetShell(title = "News", openPage = "news", modifier = modifier.tvFocusableRow { openEditor() }) {
    val rows = headlines
    when {
      feeds.isEmpty() -> ApprovedHomeWidgetMessage("Add a feed to see headlines")
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No headlines")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { h ->
              Text(
                  h.title,
                  color = ApprovedHomePalette.Text,
                  fontSize = 26.sp,
                  lineHeight = 35.sp,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.fillMaxWidth().tvFocusableRow { openArticle(h.link) },
              )
            }
          }
    }
  }
}

/** Round-robin merge of per-feed headline lists so each source is represented near the top. */
private fun interleave(lists: List<List<NewsFeed.Headline>>): List<NewsFeed.Headline> {
  val out = ArrayList<NewsFeed.Headline>()
  val max = lists.maxOfOrNull { it.size } ?: 0
  for (i in 0 until max) {
    for (l in lists) l.getOrNull(i)?.let { out.add(it) }
  }
  return out
}

/**
 * Stocks quote widget ([StocksConfig] + [AlpacaConfig]). Lists tracked tickers with last price and
 * daily change (green/red) on the 30-min/1-min cadence. Requires Alpaca credentials — shows a
 * "connect" prompt until configured. Tapping opens the editor. Data is IEX real-time (free tier).
 */
@Composable
internal fun ImmortalStocksWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var tickers by remember { mutableStateOf(StocksConfig.load(context)) }
  var configured by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }
  OnResume {
        tickers = StocksConfig.load(context)
        configured = AlpacaConfig.isConfigured(context)
  }
  val quotes by
      produceHomeState<List<AlpacaClient.StockQuote>?>(initialValue = null, tickers, configured) {
        while (true) {
          value = withContext(Dispatchers.IO) { AlpacaClient.fetchStockQuotes(context, tickers) }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("stocks") }
  ImmortalWidgetShell(title = "Stocks", openPage = "stocks", modifier = modifier.tvFocusableRow { open() }) {
    val rows = quotes
    when {
      !configured -> ApprovedHomeWidgetMessage("Tap to connect Alpaca")
      tickers.isEmpty() -> ApprovedHomeWidgetMessage("Add tickers to track")
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No quotes")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { q -> StockRow(q) }
          }
    }
  }
}

/** One ticker line: symbol · last price · tinted daily-change pill (Dusk Aurora). */
@Composable
private fun StockRow(q: AlpacaClient.StockQuote) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text(q.symbol, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f))
      Text("$%,.2f".format(q.last), color = ApprovedHomePalette.Text, fontSize = 26.sp,
          lineHeight = 35.sp, maxLines = 1)
    }
    ChangePill(q.changePct, Modifier.padding(top = 8.dp))
  }
}

/**
 * Options scanner widget ([OptionsScanConfig] + [AlpacaConfig]). Lists option contracts across the
 * configured underlyings whose Alpaca-computed delta falls in the user's band (default 0.20–0.30) —
 * a premium-selling shortlist. Refreshes on a slower 60-min/2-min cadence (option chains are heavier
 * and free data is 15-min delayed anyway). Requires Alpaca credentials; tap opens the editor.
 */
@Composable
internal fun ImmortalOptionsWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var tickers by remember { mutableStateOf(OptionsScanConfig.tickers(context)) }
  var configured by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }
  var type by remember { mutableStateOf(OptionsScanConfig.type(context)) }
  var deltaMin by remember { mutableStateOf(OptionsScanConfig.deltaMin(context)) }
  var deltaMax by remember { mutableStateOf(OptionsScanConfig.deltaMax(context)) }
  var expiryDays by remember { mutableStateOf(OptionsScanConfig.expiryDays(context)) }
  OnResume {
        tickers = OptionsScanConfig.tickers(context)
        configured = AlpacaConfig.isConfigured(context)
        type = OptionsScanConfig.type(context)
        deltaMin = OptionsScanConfig.deltaMin(context)
        deltaMax = OptionsScanConfig.deltaMax(context)
        expiryDays = OptionsScanConfig.expiryDays(context)
  }
  val hits by
      produceHomeState<List<OptionsScanner.Hit>?>(initialValue = null, tickers, configured, type, deltaMin, deltaMax, expiryDays) {
        while (true) {
          value =
              withContext(Dispatchers.IO) {
                val worker = kotlinx.coroutines.currentCoroutineContext()
                OptionsScanner.scan(context, tickers, type, deltaMin, deltaMax, expiryDays,
                    checkCancelled = { worker.ensureActive() })
              }
          homeRefreshDelay(if (value.isNullOrEmpty()) 120_000L else 60L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("options") }
  ImmortalWidgetShell(title = "Options", openPage = "options",
      modifier = modifier.tvFocusableRow { open() }) {
    Text("${"%.2f–%.2f".format(deltaMin, deltaMax)} delta band",
        color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp,
        modifier = Modifier.padding(bottom = 12.dp))
    val rows = hits
    when {
      !configured -> ApprovedHomeWidgetMessage("Tap to connect Alpaca")
      tickers.isEmpty() -> ApprovedHomeWidgetMessage("Add tickers to scan")
      rows == null -> ApprovedHomeWidgetMessage("Scanning…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No contracts in band")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { h -> OptionRow(h) }
          }
    }
  }
}

/** One scanner row: "AAPL 300C 12/20" · delta · mid price. */
@Composable
private fun OptionRow(h: OptionsScanner.Hit) {
  val label = "${h.underlying} ${strikeLabel(h.strike)}${if (h.call) "C" else "P"} ${expiryShort(h.expiry)}"
  Column(Modifier.fillMaxWidth()) {
    Text(label, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text("Δ%.2f".format(kotlin.math.abs(h.delta)), color = ApprovedHomePalette.Accent,
          fontSize = 18.sp, lineHeight = 24.sp)
      h.mid?.let { Text("$%.2f".format(it), color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp) }
    }
  }
}

/** Whole-dollar strike when integral (300), else two decimals (302.50). */
private fun strikeLabel(strike: Double): String =
    if (strike == strike.toLong().toDouble()) "${strike.toLong()}" else "%.2f".format(strike)

/** "2024-12-20" -> "12/20". */
private fun expiryShort(iso: String): String =
    iso.split("-").let { if (it.size == 3) "${it[1]}/${it[2]}" else iso }
