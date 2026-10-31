package com.paymerge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paymerge.ui.theme.PayMergeTheme

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.Calendar

import android.content.Intent

import androidx.activity.result.ActivityResult

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Design tokens ----------
private val Ink = Color(0xFF0B2239)        // headings, primary text
private val Brand = Color(0xFF0A7F78)      // single brand colour
private val BrandDark = Color(0xFF075E59)  // hero card
private val BrandSoft = Color(0xFFE3F4F2)  // icon backgrounds
private val ScreenBg = Color(0xFFF3F6F9)     // screen background
private val Surface0 = Color(0xFFFFFFFF)   // cards
private val Muted = Color(0xFF5B6F82)      // secondary text
private val Line = Color(0xFFE2E8EE)       // borders / dividers

// Radius hierarchy: hero > card > chip
private val RHero = 24.dp
private val RCard = 16.dp
private val RChip = 12.dp

data class Txn(
    val title: String,
    val note: String,
    val amount: String,
    val status: String,
    val dateTime: String,
    val source: String
)

private const val TXN_PREFS = "paymerge_transactions"
private const val TXN_KEY = "records"

private fun saveTransactions(context: Context, items: List<Txn>) {
    val array = JSONArray()

    items.forEach { txn ->
        val obj = JSONObject()
        obj.put("title", txn.title)
        obj.put("note", txn.note)
        obj.put("amount", txn.amount)
        obj.put("status", txn.status)
        obj.put("dateTime", txn.dateTime)
        obj.put("source", txn.source)
        array.put(obj)
    }

    context.getSharedPreferences(TXN_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(TXN_KEY, array.toString())
        .apply()
}

private fun loadTransactions(context: Context): List<Txn> {
    val prefs = context.getSharedPreferences(
        TXN_PREFS,
        Context.MODE_PRIVATE
    )

    val savedData = prefs.getString(TXN_KEY, "[]") ?: "[]"

    return try {
        val array = JSONArray(savedData)
        List(array.length()) { index ->
            val obj = array.getJSONObject(index)

            Txn(
                title = obj.optString("title"),
                note = obj.optString("note"),
                amount = obj.optString("amount"),
                status = obj.optString("status"),
                dateTime = obj.optString("dateTime"),
                source = obj.optString("source")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PayMergeTheme {
                PayMergeHome()
            }
        }
    }
}


@Composable
fun PayMergeHome() {
    var tab by remember { mutableIntStateOf(0) }

    val context = LocalContext.current

    val transactions = remember {
        mutableStateListOf<Txn>().apply {
            addAll(loadTransactions(context))
        }
    }
    var showScanner by remember { mutableStateOf(false) }
    var scannedUpi by remember { mutableStateOf<String?>(null) }
    var receiverName by remember { mutableStateOf("") }
    var receiverUpi by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var showMobileForm by remember { mutableStateOf(false) }
    var mobileNumber by remember { mutableStateOf("") }

    var paymentResponse by remember { mutableStateOf<String?>(null) }
    var showManualStatus by remember { mutableStateOf(false) }

    fun recordTransaction(status: String, source: String) {
        val now = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date())

        val txn = Txn(
            title = "UPI payment",
            note = receiverName.ifBlank { receiverUpi },
            amount = "₹${amount.trim()}",
            status = status,
            dateTime = now,
            source = source
        )

        transactions.add(0, txn)
        saveTransactions(context, transactions)

        showManualStatus = false

        Toast.makeText(
            context,
            "Transaction saved: $status",
            Toast.LENGTH_LONG
        ).show()
    }

    val upiPaymentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->

        val data = result.data

        val response = data?.getStringExtra("response")
        val tezResponse = data?.getStringExtra("tezResponse")
        val directStatus = data?.getStringExtra("Status")
            ?: data?.getStringExtra("status")

        val rawResponse = directStatus ?: response ?: tezResponse
        paymentResponse = rawResponse

        val normalized = rawResponse?.uppercase(Locale.ROOT).orEmpty()

        when {
            Regex("""(?:STATUS["']?\s*[:=]\s*["']?)SUCCESS""")
                .containsMatchIn(normalized) ||
                    normalized.trim() == "SUCCESS" -> {
                recordTransaction("SUCCESS", "UPI app response")
            }

            Regex("""(?:STATUS["']?\s*[:=]\s*["']?)(FAILURE|FAILED)""")
                .containsMatchIn(normalized) ||
                    normalized.trim() in listOf("FAILURE", "FAILED") -> {
                recordTransaction("FAILED", "UPI app response")
            }

            else -> {
                // Pending, submitted, missing, or unclear response
                showManualStatus = true
            }
        }
    }



    if (showScanner) {
        CustomQrScanner(
            onClose = { showScanner = false },
            onQrScanned = { raw ->
                val uri = Uri.parse(raw)
                if (
                    uri.scheme.equals("upi", ignoreCase = true) &&
                    uri.host.equals("pay", ignoreCase = true) &&
                    !uri.getQueryParameter("pa").isNullOrBlank()
                ) {
                    scannedUpi = raw
                    receiverUpi = uri.getQueryParameter("pa").orEmpty()
                    receiverName = uri.getQueryParameter("pn").orEmpty()
                    amount = uri.getQueryParameter("am").orEmpty()
                    showScanner = false
                } else {
                    Toast.makeText(
                        context,
                        "Please scan a valid UPI QR code.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = ScreenBg,
        bottomBar = {
            BottomBar(selected = tab, onSelect = { tab = it })
        }
    ) { inner ->
        if (tab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                TopBar()
                Greeting()

                ScanCard(
                    onScan = { showScanner = true },
                    onPayMobile = {
                        scannedUpi = null
                        showMobileForm = true
                    }
                )


                fun openUpiPayment() {
                    if (receiverUpi.isBlank()) {
                        Toast.makeText(
                            context,
                            "Receiver UPI ID is missing.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val enteredAmount = amount.trim()
                    val numericAmount = enteredAmount.toBigDecimalOrNull()

                    if (
                        numericAmount == null ||
                        numericAmount <= java.math.BigDecimal.ZERO
                    ) {
                        Toast.makeText(
                            context,
                            "Please enter a valid amount.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    try {
                        val originalUri = Uri.parse(scannedUpi ?: "")

                        val paymentUri = if (
                            originalUri.scheme.equals("upi", ignoreCase = true) &&
                            originalUri.host.equals("pay", ignoreCase = true)
                        ) {
                            // QR payment: preserve QR parameters and update amount
                            originalUri.buildUpon()
                                .clearQuery()
                                .apply {
                                    originalUri.queryParameterNames.forEach { key ->
                                        if (key != "am") {
                                            originalUri.getQueryParameters(key).forEach { value ->
                                                appendQueryParameter(key, value)
                                            }
                                        }
                                    }

                                    appendQueryParameter("am", enteredAmount)

                                    if (originalUri.getQueryParameter("cu").isNullOrBlank()) {
                                        appendQueryParameter("cu", "INR")
                                    }
                                }
                                .build()
                        } else {
                            // Mobile form payment: use receiver's UPI ID
                            if (receiverUpi.isBlank() || !receiverUpi.contains("@")) {
                                Toast.makeText(
                                    context,
                                    "Enter a valid receiver UPI ID.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }

                            Uri.Builder()
                                .scheme("upi")
                                .authority("pay")
                                .appendQueryParameter("pa", receiverUpi.trim())
                                .appendQueryParameter(
                                    "pn",
                                    receiverName.ifBlank { "UPI Recipient" }
                                )
                                .appendQueryParameter("am", enteredAmount)
                                .appendQueryParameter("cu", "INR")
                                .build()
                        }

                        val paymentIntent = Intent(
                            Intent.ACTION_VIEW,
                            paymentUri
                        )

                        upiPaymentLauncher.launch(
                            Intent.createChooser(
                                paymentIntent,
                                "Choose a UPI app"
                            )
                        )

                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Could not open the UPI payment app.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (showMobileForm) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Surface0
                        ),
                        border = BorderStroke(1.dp, Line),
                        shape = RoundedCornerShape(RCard)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Pay to Mobile Number",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink
                            )

                            OutlinedTextField(
                                value = mobileNumber,
                                onValueChange = {
                                    mobileNumber = it.filter(Char::isDigit).take(10)
                                },
                                label = { Text("Mobile Number") },
                                placeholder = { Text("Enter 10-digit number") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = receiverName,
                                onValueChange = { receiverName = it },
                                label = { Text("Receiver Name (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = receiverUpi,
                                onValueChange = { receiverUpi = it.trim() },
                                label = { Text("Receiver UPI ID") },
                                placeholder = { Text("example@upi") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount (₹)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    when {
                                        mobileNumber.length != 10 -> {
                                            Toast.makeText(
                                                context,
                                                "Enter a valid 10-digit mobile number.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }

                                        receiverUpi.isBlank() ||
                                                !receiverUpi.contains("@") -> {
                                            Toast.makeText(
                                                context,
                                                "Enter the receiver's valid UPI ID.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }

                                        else -> openUpiPayment()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand
                                )
                            ) {
                                Text("Continue to Payment")
                            }

                            TextButton(
                                onClick = {
                                    showMobileForm = false
                                    mobileNumber = ""
                                    receiverName = ""
                                    receiverUpi = ""
                                    amount = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }


                if (scannedUpi != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Surface0
                        ),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "UPI QR scanned",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink
                            )
                            Text(
                                "Receiver: ${receiverName.ifBlank { "Not provided" }}",
                                color = Ink
                            )
                            Text("UPI ID: $receiverUpi", color = Muted)

                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount (₹)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )



                            Button(
                                onClick = { openUpiPayment() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(RChip),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    "Continue to Payment",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    scannedUpi = null
                                    receiverName = ""
                                    receiverUpi = ""
                                    amount = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Scan another QR")
                            }
                        }
                    }
                }

                SummaryRow(paymentCount = transactions.size)
                HistorySection(transactions)
                Spacer(Modifier.height(8.dp))
            }
        }
        else if (tab == 1) {
            TransactionHistoryPage(items = transactions)
        } else if (tab == 2) {
            AnalysisPage(items = transactions)
        } else {
            ProfilePage()
        }
    }
    if (showManualStatus) {
        AlertDialog(
            onDismissRequest = {
                // Keep the dialog open until a status is selected.
            },
            title = {
                Text("Payment Status")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "We couldn't determine the payment status. " +
                                "Select what happened in your UPI app."
                    )

                    TextButton(
                        onClick = {
                            recordTransaction("SUCCESS", "User reported")
                        }
                    ) {
                        Text("Payment Successful")
                    }

                    TextButton(
                        onClick = {
                            recordTransaction("FAILED", "User reported")
                        }
                    ) {
                        Text("Payment Failed")
                    }

                    TextButton(
                        onClick = {
                            recordTransaction("PENDING", "User reported")
                        }
                    ) {
                        Text("Payment Pending")
                    }

                    TextButton(
                        onClick = {
                            recordTransaction("CANCELLED", "User reported")
                        }
                    ) {
                        Text("Payment Cancelled")
                    }
                }
            },
            confirmButton = {}
        )
    }
}

// ---------- Top bar (highlighted logo and title) ----------
@Composable
private fun TopBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoMark(Modifier.size(42.dp))

            Spacer(Modifier.width(10.dp))


            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF14243A),
                            fontWeight = FontWeight.ExtraBold
                        )
                    ) {
                        append("Pay")
                    }

                    withStyle(
                        SpanStyle(
                            color = Color(0xFF00A884),
                            fontWeight = FontWeight.ExtraBold
                        )
                    ) {
                        append("Merge")
                    }
                },
                fontSize = 30.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.7).sp,
                fontFamily = FontFamily.SansSerif
            )

        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(42.dp)
                .clip(CircleShape)
                .background(Surface0),
            contentAlignment = Alignment.Center
        ) {
            BellIcon(
                Modifier.size(22.dp),
                Ink
            )
        }
    }
}

// Logo: two chevrons moving towards each other and merging
private val LogoNavy = Color(0xFF181C6B)
private val LogoMint = Color(0xFF99E0DA)

@Composable
private fun LogoMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Surface0)
            .border(1.dp, Line, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val w = size.width
            val h = size.height
            val sw = w * 0.22f
            val round = { width: Float -> Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round) }

            val left = Path().apply {          // navy ">"
                moveTo(w * 0.26f, h * 0.14f)
                lineTo(w * 0.62f, h * 0.50f)
                lineTo(w * 0.26f, h * 0.86f)
            }
            val right = Path().apply {         // mint "<"
                moveTo(w * 0.74f, h * 0.14f)
                lineTo(w * 0.38f, h * 0.50f)
                lineTo(w * 0.74f, h * 0.86f)
            }
            drawPath(left, LogoNavy, style = round(sw))
            drawPath(right, Surface0, style = round(sw + w * 0.09f)) // white gap between chevrons
            drawPath(right, LogoMint, style = round(sw))
        }
    }
}


