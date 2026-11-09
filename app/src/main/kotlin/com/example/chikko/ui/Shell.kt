package com.example.chikko.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.chikko.app.ChikkoApp
import com.example.chikko.ui.menu.MenuScreen
import com.example.chikko.ui.orders.OrdersScreen
import com.example.chikko.ui.pos.PosScreen
import com.example.chikko.ui.reports.ReportsScreen
import com.example.chikko.ui.stock.StockScreen

/** Read by screens that need repository access. Provided once by [ChikkoShell]. */
val LocalApp = staticCompositionLocalOf<ChikkoApp> { error("ChikkoApp not provided") }

private enum class Tab(val label: String, val icon: ImageVector) {
    SELL("Sell", Icons.Filled.ShoppingCart),
    ORDERS("Orders", Icons.Filled.Receipt),
    MENU("Menu", Icons.Filled.Restaurant),
    STOCK("Stock", Icons.Filled.Inventory2),
    REPORTS("Reports", Icons.Filled.BarChart),
}

@Composable
fun ChikkoShell(app: ChikkoApp) {
    var tab by remember { mutableStateOf(Tab.SELL) }
    CompositionLocalProvider(LocalApp provides app) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad: PaddingValues ->
            when (tab) {
                Tab.SELL -> PosScreen(pad)
                Tab.ORDERS -> OrdersScreen(pad)
                Tab.MENU -> MenuScreen(pad)
                Tab.STOCK -> StockScreen(pad)
                Tab.REPORTS -> ReportsScreen(pad)
            }
        }
    }
}
