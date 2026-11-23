/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.memory.InterestWidgets
import com.immortal.launcher.memory.InterestWidgetsHome
import com.immortal.launcher.memory.MemoryScope
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The Stocks, Crypto and News widgets with learned interests added (Jarvis personalization, slice 3; mock
 * `mocks/jarvis-personalization-20261122`, Home): the household's own rows first and unchanged, then the learned
 * ones under a divider, each marked (✦ provenance line, an amber "new" tag for 3 days) with a ✕ to take it off the
 * widgets and a 4 s Undo. Only reached while [rememberInterestWidgets] has something to add; otherwise the widgets
 * draw exactly as before.
 */

/** The mock's amber (light `#B9741A`/`#FBEBD3`, dark `#F0A65A`/`#3B2A15`) for everything learned. */
internal object LearnedPalette {
  val Ink: Color @Composable get() = if (LocalApprovedPalette.current.dark) Color(0xFFF0A65A) else Color(0xFFB9741A)
  val Fill: Color @Composable get() = if (LocalApprovedPalette.current.dark) Color(0xFF3B2A15) else Color(0xFFFBEBD3)
  val Line: Color @Composable get() = if (LocalApprovedPalette.current.dark) Color(0xFF7A5326) else Color(0xFFE7C48F)
}

/** Bumped when a learned item is removed or restored, so every widget re-reads at once. */
private val interestWidgetsChanged = MutableStateFlow(0)
private const val INTEREST_WIDGETS_REFRESH_MS = 5L * 60 * 1000
/** The memory thread writes the removal first; then the widgets re-read. */
private const val INTEREST_WIDGETS_SETTLE_MS = 400L
private const val UNDO_MS = 4_000L

/**
 * What the widgets may add for whoever is in front of the Portal right now; null with the switch off (off by
 * default), for guests, children and teens. Re-read on resume, when Who's there changes and every 5 minutes.
 */
@Composable
internal fun rememberInterestWidgets(): InterestWidgetsHome.Snapshot? {
  val context = LocalContext.current.applicationContext
  val who by WhosThere.homeView.collectAsState()
  val changed by interestWidgetsChanged.collectAsState()
  var resumed by remember { mutableStateOf(0) }
  OnResume { resumed++ }
  val snapshot by produceHomeState<InterestWidgetsHome.Snapshot?>(null, who, changed, resumed) {
    if (changed > 0) delay(INTEREST_WIDGETS_SETTLE_MS)
    while (true) {
      value = withContext(Dispatchers.IO) { runCatching { InterestWidgetsHome.load(context) }.getOrNull() }
      homeRefreshDelay(INTEREST_WIDGETS_REFRESH_MS)
    }
  }
  return snapshot
}

/** Learned items taken off a widget here, and the last one (for Undo). */
@Stable
internal class LearnedHiding(private val scope: MemoryScope) {
  var hidden by mutableStateOf(emptySet<String>())
    private set
  var last by mutableStateOf<InterestWidgets.Item?>(null)

  fun remove(context: android.content.Context, item: InterestWidgets.Item) {
    hidden = hidden + item.surface
    last = item
    InterestWidgetsHome.remove(context, scope, item)
    interestWidgetsChanged.value++
  }

  fun undo(context: android.content.Context) {
    val item = last ?: return
    hidden = hidden - item.surface
    last = null
    InterestWidgetsHome.restore(context, scope, item)
    interestWidgetsChanged.value++
  }
}

@Composable
internal fun rememberLearnedHiding(scope: MemoryScope): LearnedHiding {
  val hiding = remember(scope) { LearnedHiding(scope) }
  LaunchedEffect(hiding.last) { if (hiding.last != null) { delay(UNDO_MS); hiding.last = null } }
  return hiding
}