@Composable
private fun Greeting() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "Your Payments, Simplified",
                fontSize = 25.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Manage your payments, track your spending and stay in control.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Muted
            )
        }

        PurseIllustration(
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun PurseIllustration(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(width = 150.dp, height = 135.dp),
        contentAlignment = Alignment.Center
    ) {
        // Soft background circles
        Box(
            modifier = Modifier
                .size(115.dp)
                .offset(x = 10.dp, y = 8.dp)
                .background(
                    Color(0xFFB9F0E9),
                    CircleShape
                )
        )

        Box(
            modifier = Modifier
                .size(62.dp)
                .offset(x = (-45).dp, y = 20.dp)
                .background(
                    Color(0xFFD9F7F2),
                    CircleShape
                )
        )

        // Purse handle
        Box(
            modifier = Modifier
                .offset(x = 4.dp, y = (-23).dp)
                .size(width = 83.dp, height = 49.dp)
                .rotate(-15f)
                .border(
                    width = 9.dp,
                    color = Color(0xFF087F73),
                    shape = RoundedCornerShape(15.dp)
                )
        )

        // Main purse body
        Box(
            modifier = Modifier
                .offset(x = (-1).dp, y = 8.dp)
                .size(width = 112.dp, height = 70.dp)
                .rotate(-8f)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF19C6B3),
                            Color(0xFF009688),
                            Color(0xFF00796B)
                        )
                    ),
                    shape = RoundedCornerShape(15.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.75f)
                    .height(9.dp)
                    .background(
                        color = Color(0xFF75E5D8),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }
        // ₹ coin
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-1).dp, y = (-8).dp)
                .size(43.dp)
                .background(
                    Color(0xFF008F80),
                    CircleShape
                )
                .border(
                    1.dp,
                    Color(0xFF55DACA),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "₹",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Purse button
        Box(
            modifier = Modifier
                .offset(x = 24.dp, y = 8.dp)
                .size(13.dp)
                .background(
                    Color(0xFF8DF4E8),
                    CircleShape
                )
                .border(
                    2.dp,
                    Color.White.copy(alpha = 0.7f),
                    CircleShape
                )
        )
    }
}


