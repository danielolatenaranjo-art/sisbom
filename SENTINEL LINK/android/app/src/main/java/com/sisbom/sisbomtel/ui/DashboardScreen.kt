package com.sisbom.sisbomtel.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sisbom.sisbomtel.R
import com.sisbom.sisbomtel.TelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: TelViewModel
) {
    var loginUser by remember { mutableStateOf("") }
    var loginPass by remember { mutableStateOf("") }
    var isPassVisible by remember { mutableStateOf(false) }
    var showCloseConfirmDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val activeSession = viewModel.activeCentralSession
    val isSessionActive = activeSession != null && activeSession.estado == "activo"

    Scaffold(
        containerColor = TelColors.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.sentinel_link_logo),
                            contentDescription = "SENTINEL LINK",
                            modifier = Modifier
                                .height(36.dp)
                                .padding(vertical = 2.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TelColors.SurfaceDark
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==========================================
            // HEADER: LOGO Y NOMBRE CUERPO DE BOMBEROS
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (viewModel.saasLogoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = viewModel.saasLogoUrl,
                        contentDescription = "Logo Cuerpo de Bomberos",
                        modifier = Modifier.size(110.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Escudo Oficial",
                        modifier = Modifier.size(110.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CUERPO DE BOMBEROS",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ==========================================
            // CENTRO: TARJETA DE CONTROL SESIÓN SENTINEL CAD (ESTILO LOGIN CAD)
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(
                        width = 1.5.dp,
                        color = if (isSessionActive) Color(0xFF10B981) else Color(0xFFB91C1C).copy(alpha = 0.8f),
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = TelColors.SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    // Encabezado de la Tarjeta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(if (isSessionActive) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSessionActive) "SENTINEL CAD Operativo" else "SENTINEL CAD sin operador",
                                color = if (isSessionActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSessionActive) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isSessionActive) "EN TURNO" else "INACTIVO",
                                color = if (isSessionActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isSessionActive && activeSession != null) {
                        // ==========================================
                        // ESTADO ACTIVO: DETALLES DEL OPERADOR Y BOTÓN CERRAR
                        // ==========================================
                        Text(
                            text = activeSession.nombreBombero.ifEmpty { "Operador de Guardia" },
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${activeSession.cargo} • ID: ${activeSession.idRegistro.ifEmpty { activeSession.operador }}",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Ingreso: ${activeSession.fechaIngreso} ${activeSession.horaIngreso}",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = { showCloseConfirmDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFEF4444)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFEF4444))
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PowerSettingsNew,
                                contentDescription = "Cerrar Sesión",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CERRAR SESIÓN EN CENTRAL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // ==========================================
                        // ESTADO INACTIVO: FORMULARIO DE LOGIN IDENTICO A CAD
                        // ==========================================
                        Text(
                            text = "INICIAR SESIÓN",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Campo USUARIO
                        OutlinedTextField(
                            value = loginUser,
                            onValueChange = { loginUser = it },
                            placeholder = {
                                Text(
                                    text = "USUARIO",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "Usuario",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF020617),
                                unfocusedContainerColor = Color(0xFF020617).copy(alpha = 0.8f),
                                focusedBorderColor = Color(0xFFB91C1C),
                                unfocusedBorderColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Campo CLAVE DE ACCESO
                        OutlinedTextField(
                            value = loginPass,
                            onValueChange = { loginPass = it },
                            placeholder = {
                                Text(
                                    text = "CLAVE DE ACCESO",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Clave",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isPassVisible = !isPassVisible }) {
                                    Icon(
                                        imageVector = if (isPassVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = "Ver Clave",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            visualTransformation = if (isPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    viewModel.loginCentralWithCredentials(loginUser, loginPass) { success, _ ->
                                        if (success) {
                                            loginUser = ""
                                            loginPass = ""
                                        }
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF020617),
                                unfocusedContainerColor = Color(0xFF020617).copy(alpha = 0.8f),
                                focusedBorderColor = Color(0xFFB91C1C),
                                unfocusedBorderColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (viewModel.centralSessionError.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = viewModel.centralSessionError,
                                    color = Color(0xFFEF4444),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.loginCentralWithCredentials(loginUser, loginPass) { success, _ ->
                                    if (success) {
                                        loginUser = ""
                                        loginPass = ""
                                    }
                                }
                            },
                            enabled = loginUser.isNotBlank() && loginPass.isNotBlank() && !viewModel.isStartingCentralSession,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB91C1C),
                                disabledContainerColor = Color(0xFFB91C1C).copy(alpha = 0.4f)
                            )
                        ) {
                            if (viewModel.isStartingCentralSession) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Login,
                                    contentDescription = "Ingresar",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "INGRESAR AL SISTEMA",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ==========================================
            // BOTÓN ABAJO: ABRIR PORTÓN CUARTEL
            // ==========================================
            Button(
                onClick = { viewModel.triggerDoor() },
                enabled = !viewModel.isDoorOpening,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (viewModel.isDoorOpening) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = "Abrir Portón",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (viewModel.doorStatusMessage.isNotEmpty()) viewModel.doorStatusMessage else "ABRIR PORTÓN CUARTEL",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }

    // ==========================================
    // CONFIRMACIÓN DE CIERRE DE SESIÓN
    // ==========================================
    if (showCloseConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmDialog = false },
            title = {
                Text(
                    text = "Cerrar Sesión de Central",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "¿Deseas finalizar el turno activo del operador en SENTINEL CAD?",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.closeCentralOperatorSession { success, _ ->
                            showCloseConfirmDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cerrar Sesión", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirmDialog = false }) {
                    Text("Cancelar", color = Color.Gray)
                }
            },
            containerColor = TelColors.SurfaceDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
