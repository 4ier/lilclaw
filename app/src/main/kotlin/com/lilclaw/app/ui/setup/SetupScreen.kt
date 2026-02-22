package com.lilclaw.app.ui.setup

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import org.koin.androidx.compose.koinViewModel

private val PROVIDERS = listOf("DeepSeek", "OpenAI", "Anthropic", "AWS Bedrock", "自定义")

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.step == SetupStep.DONE) {
        onSetupComplete()
        return
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = state.step,
                label = "setup_step",
                transitionSpec = {
                    (fadeIn(tween(400)) + slideInVertically { it / 8 })
                        .togetherWith(fadeOut(tween(200)) + slideOutVertically { -it / 8 })
                },
            ) { step ->
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep(onNext = viewModel::onGetStarted)
                    SetupStep.PROVIDER -> ProviderStep(
                        state = state,
                        onProviderChanged = viewModel::onProviderChanged,
                        onApiKeyChanged = viewModel::onApiKeyChanged,
                        onModelChanged = viewModel::onModelChanged,
                        onContinue = viewModel::onContinue,
                    )
                    SetupStep.LAUNCHING -> LaunchingStep(
                        state = state,
                        onRetry = viewModel::onRetry,
                    )
                    SetupStep.DONE -> { /* handled above */ }
                }
            }
        }
    }
}

// ── Welcome ───────────────────────────────────────────────

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp),
        ) {
            PawPrintCanvas(
                modifier = Modifier.size((96 * breathe).dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "小爪",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "LilClaw",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "你的口袋 AI 助手",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(64.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("开始使用", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── Provider Configuration ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderStep(
    state: SetupState,
    onProviderChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "连接 AI 服务",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "选择服务商，输入你的 API Key",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PROVIDERS.forEach { provider ->
                FilterChip(
                    selected = state.provider == provider,
                    onClick = { onProviderChanged(provider) },
                    label = { Text(provider, style = MaterialTheme.typography.bodyMedium) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // API Key — native EditText for adb input text compatibility
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = "API Key"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    maxLines = 1
                    setSingleLine(true)
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    textSize = 16f
                    setPadding(32, 24, 32, 24)
                    background = ctx.getDrawable(android.R.drawable.edit_text)
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    doAfterTextChanged { editable ->
                        onApiKeyChanged(editable?.toString() ?: "")
                    }
                }
            },
            update = { editText ->
                // Only update if truly different (avoid cursor jump)
                val current = editText.text?.toString() ?: ""
                if (current != state.apiKey) {
                    editText.setText(state.apiKey)
                    editText.setSelection(state.apiKey.length)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Model — native EditText for adb input text compatibility
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = "模型（选填）"
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    maxLines = 1
                    setSingleLine(true)
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    textSize = 16f
                    setPadding(32, 24, 32, 24)
                    background = ctx.getDrawable(android.R.drawable.edit_text)
                    doAfterTextChanged { editable ->
                        onModelChanged(editable?.toString() ?: "")
                    }
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            clearFocus()
                            if (state.provider.isNotEmpty() && state.apiKey.isNotEmpty()) {
                                onContinue()
                            }
                            true
                        } else false
                    }
                }
            },
            update = { editText ->
                val current = editText.text?.toString() ?: ""
                if (current != state.model) {
                    editText.setText(state.model)
                    editText.setSelection(state.model.length)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = onContinue,
            enabled = state.provider.isNotEmpty() && state.apiKey.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("继续", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── Launching (the magic screen) ──────────────────────────

@Composable
private fun LaunchingStep(
    state: SetupState,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Auto-scroll log to bottom
    LaunchedEffect(state.logLines.size) {
        if (state.logLines.isNotEmpty()) {
            listState.animateScrollToItem(state.logLines.size - 1)
        }
    }

    // Smooth progress animation
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "progress",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Center: paw animation + status text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .padding(bottom = 140.dp),
        ) {
            // Orbital paw animation
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp),
            ) {
                OrbitalGlow(
                    modifier = Modifier.size(160.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                PawPrintCanvas(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Status text — smooth crossfade
            AnimatedContent(
                targetState = state.statusText,
                label = "status",
                transitionSpec = {
                    fadeIn(tween(400)).togetherWith(fadeOut(tween(200)))
                },
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            // Error + retry
            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 4,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("重试")
                }
            }

            // Slim progress bar
            if (state.error == null) {
                Spacer(Modifier.height(24.dp))
                GradientProgressBar(
                    progress = animatedProgress,
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(3.dp),
                )
            }
        }

        // Bottom: boot console (very subtle)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = state.logLines,
                    key = { index, _ -> index },
                ) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Canvas Components ─────────────────────────────────────

/**
 * Paw print — four toe beans + palm pad.
 * Proportioned to look natural and friendly.
 */
@Composable
private fun PawPrintCanvas(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Toe beans — elliptical, arranged in a natural arc
        val toeRx = w * 0.105f
        val toeRy = h * 0.12f
        val toeRow = h * 0.22f

        val toes = listOf(
            Offset(cx - w * 0.25f, toeRow + h * 0.06f),  // outer left
            Offset(cx - w * 0.09f, toeRow - h * 0.02f),  // inner left
            Offset(cx + w * 0.09f, toeRow - h * 0.02f),  // inner right
            Offset(cx + w * 0.25f, toeRow + h * 0.06f),  // outer right
        )

        for (toe in toes) {
            drawOval(
                color = color,
                topLeft = Offset(toe.x - toeRx, toe.y - toeRy),
                size = Size(toeRx * 2, toeRy * 2),
            )
        }

        // Palm pad — heart-ish shape approximated as a wide oval
        val palmCx = cx
        val palmCy = h * 0.58f
        val palmRx = w * 0.22f
        val palmRy = h * 0.18f

        drawOval(
            color = color,
            topLeft = Offset(palmCx - palmRx, palmCy - palmRy),
            size = Size(palmRx * 2, palmRy * 2),
        )
    }
}

/**
 * Soft rotating glow rings orbiting around the paw.
 */
@Composable
private fun OrbitalGlow(
    modifier: Modifier = Modifier,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "orbital")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.85f
        val arcRect = Offset(cx - r, cy - r)
        val arcSize = Size(r * 2, r * 2)

        // Primary arc
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    color.copy(alpha = 0f),
                    color.copy(alpha = pulse),
                    color.copy(alpha = pulse * 0.7f),
                    color.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
            ),
            startAngle = rotation,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = arcRect,
            size = arcSize,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )

        // Secondary arc (opposite side, dimmer)
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    color.copy(alpha = 0f),
                    color.copy(alpha = pulse * 0.35f),
                    color.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
            ),
            startAngle = rotation + 180f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = arcRect,
            size = arcSize,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * Minimal gradient progress bar.
 */
@Composable
private fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val h = size.height
        val cr = CornerRadius(h / 2f)

        // Track
        drawRoundRect(color = track, cornerRadius = cr)

        // Fill
        if (progress > 0.001f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(primary.copy(alpha = 0.5f), primary),
                ),
                size = size.copy(width = size.width * progress.coerceIn(0f, 1f)),
                cornerRadius = cr,
            )
        }
    }
}