// ---------- Hero: the one bold element ----------
@Composable
private fun ScanCard(
    onScan: () -> Unit,
    onPayMobile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RHero),
        colors = CardDefaults.cardColors(containerColor = BrandDark),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Scan & pay", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Scan a QR, then pick your UPI app to pay.",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(RCard))
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    ScannerIcon(Modifier.size(32.dp), Color.White)
                }
            }

            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(RChip),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandDark)
            ) {
                Text("Scan QR code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onPayMobile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(RChip),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text(
                    "Pay to Mobile Number",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ---------- Summary ----------
@Composable
private fun SummaryRow(paymentCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(Modifier.weight(1f), label = "Spent this month", value = "₹0")
        StatTile(Modifier.weight(1f), label = "Payments saved", value = paymentCount.toString())
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(RCard),
        colors = CardDefaults.cardColors(containerColor = Surface0),
        border = BorderStroke(1.dp, Line),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 13.sp, color = Muted)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

// ---------- History ----------
@Composable
private fun HistorySection(items: List<Txn>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface0
        ),
        border = BorderStroke(1.dp, Line),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            // Section header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 18.dp,
                        end = 12.dp,
                        top = 16.dp,
                        bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Transaction History",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Text(
                        text = "${items.size} records saved",
                        fontSize = 12.sp,
                        color = Muted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BrandSoft
                ) {
                    Text(
                        text = "ALL",
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandDark
                    )
                }
            }

            HorizontalDivider(color = Line)

            if (items.isEmpty()) {
                EmptyState()
            } else {
                items.forEachIndexed { index, txn ->
                    TxnRow(txn)

                    if (index != items.lastIndex) {
                        HorizontalDivider(
                            color = Line,
                            modifier = Modifier.padding(
                                start = 18.dp,
                                end = 18.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TxnRow(t: Txn) {
    val status = t.status.uppercase(Locale.ROOT)

    val statusColor = when (status) {
        "SUCCESS" -> Color(0xFF15803D)
        "FAILED", "FAILURE" -> Color(0xFFDC2626)
        "CANCELLED", "CANCELED" -> Color(0xFF64748B)
        "PENDING" -> Color(0xFFB7791F)
        else -> Color(0xFF64748B)
    }

    val statusBackground = when (status) {
        "SUCCESS" -> Color(0xFFDCFCE7)
        "FAILED", "FAILURE" -> Color(0xFFFEE2E2)
        "CANCELLED", "CANCELED" -> Color(0xFFE2E8F0)
        "PENDING" -> Color(0xFFFEF3C7)
        else -> Color(0xFFF1F5F9)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Transaction icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BrandSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "₹",
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = BrandDark
            )
        }

        Spacer(Modifier.width(12.dp))

        // Receiver and transaction details
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = t.note.ifBlank { "UPI payment" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1
            )

            Text(
                text = t.dateTime,
                fontSize = 11.sp,
                color = Muted
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusBackground
            ) {
                Text(
                    text = when (status) {
                        "SUCCESS" -> "✓  Successful"
                        "FAILED", "FAILURE" -> "✕  Failed"
                        "CANCELLED", "CANCELED" -> "—  Cancelled"
                        "PENDING" -> "◷  Pending"
                        else -> status.replaceFirstChar {
                            it.titlecase(Locale.ROOT)
                        }
                    },
                    modifier = Modifier.padding(
                        horizontal = 9.dp,
                        vertical = 5.dp
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            Text(
                text = "Source: ${t.source}",
                fontSize = 10.sp,
                color = Muted,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = t.amount,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.End
        )
    }
}



@Composable
private fun TransactionHistoryPage(items: List<Txn>) {
    var selectedFilter by remember { mutableStateOf("All") }

    val filters = listOf(
        "All", "Success", "Failed", "Cancelled", "Pending"
    )

    val filteredItems = when (selectedFilter) {
        "Success" -> items.filter {
            it.status.equals("SUCCESS", ignoreCase = true)
        }

        "Failed" -> items.filter {
            it.status.equals("FAILED", ignoreCase = true) ||
                    it.status.equals("FAILURE", ignoreCase = true)
        }

        "Cancelled" -> items.filter {
            it.status.equals("CANCELLED", ignoreCase = true) ||
                    it.status.equals("CANCELED", ignoreCase = true)
        }

        "Pending" -> items.filter {
            it.status.equals("PENDING", ignoreCase = true)
        }

        else -> items
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(
                start = 16.dp,
                top = 60.dp,
                end = 16.dp,
                bottom = 8.dp
            )
    ) {
        Text(
            text = "Transaction History",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        Text(
            text = "Track all your saved payments",
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RCard),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            ),
            border = BorderStroke(1.dp, Line)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TOTAL RECORDS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Muted
                    )

                    Text(
                        text = items.size.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BrandSoft
                ) {
                    Text(
                        text = "PAYMERGE",
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 9.dp
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "$selectedFilter transactions (${filteredItems.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredItems.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(RCard),
                colors = CardDefaults.cardColors(
                    containerColor = Surface0
                ),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No transactions found",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Your matching payment records will appear here.",
                        fontSize = 13.sp,
                        color = Muted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                filteredItems.forEach { txn ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(RCard),
                        colors = CardDefaults.cardColors(
                            containerColor = Surface0
                        ),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        TxnRow(txn)
                    }
                }
            }
        }
    }
}


@Composable
fun ProfilePage() {
    var showAbout by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(
                start = 16.dp,
                top = 60.dp,
                end = 16.dp,
                bottom = 12.dp
            )
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "My Profile",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        Text(
            text = "Manage your PayMerge account",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = Muted,

        )

        Spacer(modifier = Modifier.height(22.dp))

        // Profile card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 1.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(BrandSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "P",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Brand
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PayMerge User",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )

                    Text(
                        text = "Personal Account",
                        fontSize = 13.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 5.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BrandSoft
                    ) {
                        Text(
                            text = "●  Active",
                            color = BrandDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 5.dp
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "ACCOUNT",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            )
        ) {
            Column {
                ProfileMenuItem(
                    icon = "👤",
                    title = "Personal Information",
                    subtitle = "View your profile details",
                    onClick = {
                        showAbout = true
                    }
                )

                HorizontalDivider(color = Line)

                ProfileMenuItem(
                    icon = "🔒",
                    title = "Privacy & Security",
                    subtitle = "Learn how your data is handled",
                    onClick = {
                        showPrivacy = true
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "PREFERENCES & SUPPORT",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            )
        ) {
            Column {
                ProfileMenuItem(
                    icon = "📊",
                    title = "Transaction History",
                    subtitle = "Review your saved payment records",
                    onClick = {}
                )

                HorizontalDivider(color = Line)

                ProfileMenuItem(
                    icon = "ℹ️",
                    title = "About PayMerge",
                    subtitle = "App information and version",
                    onClick = {
                        showAbout = true
                    }
                )

                HorizontalDivider(color = Line)

                ProfileMenuItem(
                    icon = "❓",
                    title = "Help & Support",
                    subtitle = "Get help using the demo app",
                    onClick = {
                        showPrivacy = true
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = { showLogout = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFFE6B8B8)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFC62828)
            )
        ) {
            Text(
                text = "Log Out",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "PAYMERGE",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Brand,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "One place for your payment records",
            fontSize = 12.sp,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About PayMerge") },
            text = {
                Text(
                    "PayMerge is a demo application that brings " +
                            "payment app launching and saved transaction " +
                            "records together in one place."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text("OK", color = Brand)
                }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy & Support") },
            text = {
                Text(
                    "This is a demo app. Transaction statuses " +
                            "saved manually in the demo should not be " +
                            "treated as bank-verified payment confirmations."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text("Got it", color = Brand)
                }
            }
        )
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Log Out") },
            text = {
                Text(
                    "PayMerge does not have account login yet, " +
                            "so there is no active session to log out from."
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogout = false }) {
                    Text("OK", color = Brand)
                }
            }
        )
    }
}

@Composable
private fun ProfileMenuItem(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(BrandSoft, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 19.sp)
        }

        Spacer(modifier = Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Text(
            text = "›",
            fontSize = 25.sp,
            color = Muted
        )
    }
}



@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(BrandSoft),
            contentAlignment = Alignment.Center
        ) {
            DocumentIcon(Modifier.size(26.dp), Brand)
        }
        Spacer(Modifier.height(4.dp))
        Text("No transactions yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(
            "Scan a QR code to get started. Your saved transactions will appear here.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Muted,
            textAlign = TextAlign.Center
        )
    }
}


private data class MonthStats(
    val label: String,
    val total: Double,
    val count: Int
)


@Composable
private fun AnalysisPage(items: List<Txn>) {
    val monthStats = remember(items) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // Include the current month and previous five months.
        calendar.add(Calendar.MONTH, -5)

        val months = (0 until 6).map {
            val start = calendar.clone() as Calendar
            val label = SimpleDateFormat(
                "MMM",
                Locale.getDefault()
            ).format(start.time)

            calendar.add(Calendar.MONTH, 1)
            start to label
        }

        val inputFormat = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        )

        months.map { (start, label) ->
            val end = start.clone() as Calendar
            end.add(Calendar.MONTH, 1)

            val monthTransactions = items.filter { txn ->
                try {
                    val date = inputFormat.parse(txn.dateTime)
                    date != null &&
                            !date.before(start.time) &&
                            date.before(end.time)
                } catch (_: Exception) {
                    false
                }
            }

            val successful = monthTransactions.filter {
                it.status.equals("SUCCESS", ignoreCase = true)
            }

            val total = successful.sumOf { txn ->
                txn.amount
                    .replace("₹", "")
                    .replace(",", "")
                    .trim()
                    .toDoubleOrNull() ?: 0.0
            }

            MonthStats(
                label = label,
                total = total,
                count = monthTransactions.size
            )
        }
    }

    val successfulTotal = monthStats.sumOf { it.total }
    val savedCount = monthStats.sumOf { it.count }
    val maxValue = (monthStats.maxOfOrNull { it.total } ?: 0.0)
        .coerceAtLeast(1.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                top = 60.dp,
                end = 16.dp,
                bottom = 8.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Payment Analysis",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        Text(
            text = "Your payment trends over the last 6 months",
            fontSize = 14.sp,
            color = Muted
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(RCard),
                colors = CardDefaults.cardColors(
                    containerColor = Surface0
                ),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Text(
                        text = "Successful amount",
                        fontSize = 12.sp,
                        color = Muted
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "₹${"%,.0f".format(Locale.US, successfulTotal)}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandDark
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(RCard),
                colors = CardDefaults.cardColors(
                    containerColor = Surface0
                ),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Text(
                        text = "Saved records",
                        fontSize = 12.sp,
                        color = Muted
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = savedCount.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                }
            }
        }

        // Monthly smooth curve chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RCard),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            ),
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Monthly Payment Trend",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Successful payments only",
                    fontSize = 12.sp,
                    color = Muted
                )

                Spacer(Modifier.height(12.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                ) {
                    val left = 8.dp.toPx()
                    val right = size.width - 8.dp.toPx()
                    val top = 12.dp.toPx()
                    val bottom = size.height - 12.dp.toPx()
                    val chartWidth = right - left
                    val chartHeight = bottom - top

                    for (i in 0..3) {
                        val y = top + chartHeight * i / 3f

                        drawLine(
                            color = Line,
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val points = monthStats.mapIndexed { index, month ->
                        val x = if (monthStats.size == 1) {
                            left + chartWidth / 2f
                        } else {
                            left + chartWidth * index /
                                    (monthStats.size - 1).toFloat()
                        }

                        val y = bottom -
                                (month.total / maxValue).toFloat() *
                                chartHeight

                        Offset(x, y)
                    }

                    if (points.size >= 2) {
                        val path = Path().apply {
                            moveTo(
                                points.first().x,
                                points.first().y
                            )

                            for (i in 0 until points.lastIndex) {
                                val a = points[i]
                                val b = points[i + 1]
                                val midX = (a.x + b.x) / 2f

                                quadraticTo(
                                    a.x + (midX - a.x) / 2f,
                                    a.y,
                                    midX,
                                    (a.y + b.y) / 2f
                                )

                                quadraticTo(
                                    midX + (b.x - midX) / 2f,
                                    b.y,
                                    b.x,
                                    b.y
                                )
                            }
                        }

                        drawPath(
                            path = path,
                            color = Brand,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }

                    points.forEach { point ->
                        drawCircle(
                            color = Brand,
                            radius = 4.dp.toPx(),
                            center = point
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    monthStats.forEach { month ->
                        Text(
                            text = month.label,
                            fontSize = 10.sp,
                            color = Muted
                        )
                    }
                }
            }
        }

        // Monthly histogram / bar chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RCard),
            colors = CardDefaults.cardColors(
                containerColor = Surface0
            ),
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Month-wise Comparison",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Successful payment amount per month",
                    fontSize = 12.sp,
                    color = Muted
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    monthStats.forEach { month ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = if (month.total > 0) {
                                    if (month.total >= 1000) {
                                        "₹${"%.1fk".format(
                                            Locale.US,
                                            month.total / 1000
                                        )}"
                                    } else {
                                        "₹${"%.0f".format(
                                            Locale.US,
                                            month.total
                                        )}"
                                    }
                                } else {
                                    "₹0"
                                },
                                fontSize = 9.sp,
                                color = Muted,
                                maxLines = 1
                            )

                            Spacer(Modifier.height(5.dp))

                            val barFraction =
                                (month.total / maxValue)
                                    .toFloat()
                                    .coerceIn(0f, 1f)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        (100f * barFraction)
                                            .coerceAtLeast(4f).dp
                                    )
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 6.dp,
                                            topEnd = 6.dp
                                        )
                                    )
                                    .background(Brand)
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = month.label,
                                fontSize = 10.sp,
                                color = Muted
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Analysis is based on locally saved transaction records. " +
                    "User-reported statuses are not bank-verified.",
            fontSize = 12.sp,
            color = Muted
        )

        Spacer(Modifier.height(8.dp))
    }
}



