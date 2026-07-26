package com.wmc.mediacenter.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wmc.mediacenter.HomeUiState
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.apps.SystemActions
import com.wmc.mediacenter.data.AppSettings
import com.wmc.mediacenter.data.ShortcutConfig
import com.wmc.mediacenter.ui.components.AppTile
import com.wmc.mediacenter.ui.components.HomeHeader
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import kotlin.math.roundToInt

/**
 * S11 — ONE motion profile for everything that moves vertically on Home: the
 * strip expand/collapse and the centering scroll ran on different curves
 * (fast-start tween vs slow-start spring), which read as "expand-jolt, then
 * scroll" — worst moving up. Sharing identical spring physics makes row
 * growth and recentering accelerate and settle together as a single glide.
 * Stiffness is THE feel knob: higher = snappier, lower = lazier.
 */
private const val RowMotionStiffness = 650f

private fun <T> rowMotionSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = RowMotionStiffness
)

/**
 * S12 — where the focused strip's TOP edge lands, as a fraction of viewport
 * height (WMC: the title glides to a fixed line, the strip opens downward
 * beneath it). Verified from screen capture: center-based targeting caused a
 * two-phase motion on up-moves — the strip expanded in place near the top,
 * THEN slowly drifted to center, because the expanding strip moves its own
 * center toward the target, shrinking the remaining scroll every frame and
 * starving the spring. Anchoring the TOP decouples the scroll target from
 * the row's height entirely. CRITICAL implementation note: the target must
 * be computed ONCE per focus change (pre-compensating for rows above that
 * will collapse) — deriving it from live layout every frame makes the spring
 * chase its own output and crawl (that was the first, failed attempt).
 */
private const val FocusedRowAnchorFraction = 0.30f

/**
 * S13/S14 — the follower is a CRITICALLY-DAMPED SPRING pulling the focused
 * row's top to the anchor line. Unlike pure proportional (exponential)
 * control, it has velocity: it eases IN from rest and eases OUT into the
 * anchor, so it can be fast WITHOUT the first-frame jerk that plain
 * proportional gave at low time constants — and momentum carries smoothly
 * through rapid successive D-pad presses. Stiffness is THE feel knob:
 * higher = snappier AND more immediate (steeper initial acceleration, so
 * less perceived press-to-move delay). Critical damping (ratio 1.0) =
 * fastest settle with no overshoot. The loop also HARD-CLAMPS against
 * overshoot each frame (never crosses the anchor in one step), so it stays
 * stable even if this is pushed high — but keep it in the stable range for
 * the smoothest feel; ~550 responds promptly and settles ~170ms.
 */
private const val FollowerStiffness = 550f
// S20 — slightly OVERdamped (>1.0). At exactly critical the follower could
// still show a small bounce on up-moves: velocity built up chasing the
// anchor and carried a hair past as the row heights settled. Overdamping
// removes any overshoot/momentum bounce at the cost of a marginally softer
// finish.
private const val FollowerDampingRatio = 1.25f

/** One entry in Home's row list — either the derived Recent row (F4) or a user RowConfig. */
private data class HomeRowItem(
    val key: String,
    val rowId: String?,
    val title: String,
    val apps: List<AppInfo>,
    val isRecent: Boolean
)

