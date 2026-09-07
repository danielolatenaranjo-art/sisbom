package com.sisbom.misisbom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import coil.compose.AsyncImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import android.widget.Toast
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Divider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun getVisibleTabs(isCentralActive: Boolean, user: UserPersonal?): List<MainTab> {
    val list = mutableListOf<MainTab>()
    val cargo = user?.cargo?.trim()?.uppercase() ?: ""
    val isHonorario = cargo == "BOMBERO HONORARIO"
    val idRadial = user?.idRadial ?: ""

    if (!isHonorario) {
        if (isCentralActive) {
            list.add(MainTab.Despacho)
        } else {
            list.add(MainTab.Actividad)
        }
    }
    
    list.add(MainTab.Ordenes)
    list.add(MainTab.Alertas)
    
    if (!isHonorario) {
        list.add(MainTab.Asistencia)
        if (isCentralActive || idRadial == "1") {
            list.add(MainTab.Disponibles)
        }
    }
    return list
}

fun formatFirefighterName(fullName: String): String {
    val words = fullName.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    if (words.size <= 2) return fullName
    val first = words.first()
    val penultimate = words[words.size - 2]
    return "$first $penultimate"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: SisBomViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkMode.current

    val visibleTabs = remember(viewModel.isCentralActive, viewModel.currentUser) {
        getVisibleTabs(viewModel.isCentralActive, viewModel.currentUser)
    }

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = visibleTabs.indexOf(viewModel.currentTab).coerceAtLeast(0),
        pageCount = { visibleTabs.size }
    )

    // Sincronizar tab actual al cambiar isCentralActive
    androidx.compose.runtime.LaunchedEffect(viewModel.isCentralActive) {
        if (viewModel.isCentralActive && viewModel.currentTab == MainTab.Actividad) {
            viewModel.currentTab = MainTab.Despacho
        } else if (!viewModel.isCentralActive && viewModel.currentTab == MainTab.Despacho) {
            viewModel.currentTab = MainTab.Actividad
        }
    }

    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Sincronizar pager con viewModel
    androidx.compose.runtime.LaunchedEffect(viewModel.currentTab, visibleTabs) {
        val index = visibleTabs.indexOf(viewModel.currentTab)
        if (index != -1 && index != pagerState.currentPage) {
            isProgrammaticScroll = true
            pagerState.animateScrollToPage(index)
            isProgrammaticScroll = false
        }
    }

    // Sincronizar viewModel con pager inmediatamente al deslizar
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        if (!isProgrammaticScroll) {
            val targetTab = visibleTabs[pagerState.currentPage]
            if (viewModel.currentTab != targetTab) {
                viewModel.currentTab = targetTab
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ProfileDrawerContent(viewModel) {
                scope.launch { drawerState.close() }
            }
        }
    ) {
        SisBomBackground {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    val statusBarPadding = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val screenHeightPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
                    val navigationBarPadding = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                    // TopAppBarView height ~140.dp (or ~230.dp / ~320.dp for Asistencia totals), BottomNavigationBarView height ~88.dp
                    val saasTopExtra = if (viewModel.isReadOnly) 32.dp else 0.dp
                    val today = java.util.Date()
                    val cal = java.util.Calendar.getInstance()
                    cal.time = today
                    val todayMonth = cal.get(java.util.Calendar.MONTH)
                    val todayDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val isDecember8th = (todayMonth == java.util.Calendar.DECEMBER && todayDay == 8)

                    val topPaddingValue = 112.dp
                    val customPaddingValues = PaddingValues(
                        top = topPaddingValue + saasTopExtra,
                        bottom = navigationBarPadding + 88.dp
                    )

                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = statusBarPadding + 58.dp)
                            .clipToBounds()
                    ) { page ->
                        when (visibleTabs[page]) {
                            MainTab.Actividad -> ActividadTab(viewModel, customPaddingValues)
                            MainTab.Despacho -> DespachoTab(viewModel, customPaddingValues)
                            MainTab.Ordenes -> OrdenesTab(viewModel, customPaddingValues)
                            MainTab.Alertas -> AlertasTab(viewModel, customPaddingValues)
                            MainTab.Asistencia -> AsistenciaTab(viewModel, customPaddingValues)
                            MainTab.Disponibles -> DisponiblesTab(viewModel, customPaddingValues)
                        }
                    }

                    // Cabecera flotante en la parte superior
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TopAppBarView(viewModel) {
                                scope.launch { drawerState.open() }
                            }
                            if (viewModel.isReadOnly) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .background(AlertAmber)
                                        .border(0.5.dp, Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "SUSCRIPCIÓN EN MODO LECTURA (SaaS)",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Barra de navegación flotante en la parte inferior
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    ) {
                        BottomNavigationBarView(viewModel)
                    }

                    // Chat overlay
                    viewModel.activeChatAlert?.let { alert ->
                        ChatWindow(
                            alert = alert,
                            viewModel = viewModel,
                            onDismiss = { viewModel.activeChatAlert = null }
                        )
                    }
                }
            }
        }
    }

    if (viewModel.showChangelogDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissChangelog() },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = null,
                        tint = BomberosRed,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "¡NUEVA ACTUALIZACIÓN!",
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        color = if (isDark) Color.White else TextDark
                    )
                    Text(
                        text = "SENTINEL ONE V ${viewModel.appVersionName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChangelogItem(
                        icon = Icons.Filled.Settings,
                        title = "Logo e Icono Personalizado",
                        desc = "Soporte para cambiar dinámicamente el icono de lanzamiento y almacenar en caché el logotipo oficial de su institución.",
                        isDark = isDark
                    )
                    ChangelogItem(
                        icon = Icons.Filled.Cloud,
                        title = "Descarga de Actualizaciones",
                        desc = "Corrección en la visualización del progreso de descargas OTA al evitar la compresión en tránsito.",
                        isDark = isDark
                    )
                    ChangelogItem(
                        icon = Icons.Filled.Lock,
                        title = "Seguridad del Portal",
                        desc = "Se removió la opción de cambiar organización en la pantalla de login para evitar desvinculaciones accidentales.",
                        isDark = isDark
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissChangelog() },
                    colors = ButtonDefaults.buttonColors(containerColor = BomberosRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ENTENDIDO",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            },
            containerColor = if (isDark) Color(0xFF0F172A) else Color.White,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun ChangelogItem(
    icon: ImageVector,
    title: String,
    desc: String,
    isDark: Boolean
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BomberosRed.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BomberosRed,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isDark) Color.White else TextDark
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
    }
}