// ---------- Bottom navigation ----------

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(
        containerColor = Surface0,
        tonalElevation = 0.dp
    ) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Brand,
            selectedTextColor = Brand,
            indicatorColor = BrandSoft,
            unselectedIconColor = Muted,
            unselectedTextColor = Muted
        )

        NavigationBarItem(
            selected = selected == 0,
            onClick = { onSelect(0) },
            icon = {
                HomeIcon(
                    Modifier.size(24.dp),
                    if (selected == 0) Brand else Muted
                )
            },
            label = { Text("Home") },
            colors = colors
        )

        NavigationBarItem(
            selected = selected == 1,
            onClick = { onSelect(1) },
            icon = {
                DocumentIcon(
                    Modifier.size(24.dp),
                    if (selected == 1) Brand else Muted
                )
            },
            label = { Text("History") },
            colors = colors
        )

        NavigationBarItem(
            selected = selected == 2,
            onClick = { onSelect(2) },
            icon = {
                AnalysisIcon(
                    Modifier.size(24.dp),
                    if (selected == 2) Brand else Muted
                )
            },
            label = { Text("Analysis") },
            colors = colors
        )

        NavigationBarItem(
            selected = selected == 3,
            onClick = { onSelect(3) },
            icon = {
                PersonIcon(
                    Modifier.size(24.dp),
                    if (selected == 3) Brand else Muted
                )
            },
            label = { Text("Profile") },
            colors = colors
        )
    }
}



