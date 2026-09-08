package com.sisbom.sisbomcrew.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sisbom.sisbomcrew.CrewViewModel
import com.sisbom.sisbomcrew.R
import com.sisbom.sisbomcrew.ui.theme.*

@Composable
fun LicenseActivationScreen(
    viewModel: CrewViewModel,
    modifier: Modifier = Modifier
) {
    var keyInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1E1B4B), BgDark),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 440.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.85f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Logo SENTINEL (logo 1.png)
                Image(
                    painter = painterResource(id = R.drawable.logo_1),
                    contentDescription = "SENTINEL Logo",
                    modifier = Modifier
                        .size(130.dp)
                        .padding(bottom = 2.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SENTINEL CREW",
                        color = TextWhite,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "ACTIVACIÓN DE LICENCIA MULTI-TENANT",
                        color = AccentCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }

                Text(
                    text = "Ingrese la clave de licencia asignada a su Cuerpo de Bomberos para sincronizar la dotación y servicios.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { 
                        keyInput = it.uppercase()
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("CLAVE DE LICENCIA", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    placeholder = { Text("EJ: SB-LICENCIA-KEY", color = TextMuted.copy(alpha = 0.5f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCrimson,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = Color.Black.copy(alpha = 0.40f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.25f)
                    ),
                    leadingIcon = {
                        Icon(Icons.Filled.Key, contentDescription = "Clave", tint = AccentCrimson)
                    }
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = AccentCrimson,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = {
                        viewModel.activateLicense(keyInput) { success, msg ->
                            if (!success) {
                                errorMessage = msg
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCrimson),
                    enabled = !isLoading && keyInput.trim().isNotEmpty()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Shield, contentDescription = "Activar", tint = TextWhite)
                            Text(
                                text = "ACTIVAR APLICACIÓN",
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
