package com.gnosh.tvhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tvhome.core.assistant.AskCopy
import tvhome.core.assistant.AskEngine
import tvhome.core.assistant.AskOutcome
import tvhome.core.assistant.OpenTarget
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.Tokens

/** On the Ask view's root, for TalkBack and for the emulator QA check (scripts/ci/emulator-qa.sh). */
const val ASK_FULL_SCREEN = "Ask Claude full screen"

/** How Open would launch a target, decided before Brian presses it so the screen can say which fallback is used. */
class OpenPlan(val description: String, val launch: () -> Boolean)

/**
 * What the Ask view needs from the activity. [newEngine] gives a fresh engine (and so a fresh, in-memory conversation)
 * per visit. [listen] starts the system speech dialog and returns false if there is none (then the view falls back to
 * typing); its callback gets the transcript, or null when nothing was recognised.
 */
interface AskHost {
    fun newEngine(): AskEngine
    fun listen(onResult: (String?) -> Unit): Boolean
    fun planOpen(target: OpenTarget): OpenPlan
}

private sealed interface Step {
    /** The key check (Keystore decrypt, on IO) for this visit; a frame or two. */
    object Checking : Step
    data class Start(val note: String?) : Step
    data class Typing(val text: String) : Step
    data class Confirm(val text: String) : Step
    data class Waiting(val question: String) : Step
    data class Done(val question: String, val outcome: AskOutcome) : Step
}

/**
 * The Ask Claude view, full screen (M1b lean). Input: speech through the system dialog, else a text field; the
 * transcript is shown with Send and Cancel before anything is sent. Result: the answer (focusable, D-pad Up/Down
 * scrolls it), and Open only when Claude named an installed app; nothing launches until Open is pressed. One
 * follow-up, then Start over. Back closes the view and drops the conversation (it lives in [engine], remembered only
 * while this view is composed). Nothing above the controls is focusable, so Back reaches the BackHandler at once.
 */
@Composable
fun AskFullScreen(host: AskHost, theme: HomeTheme, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var generation by remember { mutableIntStateOf(0) }
    val engine = remember(generation) { host.newEngine() }
    var step by remember(generation) { mutableStateOf<Step>(Step.Checking) }
    LaunchedEffect(generation) {
        step = if (engine.checkKey()) Step.Start(null) else Step.Done("", AskOutcome.NotSetUp)
    }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun send(question: String) {
        step = Step.Waiting(question)
        job?.cancel()
        job = scope.launch {
            val outcome = engine.ask(question)
            step = if (outcome == AskOutcome.EmptyQuestion) Step.Start(AskCopy.EMPTY_QUESTION) else Step.Done(question, outcome)
        }
    }

    fun listen() {
        val started = host.listen { text ->
            step = if (text.isNullOrBlank()) Step.Start("Didn't catch that. Try again, or type it.") else Step.Confirm(text)
        }
        if (!started) step = Step.Typing("")
    }

    val fg = color(theme.text)
    val dim = color(theme.secondaryText)
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            .semantics { contentDescription = ASK_FULL_SCREEN }
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        Column(Modifier.widthIn(max = Tokens.MAX_CONTENT_WIDTH_DP.dp).fillMaxSize()) {
            Text(AskCopy.TILE_LABEL, color = fg, fontSize = Tokens.HEADING_SP.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))
            when (val s = step) {
                Step.Checking -> Line("\u2026", dim)
                is Step.Start -> {
                    Line(s.note ?: prompt(engine), dim)
                    Buttons(theme, step) {
                        button("Speak") { listen() }
                        button("Type") { step = Step.Typing("") }
                        button("Done") { onClose() }
                    }
                }
                is Step.Typing -> {
                    QuestionField(s.text, theme, onChange = { step = Step.Typing(it) }, onSend = { send(s.text) })
                    Buttons(theme, step, focusFirst = false) {
                        button("Send") { send(s.text) }
                        button("Cancel") { step = Step.Start(null) }
                    }
                }
                is Step.Confirm -> {
                    Line("Send this to Claude?", dim)
                    Spacer(Modifier.height(12.dp))
                    Text("“${s.text}”", color = fg, fontSize = Tokens.ROW_SP.sp)
                    Buttons(theme, step) {
                        button("Send") { send(s.text) }
                        button("Edit") { step = Step.Typing(s.text) }
                        button("Cancel") { step = Step.Start(null) }
                    }
                }
                is Step.Waiting -> {
                    Line("Asking Claude…", dim)
                    Buttons(theme, step) {
                        button("Cancel") { job?.cancel(); step = Step.Start(null) }
                    }
                }
                is Step.Done -> Result(s, engine, host, theme, onRetry = { send(s.question) }, onFollowUp = { step = Step.Start(null) },
                    onStartOver = { generation++ }, onClose = onClose)
            }
        }
    }
}