// ---------- Custom QR scanner ----------
@Composable
private fun CustomQrScanner(
    onClose: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scanHandled by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf("Place a QR code inside the camera view.") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) scanMessage = "Camera permission is required to scan QR codes."
    }

    val processImage: (Uri) -> Unit = { uri ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (raw != null) onQrScanned(raw)
                    else scanMessage = "No QR code found in this image. Try another image."
                }
                .addOnFailureListener {
                    scanMessage = "Could not read this image. Please try another one."
                }
        } catch (_: Exception) {
            scanMessage = "Could not open this image. Please try another one."
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) processImage(uri) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF071B2B)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("Cancel", color = Color.White) }
                Text(
                    "Scan QR Code",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(64.dp))
            }

            Spacer(Modifier.height(16.dp))

            if (cameraPermissionGranted) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)),
                    factory = { viewContext ->
                        val previewView = PreviewView(viewContext)
                        val future = ProcessCameraProvider.getInstance(viewContext)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(cameraExecutor) { proxy ->
                                if (scanHandled) {
                                    proxy.close()
                                } else {
                                    val mediaImage = proxy.image
                                    if (mediaImage == null) {
                                        proxy.close()
                                    } else {
                                        val input = InputImage.fromMediaImage(
                                            mediaImage,
                                            proxy.imageInfo.rotationDegrees
                                        )
                                        scanner.process(input)
                                            .addOnSuccessListener { barcodes ->
                                                val raw = barcodes.firstOrNull {
                                                    !it.rawValue.isNullOrBlank()
                                                }?.rawValue
                                                if (raw != null && !scanHandled) {
                                                    scanHandled = true
                                                    onQrScanned(raw)
                                                }
                                            }
                                            .addOnFailureListener {
                                                scanMessage = "Unable to read QR code. Try again."
                                            }
                                            .addOnCompleteListener { proxy.close() }
                                    }
                                }
                            }
                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (_: Exception) {
                                scanMessage = "Could not start the camera. Please try again."
                            }
                        }, ContextCompat.getMainExecutor(viewContext))
                        previewView
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(Color(0xFF132D40), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Allow camera access to scan QR codes.", color = Color.White, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(scanMessage, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandDark)
            ) {
                Text("Upload from Gallery", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------- Icons (Canvas) ----------
@Composable
private fun ScannerIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val sw = s * 0.09f
        val c = s * 0.30f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

        line(0f, c, 0f, 0f); line(0f, 0f, c, 0f)
        line(s - c, 0f, s, 0f); line(s, 0f, s, c)
        line(0f, s - c, 0f, s); line(0f, s, c, s)
        line(s - c, s, s, s); line(s, s - c, s, s)

        val cell = s * 0.16f
        val gap = s * 0.06f
        val start = s * 0.28f
        listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2).forEach { (x, y) ->
            drawRoundRect(
                color = color,
                topLeft = Offset(start + x * (cell + gap), start + y * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.2f)
            )
        }
    }
}

