package com.shakib.pharmacykhata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shakib.pharmacykhata.data.catalog.CatalogRepository
import com.shakib.pharmacykhata.data.remote.AuthRepository
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.ui.auth.AuthScreen
import com.shakib.pharmacykhata.ui.dashboard.DashboardScreen
import com.shakib.pharmacykhata.ui.inventory.InventoryScreen
import com.shakib.pharmacykhata.ui.khata.KhataScreen
import com.shakib.pharmacykhata.ui.pos.PosScreen
import com.shakib.pharmacykhata.ui.purchase.PurchaseScreen
import com.shakib.pharmacykhata.ui.reports.ReportsScreen
import com.shakib.pharmacykhata.ui.scanner.ScannerScreen
import com.shakib.pharmacykhata.ui.settings.SettingsScreen
import com.shakib.pharmacykhata.ui.theme.PharmacyKhataProTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var catalogRepository: CatalogRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sessionStore: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { runCatching { catalogRepository.ensureSeeded() } }
        setContent {
            PharmacyKhataProTheme {
                var unlocked by remember { mutableStateOf(sessionStore.pinHash == null) }
                if (!unlocked) {
                    PinLockScreen(sessionStore) { unlocked = true }
                } else {
                    AppRoot(startSignedIn = authRepository.isSignedIn)
                }
            }
        }
    }
}

@Composable
private fun PinLockScreen(session: SessionStore, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("অ্যাপ লক", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("PIN দিন") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (SessionStore.hashPin(pin) == session.pinHash) onUnlocked() else error = "ভুল PIN"
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("খুলুন") }
    }
}

private data class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val DESTS = listOf(
    Dest("dashboard", "ড্যাশবোর্ড", Icons.Default.Home),
    Dest("pos", "বিক্রি", Icons.Default.PointOfSale),
    Dest("inventory", "স্টক", Icons.Default.Inventory2),
    Dest("purchase", "ক্রয়", Icons.Default.ShoppingCart),
    Dest("khata", "খাতা", Icons.Default.MenuBook),
    Dest("reports", "রিপোর্ট", Icons.Default.BarChart),
    Dest("settings", "সেটিংস", Icons.Default.Settings)
)

@Composable
fun AppRoot(startSignedIn: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    var pendingBarcode by remember { mutableStateOf<String?>(null) }

    if (!startSignedIn && current == null) {
        // First composition before NavHost decides start destination — handled by startDestination below.
    }

    Scaffold(
        bottomBar = {
            if (current != "auth" && current != "scanner") {
                NavigationBar {
                    DESTS.forEach { d ->
                        NavigationBarItem(
                            selected = current == d.route,
                            onClick = { nav.navigate(d.route) { launchSingleTop = true; restoreState = true } },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = if (startSignedIn) "dashboard" else "auth", modifier = Modifier.padding(padding)) {
            composable("auth") {
                AuthScreen(onAuthenticated = { nav.navigate("dashboard") { popUpTo("auth") { inclusive = true } } })
            }
            composable("dashboard") { DashboardScreen() }
            composable("pos") {
                PosScreen(
                    onOpenScanner = { nav.navigate("scanner") },
                    scannedBarcode = pendingBarcode,
                    onBarcodeConsumed = { pendingBarcode = null }
                )
            }
            composable("scanner") {
                ScannerScreen(
                    onResult = { code -> pendingBarcode = code; nav.popBackStack() },
                    onClose = { nav.popBackStack() }
                )
            }
            composable("inventory") { InventoryScreen() }
            composable("purchase") { PurchaseScreen() }
            composable("khata") { KhataScreen() }
            composable("reports") { ReportsScreen() }
            composable("settings") {
                SettingsScreen(onSignedOut = { nav.navigate("auth") { popUpTo(0) { inclusive = true } } })
            }
        }
    }
}