/** "✦ From what you ask about" (mock `.prov`). */
@Composable
internal fun LearnedProvenance(text: String, modifier: Modifier = Modifier, scale: Float = 1f, ink: Color? = null) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
    ApprovedHomeIcon("spark", Modifier.size((18 * scale).dp), LearnedPalette.Ink)
    Text(text, color = ink ?: LearnedPalette.Ink, fontSize = (17 * scale).sp, lineHeight = (22 * scale).sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** The amber "new" tag on an item learned in the last 3 days (mock `.nw`). */
@Composable
internal fun LearnedNewTag(modifier: Modifier = Modifier) {
  Text("new", color = LearnedPalette.Ink, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
      modifier = modifier.clip(RoundedCornerShape(999.dp)).background(LearnedPalette.Fill).padding(horizontal = 9.dp, vertical = 2.dp))
}

/** The ✕ that takes a learned item off the widgets: a 64 dp target. */
@Composable
internal fun LearnedRemoveButton(spoken: String, onClick: () -> Unit) {
  Box(Modifier.size(LEARNED_TARGET).tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .ccSpeak(spoken), contentAlignment = Alignment.Center) {
    ApprovedHomeIcon("x", Modifier.size(24.dp), ApprovedHomePalette.Secondary)
  }
}

/** "Removed AMD" with Undo for 4 s (mock `.learned`). */
@Composable
internal fun LearnedUndoRow(hiding: LearnedHiding) {
  val context = LocalContext.current
  val item = hiding.last ?: return
  Row(Modifier.fillMaxWidth().heightIn(min = LEARNED_TARGET).clip(RoundedCornerShape(20.dp)).background(LearnedPalette.Fill)
      .border(1.5.dp, LearnedPalette.Line, RoundedCornerShape(20.dp)).padding(start = 18.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Text("Removed ${item.label}", color = ApprovedHomePalette.Text, fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Box(Modifier.height(LEARNED_TARGET).widthIn(min = LEARNED_TARGET)
        .tvFocusable(RoundedCornerShape(999.dp), ringColor = ApprovedHomePalette.Accent) { hiding.undo(context) }
        .ccSpeak("Undo, show ${item.label} again").padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
      Text("Undo", color = LearnedPalette.Ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** The thin rule above the first learned row (mock `.row` border). */
@Composable
internal fun LearnedDivider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(ApprovedHomePalette.Border))
}

internal val LEARNED_TARGET = 64.dp

/** "NVDA new / NVIDIA … $142.18 +2.4% ✕": a learned ticker under the household's own. */
@Composable
internal fun LearnedStockRow(item: InterestWidgets.Item, quote: com.immortal.launcher.jarvis.StockQuotes.Quote?,
    onRemove: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.key, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (item.fresh) LearnedNewTag()
      }
      val name = quote?.name ?: item.label
      if (!name.equals(item.key, ignoreCase = true)) Text(name, color = ApprovedHomePalette.Secondary, fontSize = 18.sp,
          lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(quote?.let { "$%,.2f".format(it.last) } ?: "—", color = ApprovedHomePalette.Text, fontSize = 26.sp,
          lineHeight = 35.sp, maxLines = 1)
      quote?.let { ChangePill(it.changePct, Modifier.padding(top = 8.dp)) }
    }
    LearnedRemoveButton("Remove ${item.key} from Stocks", onRemove)
  }
}

/** Stocks with learned tickers: the configured list exactly as the plain widget shows it, then the learned rows. */
@Composable
internal fun LearnedStocksWidget(snap: InterestWidgetsHome.Snapshot, modifier: Modifier, maxRows: Int) {
  val context = LocalContext.current
  var tickers by remember { mutableStateOf(StocksConfig.load(context)) }
  var configured by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }
  OnResume { tickers = StocksConfig.load(context); configured = AlpacaConfig.isConfigured(context) }
  val hiding = rememberLearnedHiding(snap.scope)
  val rows = InterestWidgets.tickers(tickers, snap.picks.tickers.filterNot { it.surface in hiding.hidden })
  val own by produceHomeState<List<AlpacaClient.StockQuote>?>(initialValue = null, tickers, configured) {
    while (true) {
      value = if (!configured || tickers.isEmpty()) emptyList()
          else withContext(Dispatchers.IO) { AlpacaClient.fetchStockQuotes(context, tickers) }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
    }
  }
  val symbols = rows.mapNotNull { it.learned?.key }
  val learnedQuotes by produceHomeState<List<com.immortal.launcher.jarvis.StockQuotes.Quote>?>(initialValue = null, symbols) {
    while (true) {
      value = withContext(Dispatchers.IO) { runCatching { InterestWidgetsHome.quotes(context, symbols) }.getOrDefault(emptyList()) }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 5L * 60 * 1000)
    }
  }
  val open: () -> Unit = { context.openApprovedHomeSettings("stocks") }
  ImmortalWidgetShell(title = "Stocks", openPage = "stocks", modifier = modifier.tvFocusableRow { open() },
      provenance = InterestWidgets.provenance(InterestWidgets.Kind.TICKER, snap.household)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      val mine = own.orEmpty().take(maxRows)
      mine.forEach { q -> StockRow(q) }
      if (tickers.isNotEmpty() && !configured) ApprovedHomeWidgetMessage("Tap to connect Alpaca")
      LearnedUndoRow(hiding)
      val learned = rows.filter { it.learned != null }.take((maxRows - mine.size).coerceAtLeast(0))
      if (learned.isNotEmpty() && (mine.isNotEmpty() || tickers.isNotEmpty())) LearnedDivider()
      learned.forEach { row ->
        val item = row.learned ?: return@forEach
        LearnedStockRow(item, learnedQuotes?.firstOrNull { it.symbol.equals(item.key, ignoreCase = true) }) {
          hiding.remove(context, item)
        }
      }
    }
  }
}