@Composable
fun TopAppBarView(viewModel: SisBomViewModel, onMenuClick: () -> Unit) {
    val user = viewModel.currentUser ?: return
    val isDark = LocalDarkMode.current

    val rawEstado = user.estado.trim().uppercase()
    val inService = user.enServicio.trim() != "0" && user.enServicio.trim().isNotEmpty() && !user.enServicio.trim().startsWith("-")

    val is09Active = rawEstado == "0-9"
    val is08Active = rawEstado == "0-8" || rawEstado.contains("SUSPENDIDO") || rawEstado == "CDS" || rawEstado.contains("LICENCIA") || rawEstado == "PERMISO"

    val is09Enabled = !inService && !rawEstado.contains("SUSPENDIDO")
    val is08Enabled = !inService

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo SENTINEL ONE (Adaptativo Claro/Oscuro)
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(
                id = if (isDark) R.drawable.sentinel_one_logo else R.drawable.sentinel_one_logo_light
            ),
            contentDescription = "SENTINEL ONE",
            modifier = Modifier
                .height(24.dp)
                .padding(bottom = 6.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )

        // 2. Tarjeta con efecto Glassmorphism teñido del color del estado (Altamente diferenciable)
        val glassBgColor = when {
            rawEstado == "0-9" -> {
                if (isDark) Color(0xFF0F3D1C) else Color(0xFFC8E6C9)
            }
            rawEstado == "0-8" -> {
                if (isDark) Color(0xFF5C1414) else Color(0xFFFFCDD2)
            }
            rawEstado.contains("PERMISO") -> {
                if (isDark) Color(0xFF5C3308) else Color(0xFFFFE0B2)
            }
            rawEstado.contains("LICENCIA") -> {
                if (isDark) Color(0xFF093E5C) else Color(0xFFB3E5FC)
            }
            rawEstado == "CDS" -> {
                if (isDark) Color(0xFF5C4F08) else Color(0xFFFFF9C4)
            }
            rawEstado.contains("SUSPENDIDO") -> {
                if (isDark) Color(0xFF3B0C54) else Color(0xFFE1BEE7)
            }
            else -> {
                if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
            }
        }

        val cardBorderColor = when {
            rawEstado == "0-9" -> {
                if (isDark) Color(0xFF00B050).copy(alpha = 0.5f) else Color(0xFF81C784)
            }
            rawEstado == "0-8" -> {
                if (isDark) Color(0xFFFF3B30).copy(alpha = 0.5f) else Color(0xFFE57373)
            }
            rawEstado.contains("PERMISO") -> {
                if (isDark) Color(0xFFFF9500).copy(alpha = 0.5f) else Color(0xFFFFB74D)
            }
            rawEstado.contains("LICENCIA") -> {
                if (isDark) Color(0xFF34A8DF).copy(alpha = 0.5f) else Color(0xFF4FC3F7)
            }
            rawEstado == "CDS" -> {
                if (isDark) Color(0xFFD1A100).copy(alpha = 0.5f) else Color(0xFFFFF176)
            }
            rawEstado.contains("SUSPENDIDO") -> {
                if (isDark) Color(0xFF8E24AA).copy(alpha = 0.5f) else Color(0xFFBA68C8)
            }
            else -> {
                if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f)
            }
        }

        val cardTextColor = if (isDark) Color.White else Color(0xFF0F172A)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(glassBgColor)
                .border(1.dp, cardBorderColor, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // NOMBRE CUERPO BOMBEROS (Dentro de la tarjeta, arriba al centro)
            Text(
                text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CUERPO DE BOMBEROS",
                color = if (isDark) Color.White.copy(alpha = 0.9f) else Color(0xFF1E293B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Fila con Avatar, Nombre e ID/Cargo a la izquierda, y botones 09/08 a la derecha
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar (Imagen en cuadrado con bordes ligeramente redondeados como Figma)
                val photoModel = remember(user.foto) {
                    val clean = user.foto.trim()
                    if (clean.isNotEmpty()) {
                        val decoded = decodeBase64ToBitmap(clean)
                        if (decoded != null) {
                            decoded
                        } else if (!clean.contains(" ") && (clean.startsWith("http") || clean.contains("/"))) {
                            clean
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable { onMenuClick() } // Clic abre drawer/perfil
                ) {
                    if (photoModel is android.graphics.Bitmap) {
                        androidx.compose.foundation.Image(
                            bitmap = photoModel.asImageBitmap(),
                            contentDescription = "Avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else if (photoModel != null) {
                        AsyncImage(
                            model = photoModel,
                            contentDescription = "Avatar",
                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = viewModel.getClientLogoModel(),
                            contentDescription = "Avatar",
                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Nombre y Radial/Cargo
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onMenuClick() }
                ) {
                    Text(
                        text = formatFirefighterName(user.nombreBombero),
                        color = cardTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${user.idRadial} - ${user.cargo}",
                        color = cardTextColor.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Botones de Disponibilidad 09 y 08 en la cabecera (Agrandados)
                if (viewModel.isCentralActive) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 116.dp, height = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF00B050).copy(alpha = 0.15f) else Color(0xFF00B050))
                            .border(
                                1.dp,
                                if (isDark) Color(0xFF00B050).copy(alpha = 0.5f) else Color(0xFF00B050),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        Text(
                            text = "OPERADOR",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botón 09 (Verde)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 54.dp, height = 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (is09Active) Color(0xFF00B050) else Color(0xFF00B050).copy(alpha = 0.15f)
                                )
                                .border(
                                    1.dp,
                                    if (is09Active) Color(0xFF00B050) else Color(0xFF00B050).copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = is09Enabled) {
                                    viewModel.changeStatus("0-9")
                                }
                        ) {
                            Text(
                                text = "09",
                                color = if (is09Active) Color.White else (if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF00B050)),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Botón 08 (Rojo)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 54.dp, height = 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (is08Active) Color(0xFFFF3B30) else Color(0xFFFF3B30).copy(alpha = 0.15f)
                                )
                                .border(
                                    1.dp,
                                    if (is08Active) Color(0xFFFF3B30) else Color(0xFFFF3B30).copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = is08Enabled) {
                                    viewModel.changeStatus("0-8")
                                }
                        ) {
                            Text(
                                text = "08",
                                color = if (is08Active) Color.White else (if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFFFF3B30)),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Línea Divisoria (Figma)
            Divider(
                color = cardTextColor.copy(alpha = 0.15f),
                thickness = 1.dp,
                modifier = Modifier.fillMaxWidth()
            )

            // 4. Título Dinámico de Sección
            val tabTitle = when (viewModel.currentTab) {
                MainTab.Despacho -> "DESPACHOS ACTIVOS"
                MainTab.Actividad -> "DESPACHOS ACTIVOS"
                MainTab.Ordenes -> "ORDENES DEL DIA"
                MainTab.Alertas -> "MURO DE COMUNICACIONES"
                MainTab.Asistencia -> "HISTORIAL DE ASISTENCIAS"
                MainTab.Disponibles -> "PERSONAL DISPONIBLE"
            }
            val parts = tabTitle.split(" ")
            if (parts.size >= 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, start = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = parts[0] + " ",
                        color = cardTextColor.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = parts.subList(1, parts.size).joinToString(" "),
                        color = cardTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }


        }
    }
}

@Composable
fun BottomNavigationBarView(viewModel: SisBomViewModel) {
    val radial = viewModel.currentUser?.idRadial ?: ""
    val unreadAlerts = viewModel.alertsList.count { a ->
        a.duracion == "C" && !a.conforme.split(",").map { it.trim() }.contains(radial)
    }
    val unreadOrdenes = viewModel.alertsList.count { a ->
        a.tipo == "orden" && !a.conforme.split(",").map { it.trim() }.contains(radial)
    }

    val isDark = LocalDarkMode.current

    // Lista de pestañas visibles según estado de central
    val visibleTabs = remember(viewModel.isCentralActive, viewModel.currentUser) {
        getVisibleTabs(viewModel.isCentralActive, viewModel.currentUser)
    }

    val activeIndex = visibleTabs.indexOf(viewModel.currentTab)

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color.Transparent)
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 6.dp)
    ) {
        // Contenedor flotante tipo píldora Glassmorphism premium
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(28.dp), spotColor = if (isDark) Color.Black else Color(0x33000000))
                .background(
                    if (isDark) Color(0xFF0F172A).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f),
                    RoundedCornerShape(28.dp)
                )
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0),
                    RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val totalWidth = maxWidth
                val tabCount = visibleTabs.size
                val tabWidth = totalWidth / tabCount

                if (activeIndex != -1) {
                    // Píldora activa con física de resorte (Spring)
                    val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
                        targetValue = tabWidth * activeIndex + (tabWidth - 60.dp) / 2,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.78f,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        ),
                        label = "PointerOffset"
                    )

                    Box(
                        modifier = Modifier
                            .offset(x = indicatorOffset, y = 8.dp)
                            .size(width = 60.dp, height = 52.dp)
                            .background(
                                color = if (isDark) Color(0xFFEF4444).copy(alpha = 0.16f) else Color(0xFFDC2626).copy(alpha = 0.10f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp,
                                if (isDark) Color(0xFFEF4444).copy(alpha = 0.35f) else Color(0xFFDC2626).copy(alpha = 0.25f),
                                RoundedCornerShape(20.dp)
                            )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleTabs.forEach { tab ->
                        val isSelected = viewModel.currentTab == tab
                        val icon = when (tab) {
                            MainTab.Actividad -> Icons.Filled.LocalFireDepartment
                            MainTab.Despacho -> Icons.Filled.FlashOn
                            MainTab.Ordenes -> Icons.Filled.Assignment
                            MainTab.Alertas -> Icons.Filled.Notifications
                            MainTab.Asistencia -> Icons.Filled.DateRange
                            MainTab.Disponibles -> Icons.Filled.People
                        }
                        val label = when (tab) {
                            MainTab.Actividad -> "Actividad"
                            MainTab.Despacho -> "Despacho"
                            MainTab.Ordenes -> "Órdenes"
                            MainTab.Alertas -> "Alertas"
                            MainTab.Asistencia -> "Asistencia"
                            MainTab.Disponibles -> "Disponibles"
                        }
                        val badgeCount = when (tab) {
                            MainTab.Ordenes -> unreadOrdenes
                            MainTab.Alertas -> unreadAlerts
                            else -> 0
                        }

                        val iconScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.7f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                            ),
                            label = "IconScale"
                        )

                        val itemColor by androidx.compose.animation.animateColorAsState(
                            targetValue = if (isSelected) {
                                if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
                            } else {
                                if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            },
                            label = "ItemColor"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (!isSelected) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            viewModel.currentTab = tab
                                        }
                                    }
                                )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.height(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.TopEnd) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = itemColor,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                                    )
                                    if (badgeCount > 0) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .offset(x = 6.dp, y = (-2).dp)
                                                .size(9.dp)
                                                .background(Color(0xFFEF4444), CircleShape)
                                                .border(1.5.dp, if (isDark) Color(0xFF0F172A) else Color.White, CircleShape)
                                        ) {}
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                color = itemColor,
                                fontSize = if (isSelected) 9.5.sp else 9.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                letterSpacing = if (isSelected) 0.3.sp else 0.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileDrawerContent(viewModel: SisBomViewModel, onClose: () -> Unit) {
    val user = viewModel.currentUser ?: return
    val isDark = LocalDarkMode.current
    val drawerBg = if (isDark) Color(0xFF050508) else Color.White
    val borderCol = if (isDark) Color(0xFF1E293B) else BgCream
    val textColor = if (isDark) Color.White else TextDark
    val textSecColor = if (isDark) Color(0xFF94A3B8) else TextSecondary

    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showChangePasswordFields by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(drawerBg)
            .border(1.dp, borderCol)
            .statusBarsPadding()
            .padding(top = 20.dp, bottom = 20.dp, start = 16.dp, end = 16.dp)
            .verticalScroll(scrollState)
    ) {
        // Cabecera del Perfil con Foto, Nombre y Botón Cerrar (X)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, BomberosRed, RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0xFF1E293B) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val photoModel = remember(user.foto) {
                        val clean = user.foto.trim()
                        if (clean.isNotEmpty()) {
                            val decoded = decodeBase64ToBitmap(clean)
                            if (decoded != null) {
                                decoded
                            } else if (!clean.contains(" ") && (clean.startsWith("http") || clean.contains("/"))) {
                                clean
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    if (photoModel is android.graphics.Bitmap) {
                        androidx.compose.foundation.Image(
                            bitmap = photoModel.asImageBitmap(),
                            contentDescription = "Foto de perfil",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else if (photoModel != null) {
                        AsyncImage(
                            model = photoModel,
                            contentDescription = "Foto de perfil",
                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = viewModel.getClientLogoModel(),
                            contentDescription = "Logo",
                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = user.nombreBombero.uppercase(),
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = user.cargo.uppercase(),
                        color = BomberosRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                    .border(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    tint = if (isDark) Color.White else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0), thickness = 1.dp)
        Spacer(modifier = Modifier.height(20.dp))

        // Sección: IDENTIFICACIÓN OFICIAL
        Text(
            text = "IDENTIFICACIÓN OFICIAL",
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // N° Registro
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.3f) else Color(0xFFF8FAFC))
                    .border(1.dp, if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "N° REGISTRO",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = user.idRegistro,
                        color = textColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            // ID Radial
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF311010).copy(alpha = 0.3f) else Color(0xFFFEF2F2))
                    .border(1.dp, if (isDark) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFFFEE2E2), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ID RADIAL",
                        color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFEF4444),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = user.idRadial,
                        color = if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // Sección: APARIENCIA DE LA APP
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "APARIENCIA DE LA APP",
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.3f) else Color.White)
                .border(1.dp, if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Modo Visual",
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = isDark,
                    onCheckedChange = { viewModel.setDarkModeEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF3B82F6),
                        uncheckedThumbColor = Color(0xFF64748B),
                        uncheckedTrackColor = Color(0xFFE2E8F0)
                    )
                )
            }
        }

        // Sección: MODO AVIÓN (0-8 ABSOLUTO)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "MODO DE NOTIFICACIONES",
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.3f) else Color.White)
                .border(1.dp, if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo Avión (0-8 Absoluto)",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Silencia y bloquea toda alerta",
                        color = textSecColor,
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = viewModel.isAirplaneMode,
                    onCheckedChange = { viewModel.setAirplaneModeEnabled(it) },
                    enabled = !viewModel.isCentralActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BomberosRed,
                        uncheckedThumbColor = Color(0xFF64748B),
                        uncheckedTrackColor = Color(0xFFE2E8F0)
                    )
                )
            }
        }

        // Sección: SEGURIDAD
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SEGURIDAD",
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (!showChangePasswordFields) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.3f) else Color(0xFFF1F5F9))
                    .border(1.dp, if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                    .clickable { showChangePasswordFields = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Lock",
                        tint = if (isDark) Color.White else textSecColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CAMBIAR CONTRASEÑA",
                        color = if (isDark) Color.White else textSecColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = currentPass,
                    onValueChange = { currentPass = it },
                    label = { Text("Contraseña Actual") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BomberosRed,
                        unfocusedBorderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedLabelColor = BomberosRed,
                        unfocusedLabelColor = textSecColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text("Contraseña Nueva") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BomberosRed,
                        unfocusedBorderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedLabelColor = BomberosRed,
                        unfocusedLabelColor = textSecColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPass,
                    onValueChange = { confirmPass = it },
                    label = { Text("Repetir Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BomberosRed,
                        unfocusedBorderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedLabelColor = BomberosRed,
                        unfocusedLabelColor = textSecColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.changePasswordError.isNotEmpty()) {
                    Text(viewModel.changePasswordError, color = BomberosRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (viewModel.changePasswordSuccess.isNotEmpty()) {
                    Text(viewModel.changePasswordSuccess, color = GoGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.changePassword(currentPass, newPass, confirmPass) },
                    colors = ButtonDefaults.buttonColors(containerColor = BomberosRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ACTUALIZAR CLAVE", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { showChangePasswordFields = false },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("OCULTAR", color = if (isDark) Color.White else textSecColor, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // Sección: APERTURA DE PUERTA (Only for Comandante or active Central Operator or user.puerta == true)
        val isComandante = user.cargo.trim().uppercase() == "COMANDANTE"
        val isCentralOperator = viewModel.isCentralActive
        val hasPuertaPermission = user.puerta
        if (isComandante || isCentralOperator || hasPuertaPermission) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ACCESOS",
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            
            var isOpening by remember { mutableStateOf(false) }
            
            Button(
                onClick = {
                    isOpening = true
                    viewModel.openDoor(
                        onSuccess = {
                            isOpening = false
                            Toast.makeText(viewModel.getApplication(), "Puerta abierta con éxito", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = {
                            isOpening = false
                            Toast.makeText(viewModel.getApplication(), "Error al abrir la puerta: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoGreen),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isOpening
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isOpening) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = "Abrir Puerta",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "APERTURA DE PUERTA",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // Sección: TURNO DE CENTRAL DE ALARMAS
        val isOpActive = viewModel.centralOperatorName.isNotEmpty()
        val isComandanteOp = user.cargo.trim().uppercase() == "COMANDANTE" && listOf("1", "01", "2", "02", "3", "03").contains(user.idRadial.trim())
        val canCloseOp = isOpActive && (viewModel.isCentralActive || (viewModel.centralOperatorId.isNotEmpty() && viewModel.centralOperatorId == user.idRegistro) || isComandanteOp)

        if (canCloseOp) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "TURNO CENTRAL DE ALARMAS",
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    viewModel.closeCentralOperatorSession()
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cerrar Turno",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CERRAR TURNO DE CENTRAL",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(
                    id = if (isDark) R.drawable.sentinel_one_logo else R.drawable.sentinel_one_logo_light
                ),
                contentDescription = "SENTINEL ONE",
                modifier = Modifier
                    .height(34.dp)
                    .padding(horizontal = 8.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "V ${viewModel.appVersionName}",
                color = Color(0xFFD97706),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