/**
 * P5 home screen: WMC header (focused row title + clock) + one row per
 * user-configured RowConfig, plus an optional auto-populated "Recent" row
 * (F4) pinned above them when enabled. The built-in actions that used to
 * live in a fixed bottom bar (All Apps / Edit Rows / Settings / Google TV
 * Home) are now ordinary cards inside the default, editable "Settings" row
 * — clicking one dispatches to its action instead of launching a package.
 * Long-press OK on a tile opens the Move/Remove/App-info context menu
 * (handled one level up, in MediaCenterApp).
 *
 * S2/S3 — WMC's signature "cross" navigation: strips stack vertically and
 * the focused strip glides to the vertical center of the screen, with
 * unfocused strips dimmed and peeking above/below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    settings: AppSettings,
    recentApps: List<AppInfo>,
    onOpenAllApps: () -> Unit,
    onOpenEditRows: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoogleTvHome: () -> Unit,
    onLongPressRowTile: (rowId: String, app: AppInfo, index: Int, rowSize: Int) -> Unit,
    onAppLaunched: (String) -> Unit,
    onRemoveRecent: (String) -> Unit
) {
    val context = LocalContext.current
    // Captured BEFORE the no-op spec is provided below — rows restore this
    // default for their own horizontal scrolling (non-classic mode).
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val firstTileFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    var focusedRowIndex by remember { mutableStateOf(0) }

    val rowItems = remember(recentApps, uiState.rows) {
        buildList {
            if (recentApps.isNotEmpty()) {
                add(HomeRowItem(key = "__recent__", rowId = null, title = "Recent", apps = recentApps, isRecent = true))
            }
            uiState.rows.forEach { row ->
                add(HomeRowItem(key = row.id, rowId = row.id, title = row.name, apps = row.apps, isRecent = false))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackgroundAnimated()
    ) {
        HomeHeader(
            use24HourClock = settings.use24HourClock,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
        )

        val listState = rememberLazyListState()
        // S22 — the `rowHeights` map that used to live here was DEAD: it was
        // written from every row's onSizeChanged (i.e. on every layout pass
        // of every row, for the whole duration of every expand/collapse
        // animation) and read by nothing. It was a leftover from the
        // pre-follower design, which computed a scroll target from measured
        // heights. Removed.

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }

            // S10 — the vertical list must NEVER react to focus
            // bring-into-view: the centering effect below owns vertical
            // position. Without this, every lateral D-pad move let the focus
            // system nudge the LazyColumn against our centering scroll —
            // that was the whole-screen bounce on left/right movement.
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScrollSpec) {
            LazyColumn(
                state = listState,
                // Half-viewport top/bottom padding so ANY row — first or last,
                // expanded or collapsed — can always reach the vertical
                // center when focused. (0.35f was enough when every row was
                // tall, but with classic strips the collapsed stack is short
                // and the scroll clamped, pinning everything low on screen.)
                contentPadding = PaddingValues(vertical = maxHeight * 0.5f),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(rowItems, key = { _, item -> item.key }) { rowIndex, item ->
                    val isRowFocused = rowIndex == focusedRowIndex
                    AppRow(
                        title = item.title,
                        apps = item.apps,
                        showLabels = settings.showAppNames,
                        glassTiles = settings.glassTiles,
                        classicStrips = settings.classicStrips,
                        fadedTiles = settings.fadedTiles,
                        preferIconTiles = settings.preferIconTiles,
                        isRowFocused = isRowFocused,
                        modifier = Modifier
                            .onFocusChanged { state -> if (state.hasFocus) focusedRowIndex = rowIndex },
                        firstTileFocusRequester = if (rowIndex == 0) firstTileFocusRequester else null,
                        onLongPressTile = { index, app ->
                            if (item.isRecent) {
                                onRemoveRecent(app.packageName)
                            } else {
                                onLongPressRowTile(item.rowId!!, app, index, item.apps.size)
                            }
                        },
                        onSystemAction = { packageName ->
                            if (!item.isRecent) {
                                dispatchSystemAction(
                                    packageName = packageName,
                                    onOpenAllApps = onOpenAllApps,
                                    onOpenEditRows = onOpenEditRows,
                                    onOpenSettings = onOpenSettings,
                                    onGoogleTvHome = onGoogleTvHome
                                )
                            }
                        },
                        onShortcutClick = { shortcutId ->
                            uiState.shortcutsById[shortcutId]?.let { shortcut -> launchShortcut(context, shortcut) }
                        },
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        onAppLaunched = onAppLaunched
                    )
                }
            }
            } // CompositionLocalProvider (no-op vertical bring-into-view)

            // S13 — PROPORTIONAL FOLLOWER. One long-lived loop that every
            // frame nudges the focused strip's TOP toward the fixed anchor
            // line (FocusedRowAnchorFraction). This replaces all prior
            // per-keypress delta math, which computed a scroll target from a
            // still-animating layout snapshot and guessed how much the rows
            // above would shrink — a guess that differed up vs down and often
            // landed rows on different lines (the "up renders differently"
            // artifact, confirmed by frame-tracking demo4: focused rows
            // settled at wildly different heights).
            //
            // The follower enforces ONE invariant on every move regardless of
            // direction: focused-row-top == anchor. It reads the LIVE
            // position each frame and closes a fixed fraction of the
            // remaining error, so it rides the rows' expand/collapse
            // animations instead of predicting them, and it can't tail-chase
            // (proportional control converges to zero error and stops — the
            // old live-read spring crawled because it re-aimed a moving
            // target every frame; this just steps toward a FIXED line).
            LaunchedEffect(viewportPx) {
                val anchorY = viewportPx * FocusedRowAnchorFraction
                val damping = FollowerDampingRatio * 2f * kotlin.math.sqrt(FollowerStiffness)
                var velocity = 0f   // px/s of the focused row's top; persists across keypresses
                var lastFrame = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = if (lastFrame == 0L) 0.016f
                             else ((now - lastFrame) / 1_000_000_000f).coerceIn(0.004f, 0.025f)
                    lastFrame = now

                    val layoutInfo = listState.layoutInfo
                    val item = layoutInfo.visibleItemsInfo.find { it.index == focusedRowIndex }
                    if (item == null) {
                        // Focused row scrolled fully off (rare long jump) —
                        // bring it roughly on screen; next frames refine.
                        velocity = 0f
                        runCatching { listState.scrollToItem(focusedRowIndex) }
                        continue
                    }
                    val top = (item.offset - layoutInfo.viewportStartOffset).toFloat()
                    val error = top - anchorY   // >0: row sits below the anchor
                    if (kotlin.math.abs(error) < 0.5f && kotlin.math.abs(velocity) < 2f) {
                        velocity = 0f
                        continue
                    }

                    // Critically-damped spring integrated semi-implicitly
                    // (update velocity, then position). The row's top is the
                    // spring's position; the anchor is its rest target.
                    velocity += (-FollowerStiffness * error - damping * velocity) * dt
                    var topDelta = velocity * dt          // how far the top moves this frame
                    // OVERSHOOT CLAMP: there's a one-frame delay between
                    // dispatching a scroll and the layout reflecting it, so a
                    // stiff spring can command more than the remaining error
                    // and shoot past the anchor — that cross-and-return every
                    // frame is what flashed the screen. Never let a single
                    // frame cross the anchor: cap the move at the remaining
                    // error and kill velocity when we land. Makes the loop
                    // unconditionally stable at any stiffness.
                    if (topDelta <= -kotlin.math.abs(error) || topDelta >= kotlin.math.abs(error)) {
                        if ((topDelta < 0f) == (error > 0f)) { // moving toward the anchor
                            topDelta = -error
                            velocity = 0f
                        }
                    }
                    // Moving the top DOWN by d needs a negative raw scroll;
                    // dispatching positive lowers item offsets (raises the row).
                    runCatching { listState.dispatchRawDelta(-topDelta) }
                }
            }
        }
    }

    // Grab initial D-pad focus once the first visible row (Recent if shown,
    // else row 0) has apps to focus, so the remote works immediately without
    // an extra press. Only ever fires once.
    val firstRowHasApps = rowItems.firstOrNull()?.apps?.isNotEmpty() == true
    LaunchedEffect(firstRowHasApps) {
        if (!hasRequestedInitialFocus && firstRowHasApps) {
            hasRequestedInitialFocus = true
            runCatching { firstTileFocusRequester.requestFocus() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    title: String,
    apps: List<AppInfo>,
    showLabels: Boolean,
    glassTiles: Boolean,
    classicStrips: Boolean,
    fadedTiles: Boolean,
    preferIconTiles: Boolean,
    isRowFocused: Boolean,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
    firstTileFocusRequester: FocusRequester? = null,
    onLongPressTile: (index: Int, app: AppInfo) -> Unit,
    onSystemAction: (packageName: String) -> Unit,
    onShortcutClick: (shortcutId: String) -> Unit = {},
    onAppLaunched: (String) -> Unit = {}
) {
    if (apps.isEmpty()) return // an empty user row (e.g. fresh "Apps") has nothing to show yet

    // S3 — only the centered strip is fully lit: its title grows/brightens,
    // everything else fades back.
    val titleAlpha by animateFloatAsState(
        targetValue = if (isRowFocused) 1f else 0.45f,
        animationSpec = tween(200),
        label = "rowTitleAlpha"
    )
    // S9 — classic strips (default ON): only the highlighted row shows its
    // tiles, like the real WMC start menu; unfocused rows collapse to just
    // their title. 1f = fully expanded, 0f = collapsed. S11 — animated with
    // the SAME spring as the centering scroll so growth and scroll move as
    // one (see rowMotionSpring).
    val expansion by animateFloatAsState(
        targetValue = if (!classicStrips || isRowFocused) 1f else 0f,
        animationSpec = rowMotionSpring(),
        label = "rowExpansion"
    )

    Column(modifier = modifier) {
        Text(
            text = title,
            style = when {
                // With the header title gone (S9), the focused strip's title
                // is THE section label — real WMC scale: big and light for
                // the highlighted category, clearly smaller but readable for
                // the collapsed ones.
                classicStrips && isRowFocused -> MaterialTheme.typography.displayLarge
                classicStrips -> MaterialTheme.typography.headlineSmall
                isRowFocused -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            },
            color = WmcTextPrimary.copy(alpha = titleAlpha),
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        val rowListState = rememberLazyListState()

        // S9/S10 — WMC's fixed-focus carousel, done the right way: a custom
        // BringIntoViewSpec makes the FOCUS SYSTEM itself anchor the focused
        // tile at the strip's left content edge with one eased animation.
        // (The previous LaunchedEffect scroll raced the focus system's own
        // bring-into-view — that was the horizontal double-settle.)
        val density = LocalDensity.current
        val anchorSpec = remember(density) {
            object : BringIntoViewSpec {
                private val anchorPx = with(density) { 48.dp.toPx() }
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    tween(durationMillis = 280, easing = FastOutSlowInEasing)

                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                    offset - anchorPx
            }
        }

        // The strip stays composed — and therefore D-pad focusable — even
        // when collapsed: it's measured at full height, then laid out at
        // expansion-scaled height and clipped. Vertical focus search can
        // still land on a collapsed row's (invisible) tiles, which is
        // exactly what focuses the row and expands it.
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides if (classicStrips) anchorSpec else defaultBringIntoViewSpec
        ) {
        Box(
            modifier = Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val height = (placeable.height * expansion).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
        ) {
            LazyRow(
                state = rowListState,
                // Vertical padding gives the focused tile's 1.12x scale-up
                // room to draw — the strip now clips (collapse + offscreen
                // edge-fade compositing), which was cutting off the top of
                // the scaled highlighted card.
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .graphicsLayer {
                        // S15 — tile opacity is GATED, not linear in expansion.
                        // Height animates over the full 0..1, but tiles stay
                        // fully transparent until the row is ~60% open, then
                        // fade in over the remainder. Linear alpha meant a
                        // half-collapsed row drew the top slice of its tiles at
                        // partial opacity — the faint "ghost of a card top"
                        // seen while scrolling. Gating keeps the sliver zone
                        // empty: the space opens first, tiles fill in as it
                        // settles.
                        alpha = if (classicStrips) {
                            ((expansion - 0.6f) / 0.4f).coerceIn(0f, 1f)
                        } else if (isRowFocused) 1f else 0.6f
                        // S16 — NO offscreen compositing. gfxinfo on the box
                        // showed ~19ms GPU/frame with 46 offscreen
                        // RenderTargets — the old DstIn edge fade needed an
                        // offscreen layer per strip, the single most expensive
                        // thing on a tile GPU. Dropped entirely (the strip now
                        // just clips at its bounds via the parent's
                        // clipToBounds). Losing the soft end-dissolve is a
                        // cheap cosmetic price for roughly halving GPU cost.
                    }
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    AppTile(
                        app = app,
                        showLabel = showLabels,
                        glassTiles = glassTiles,
                        labelOnlyWhenFocused = classicStrips,
                        fadedWhenUnfocused = fadedTiles,
                        preferIcons = preferIconTiles,
                        // Built-in cards run their action, shortcut cards fire
                        // their stored deep link; everything else falls back to
                        // AppTile's default launch-by-package behavior.
                        onClick = when {
                            SystemActions.isSystemAction(app.packageName) -> { { onSystemAction(app.packageName) } }
                            ShortcutConfig.isShortcutId(app.packageName) -> { { onShortcutClick(app.packageName) } }
                            else -> null
                        },
                        modifier = if (index == 0 && firstTileFocusRequester != null) {
                            Modifier.focusRequester(firstTileFocusRequester)
                        } else {
                            Modifier
                        },
                        onLongClick = { onLongPressTile(index, app) },
                        onAppLaunched = onAppLaunched
                    )
                }
            }

            // S9 — chevrons hint that the strip continues off-screen.
            if (isRowFocused && rowListState.canScrollBackward) {
                RowChevron(symbol = "‹", modifier = Modifier.align(Alignment.CenterStart))
            }
            if (isRowFocused && rowListState.canScrollForward) {
                RowChevron(symbol = "›", modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        } // CompositionLocalProvider (horizontal anchor spec)
    }
}

/**
 * S10 — "never scroll" bring-into-view spec for Home's vertical LazyColumn:
 * HomeScreen's own centering effect is the only thing allowed to move the
 * row stack, so focus changes (especially lateral ones bubbling up from the
 * tile strips) can't bounce the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
private object NoFocusScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun RowChevron(symbol: String, modifier: Modifier = Modifier) {
    Text(
        text = symbol,
        color = WmcTextPrimary.copy(alpha = 0.55f),
        fontSize = 34.sp,
        modifier = modifier.padding(horizontal = 10.dp)
    )
}

/**
 * "Google TV Home" no longer switches launchers instantly on click — it's
 * the one accidental-exit risk left once Back is fully safe (see
 * MediaCenterApp's BackHandler), so [onGoogleTvHome] routes it through a
 * Cancel/Switch confirm overlay one level up instead, matching Delete Row /
 * Reset Setup.
 */
private fun dispatchSystemAction(
    packageName: String,
    onOpenAllApps: () -> Unit,
    onOpenEditRows: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoogleTvHome: () -> Unit
) {
    when (packageName) {
        SystemActions.ALL_APPS -> onOpenAllApps()
        SystemActions.EDIT_ROWS -> onOpenEditRows()
        SystemActions.SETTINGS -> onOpenSettings()
        SystemActions.GOOGLE_TV_HOME -> onGoogleTvHome()
    }
}