@Composable
private fun DocumentIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val outline = Path().apply {
            moveTo(w * 0.20f, h * 0.08f)
            lineTo(w * 0.62f, h * 0.08f)
            lineTo(w * 0.82f, h * 0.28f)
            lineTo(w * 0.82f, h * 0.92f)
            lineTo(w * 0.20f, h * 0.92f)
            close()
        }
        drawPath(outline, color, style = Stroke(width = sw))
        for (i in 0..2) {
            val y = h * (0.46f + i * 0.15f)
            drawLine(color, Offset(w * 0.34f, y), Offset(w * 0.68f, y), sw * 0.8f, StrokeCap.Round)
        }
    }
}

@Composable
private fun BellIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bell = Path().apply {
            moveTo(w * 0.20f, h * 0.78f)
            lineTo(w * 0.28f, h * 0.68f)
            lineTo(w * 0.28f, h * 0.45f)
            cubicTo(w * 0.28f, h * 0.18f, w * 0.72f, h * 0.18f, w * 0.72f, h * 0.45f)
            lineTo(w * 0.72f, h * 0.68f)
            lineTo(w * 0.80f, h * 0.78f)
            close()
        }
        drawPath(bell, color, style = st)
        drawLine(color, Offset(w * 0.42f, h * 0.90f), Offset(w * 0.58f, h * 0.90f), w * 0.09f, StrokeCap.Round)
    }
}