/** Crypto with learned coins after the configured ones; whole rows fit the card as in the plain widget. */
@Composable
internal fun LearnedCryptoWidget(snap: InterestWidgetsHome.Snapshot, modifier: Modifier, maxRows: Int) {
  val context = LocalContext.current
  var coins by remember { mutableStateOf(CryptoConfig.load(context)) }
  OnResume { coins = CryptoConfig.load(context) }
  val hiding = rememberLearnedHiding(snap.scope)
  val rows = InterestWidgets.coins(coins, snap.picks.coins.filterNot { it.surface in hiding.hidden })
  val own by produceHomeState<List<CryptoFeed.Quote>?>(initialValue = null, coins) {
    while (true) {
      value = withContext(Dispatchers.IO) { CryptoFeed.fetch(context, coins) }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
    }
  }
  val learnedCoins = rows.filter { it.learned != null }.map { it.value }
  val learnedQuotes by produceHomeState<List<CryptoFeed.Quote>?>(initialValue = null, learnedCoins) {
    while (true) {
      value = withContext(Dispatchers.IO) { runCatching { InterestWidgetsHome.coins(learnedCoins) }.getOrDefault(emptyList()) }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 5L * 60 * 1000)
    }
  }
  val open: () -> Unit = { context.openApprovedHomeSettings("crypto") }
  ImmortalWidgetShell(title = "Crypto", openPage = "crypto", modifier = modifier.tvFocusableRow { open() },
      scrollContent = false, provenance = InterestWidgets.provenance(InterestWidgets.Kind.CRYPTO, snap.household)) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
      val mine = own.orEmpty()
      val theirs = rows.mapNotNull { r -> r.learned?.let { item -> learnedQuotes?.firstOrNull { it.id == r.value.id }?.let { item to it } } }
      val total = mine.size + theirs.size + if (hiding.last != null) 1 else 0
      val visible = homeRowsVisibleCount(total, maxHeight.value, CRYPTO_ROW_DP, CRYPTO_GAP_DP, CRYPTO_FOOTER_DP, maxRows)
      Column(verticalArrangement = Arrangement.spacedBy(CRYPTO_GAP_DP.dp)) {
        mine.take(visible).forEach { CryptoRow(it) }
        var left = visible - minOf(visible, mine.size)
        if (hiding.last != null && left > 0) { LearnedUndoRow(hiding); left-- }
        theirs.take(left).forEach { (item, q) ->
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CryptoRow(q) }
            if (item.fresh) LearnedNewTag()
            LearnedRemoveButton("Remove ${item.label} from Crypto") { hiding.remove(context, item) }
          }
        }
        val hidden = minOf(total, maxRows) - visible
        if (hidden > 0) Text("$hidden more ${if (hidden == 1) "coin" else "coins"}", color = ApprovedHomePalette.Secondary,
            fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1)
        if (total == 0) ApprovedHomeWidgetMessage(if (own == null) "Loading…" else "Add coins to track")
      }
    }
  }
}

/** News with topic feeds: the configured feeds as before, interleaved with the learned topics' headlines. */
@Composable
internal fun LearnedNewsWidget(snap: InterestWidgetsHome.Snapshot, modifier: Modifier, maxRows: Int) {
  val context = LocalContext.current
  var feeds by remember { mutableStateOf(NewsConfig.load(context)) }
  OnResume { feeds = NewsConfig.load(context) }
  val hiding = rememberLearnedHiding(snap.scope)
  val topics = snap.picks.topics.filterNot { it.surface in hiding.hidden }
  val own by produceHomeState<List<List<NewsFeed.Headline>>?>(initialValue = null, feeds) {
    while (true) {
      value = withContext(Dispatchers.IO) { feeds.map { NewsFeed.fetch(context, it.url) } }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
    }
  }
  val learned by produceHomeState<List<Pair<InterestWidgets.Item, List<InterestWidgets.Dated>>>?>(initialValue = null, topics) {
    while (true) {
      value = withContext(Dispatchers.IO) { runCatching { InterestWidgetsHome.topics(topics) }.getOrDefault(emptyList()) }
      homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 15L * 60 * 1000)
    }
  }
  val rows = remember(own, learned, maxRows) { InterestWidgets.news(own.orEmpty(), learned.orEmpty(), maxRows) }
  val nowMs = System.currentTimeMillis()
  val openEditor: () -> Unit = { context.openApprovedHomeSettings("news") }
  val openArticle: (String?) -> Unit = { link ->
    if (link.isNullOrBlank()) openEditor()
    else runCatching {
      context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
  ImmortalWidgetShell(title = "News", openPage = "news", modifier = modifier.tvFocusableRow { openEditor() },
      provenance = InterestWidgets.provenance(InterestWidgets.Kind.TOPIC, snap.household)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      LearnedUndoRow(hiding)
      if (rows.isEmpty()) ApprovedHomeWidgetMessage(if (own == null || learned == null) "Loading…" else "No headlines")
      rows.forEach { row ->
        val topic = row.topic
        if (topic == null) {
          Text(row.headline.title, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp, maxLines = 2,
              overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().tvFocusableRow { openArticle(row.headline.link) })
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f).tvFocusableRow { openArticle(row.headline.link) }) {
            Text(row.headline.title, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            val age = InterestWidgets.age(row.publishedMs, nowMs)
            LearnedProvenance(if (age != null) "${topic.label} · $age" else topic.label, Modifier.padding(top = 4.dp),
                ink = ApprovedHomePalette.Secondary)
          }
          LearnedRemoveButton("Stop showing ${topic.label} headlines") { hiding.remove(context, topic) }
        }
      }
    }
  }
}