private fun prompt(engine: AskEngine) =
    if (engine.isFollowUp) "Ask a follow-up: say or type it." else "Ask anything, or say what you'd like to watch."

@Composable
private fun ColumnScope.Result(
    s: Step.Done,
    engine: AskEngine,
    host: AskHost,
    theme: HomeTheme,
    onRetry: () -> Unit,
    onFollowUp: () -> Unit,
    onStartOver: () -> Unit,
    onClose: () -> Unit,
) {
    val fg = color(theme.text)
    val dim = color(theme.secondaryText)
    val amber = color(Tokens.AMBER)
    when (val o = s.outcome) {
        is AskOutcome.Answer -> {
            val plan = remember(o) { o.reply.open?.let { host.planOpen(it) } }
            AnswerText(o.reply.answer, theme, Modifier.weight(1f))
            plan?.let {
                Spacer(Modifier.height(8.dp))
                Line(it.description, dim)
            }
            var openFailed by remember(o) { mutableStateOf(false) }
            if (openFailed) Line("Couldn't open ${o.reply.open?.app?.label.orEmpty()}.", amber)
            Buttons(theme, s, focusFirst = false) {
                if (plan != null) button("Open in ${o.reply.open!!.app.label}") { openFailed = !plan.launch() }
                if (o.canFollowUp) button("Follow up") { onFollowUp() } else button("Start over") { onStartOver() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.NotSetUp -> {
            Message(AskCopy.NOT_SET_UP_TITLE, AskCopy.NOT_SET_UP_DETAIL, fg, dim)
            Buttons(theme, s) { button("Close") { onClose() } }
        }
        AskOutcome.CantReach -> {
            Message(AskCopy.CANT_REACH, AskCopy.CANT_REACH_DETAIL, amber, dim)
            Buttons(theme, s) {
                button("Retry") { onRetry() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.KeyRejected -> {
            Message(AskCopy.KEY_REJECTED, AskCopy.KEY_REJECTED_DETAIL, amber, dim)
            Buttons(theme, s) { button("Done") { onClose() } }
        }
        AskOutcome.RequestRejected -> {
            Message(AskCopy.REQUEST_REJECTED, AskCopy.REQUEST_REJECTED_DETAIL, amber, dim)
            Buttons(theme, s) {
                button("Try again") { onFollowUp() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.Busy -> {
            Message(AskCopy.BUSY, AskCopy.BUSY_DETAIL, fg, dim)
            Buttons(theme, s) {
                button("Retry") { onRetry() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.Unworkable -> {
            Message(AskCopy.UNWORKABLE, null, fg, dim)
            Buttons(theme, s) {
                if (engine.canAsk) button("Try again") { onFollowUp() } else button("Start over") { onStartOver() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.CapReached -> {
            Message(AskCopy.CAP_REACHED, null, fg, dim)
            Buttons(theme, s) { button("Done") { onClose() } }
        }
        AskOutcome.FollowUpUsed -> {
            Message(AskCopy.FOLLOW_UP_USED, null, fg, dim)
            Buttons(theme, s) {
                button("Start over") { onStartOver() }
                button("Done") { onClose() }
            }
        }
        AskOutcome.EmptyQuestion -> {
            Message(AskCopy.EMPTY_QUESTION, null, fg, dim)
            Buttons(theme, s) { button("Try again") { onFollowUp() } }
        }
    }
}

@Composable
private fun Line(text: String, c: Color) {
    Text(text, color = c, fontSize = Tokens.SECONDARY_SP.sp)
}

@Composable
private fun Message(title: String, detail: String?, titleColour: Color, dim: Color) {
    Text(title, color = titleColour, fontSize = Tokens.HEADING_SP.sp, fontWeight = FontWeight.Medium)
    detail?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = dim, fontSize = Tokens.ROW_SP.sp)
    }
}

/**
 * The answer, focusable so D-pad Up/Down scrolls it a few lines at a time. At the bottom (or when it fits) Down is
 * not consumed and moves focus on to the buttons; Up at the top is likewise left alone. Takes focus when shown.
 */
@Composable
private fun AnswerText(answer: String, theme: HomeTheme, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    LaunchedEffect(answer) { focusSoon(requester) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(if (focused) color(theme.focusFill) else Color.Transparent, shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = SCROLL_STEP_PX
                when (e.key) {
                    Key.DirectionDown -> (scroll.value < scroll.maxValue).also { if (it) scope.launch { scroll.animateScrollBy(step) } }
                    Key.DirectionUp -> (scroll.value > 0).also { if (it) scope.launch { scroll.animateScrollBy(-step) } }
                    else -> false
                }
            }
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { contentDescription = answer },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(16.dp)) {
            Text(answer, color = color(theme.text), fontSize = Tokens.ROW_SP.sp)
        }
    }
}

private const val SCROLL_STEP_PX = 240f

/** The typed question. Focus goes to the field; the keyboard's action key sends. */
@Composable
private fun QuestionField(text: String, theme: HomeTheme, onChange: (String) -> Unit, onSend: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    LaunchedEffect(Unit) { focusSoon(requester) }
    BasicTextField(
        value = text,
        onValueChange = { onChange(it.take(tvhome.core.assistant.AskConfig.MAX_QUESTION_CHARS)) },
        singleLine = true,
        textStyle = TextStyle(color = color(theme.text), fontSize = Tokens.ROW_SP.sp),
        cursorBrush = SolidColor(color(theme.focusRing)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.ROW_HEIGHT_DP.dp)
            .background(color(theme.tile), shape)
            .border(if (focused) Tokens.RING_DP.dp else 1.dp, color(if (focused) theme.focusRing else theme.secondaryText), shape)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "Your question" }
            .padding(16.dp),
    )
}

private class ButtonSpec(val label: String, val onClick: () -> Unit)

private class ButtonsScope {
    val specs = ArrayList<ButtonSpec>()
    fun button(label: String, onClick: () -> Unit) { specs += ButtonSpec(label, onClick) }
}

/** A row of buttons. With [focusFirst], the first button takes focus each time [key] changes. */
@Composable
private fun Buttons(theme: HomeTheme, key: Any, focusFirst: Boolean = true, content: ButtonsScope.() -> Unit) {
    val specs = ButtonsScope().apply(content).specs
    val first = remember(key) { FocusRequester() }
    if (focusFirst) LaunchedEffect(key) { focusSoon(first) }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        specs.forEachIndexed { i, b -> AskButton(b.label, theme, if (i == 0) first else null, b.onClick) }
    }
}

@Composable
private fun AskButton(label: String, theme: HomeTheme, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .background(color(if (focused) theme.focusFill else theme.tile), shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color(theme.text), fontSize = Tokens.SECONDARY_SP.sp, fontWeight = FontWeight.Medium)
    }
}

/** Requests focus after the next frame, retrying once (the target may not be attached yet). */
private suspend fun focusSoon(target: FocusRequester) {
    androidx.compose.runtime.withFrameNanos { }
    if (runCatching { target.requestFocus() }.isFailure) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { target.requestFocus() }
    }
}
