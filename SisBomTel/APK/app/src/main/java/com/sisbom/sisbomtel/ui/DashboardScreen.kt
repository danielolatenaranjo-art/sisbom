package com.sisbom.sisbomtel.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // CENTRO: LOGO Y NOMBRE CUERPO DE BOMBEROS
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // LOGO DIRECTO (SIN TARJETA CONTENEDORA)
                if (viewModel.saasLogoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = viewModel.saasLogoUrl,
                        contentDescription = "Logo Cuerpo de Bomberos",
                        modifier = Modifier.size(190.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Escudo Oficial",
                        modifier = Modifier.size(190.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // NOMBRE CUERPO DE BOMBEROS
                Text(
                    text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CUERPO DE BOMBEROS",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

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
}