@Composable
private fun HomeIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val roof = Path().apply {
            moveTo(w * 0.10f, h * 0.50f)
            lineTo(w * 0.50f, h * 0.15f)
            lineTo(w * 0.90f, h * 0.50f)
        }
        val body = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.20f, h * 0.85f)
            lineTo(w * 0.80f, h * 0.85f)
            lineTo(w * 0.80f, h * 0.44f)
        }
        drawPath(roof, color, style = st)
        drawPath(body, color, style = st)
    }
}

@Composable
private fun PersonIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.32f), style = st)
        val shoulders = Path().apply {
            moveTo(w * 0.18f, h * 0.88f)
            cubicTo(w * 0.18f, h * 0.55f, w * 0.82f, h * 0.55f, w * 0.82f, h * 0.88f)
        }
        drawPath(shoulders, color, style = st)
    }
}


@Composable
private fun AnalysisIcon(
    modifier: Modifier = Modifier,
    tint: Color = Brand
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.88f),
            end = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.12f),
            strokeWidth = 2.dp.toPx()
        )

        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.88f),
            end = androidx.compose.ui.geometry.Offset(w * 0.92f, h * 0.88f),
            strokeWidth = 2.dp.toPx()
        )

        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.70f),
            end = androidx.compose.ui.geometry.Offset(w * 0.43f, h * 0.48f),
            strokeWidth = 3.dp.toPx()
        )

        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.43f, h * 0.48f),
            end = androidx.compose.ui.geometry.Offset(w * 0.63f, h * 0.60f),
            strokeWidth = 3.dp.toPx()
        )

        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.63f, h * 0.60f),
            end = androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.22f),
            strokeWidth = 3.dp.toPx()
        )
    }
}

