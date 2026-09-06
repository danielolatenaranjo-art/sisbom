package com.sisbom.misisbom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(viewModel: SisBomViewModel) {
    var userId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        label = "ButtonScale"
    )

    val isDark = LocalDarkMode.current

    SisBomBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            coil.compose.AsyncImage(
                model = viewModel.getClientLogoModel(),
                contentDescription = "Logo",
                placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                modifier = Modifier
                    .size(112.dp)
                    .padding(bottom = 12.dp)
            )

            Text(
                text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "SENTINEL ONE",
                color = if (isDark) Color.White else TextDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )

            Text(
                text = "PORTAL MÓVIL DE PERSONAL",
                color = if (isDark) TextSecondaryDark else TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Tarjeta de Login Glassmorphic translúcida
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    // Input ID Registro
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it },
                        label = { Text("USUARIO") },
                        placeholder = { Text("Ej. 14") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Person, contentDescription = "User Icon", tint = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFE2E8F0),
                            focusedLabelColor = BomberosRed,
                            unfocusedLabelColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF94A3B8),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Input Contraseña
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("CONTRASEÑA") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock Icon", tint = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B))
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = "Toggle Password",
                                    tint = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B)
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFE2E8F0),
                            focusedLabelColor = BomberosRed,
                            unfocusedLabelColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF94A3B8),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Botón de Ingreso
                    Button(
                        onClick = {
                            viewModel.performLogin(userId, password)
                        },
                        enabled = !viewModel.isLoggingIn,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BomberosRed,
                            contentColor = Color.White,
                            disabledContainerColor = BomberosRed.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .scale(buttonScale)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isPressed = true
                                        tryAwaitRelease()
                                        isPressed = false
                                    }
                                )
                            }
                    ) {
                        if (viewModel.isLoggingIn) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Text(
                                text = "INGRESAR AL SISTEMA",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Estado offline indicativo
            AnimatedVisibility(
                visible = viewModel.personnelList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Info",
                        tint = GoGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Base de datos cargada localmente (Acceso rápido offline)",
                        color = if (isDark) TextSecondaryDark else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }



            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "SENTINEL ONE V${viewModel.appVersionName} • USO OFICIAL",
                color = if (isDark) Color.DarkGray else Color.LightGray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}
