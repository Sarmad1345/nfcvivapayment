package com.example.nfcvivapayment

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var nfcStatusTextView: TextView // NFC status display

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://demo.vivapayments.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val vivaAuthService = retrofit.create(VivaAuthService::class.java)
    private val vivaPaymentService = retrofit.create(VivaPaymentService::class.java)

    companion object {
        private const val NFC_PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcStatusTextView = findViewById(R.id.nfc_status) // Initialize NFC status TextView

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            // NFC is not supported
            nfcStatusTextView.text = "This phone does not support NFC."
        } else {
            // NFC supported, check if it's enabled
            checkNfcStatus()
            pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.NFC),
                NFC_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkNfcStatus() {
        if (nfcAdapter?.isEnabled == true) {
            nfcStatusTextView.text = "NFC is ON. Tap your card to proceed."
        } else {
            nfcStatusTextView.text = "NFC is OFF. Please enable NFC."
            // Prompt the user to go to the NFC settings
            Toast.makeText(this, "NFC is off. Redirecting to settings...", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
    }
    override fun onResume() {
        super.onResume()

        if (nfcAdapter?.isEnabled == true) {
            Log.d("NFC Debug", "NFC Adapter is enabled. Setting up foreground dispatch.")
            val intentFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            val techList = arrayOf(arrayOf(IsoDep::class.java.name))
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, arrayOf(intentFilter), techList)
        } else {
            Log.d("NFC Debug", "NFC Adapter is disabled. Prompting user to enable it.")
            checkNfcStatus() // Check if NFC is off and prompt the user
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("NFC Debug", "Disabling foreground dispatch.")
        nfcAdapter?.disableForegroundDispatch(this)
    }



    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NFC_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with enabling NFC dispatch
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
            } else {
                Toast.makeText(this, "NFC permission is required.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent == null) {
            Log.d("NFC Debug", "onNewIntent called with action: null")
            return
        }

        val action = intent.action
        Log.d("NFC Debug", "onNewIntent called with action: $action")

        if (NfcAdapter.ACTION_TECH_DISCOVERED == action) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            val isoDep: IsoDep? = IsoDep.get(tag)

            if (isoDep != null) {
                try {
                    nfcStatusTextView.text = "NFC tag detected, processing..."
                    isoDep.connect()

                    Log.d("NFC Debug", "IsoDep tag connected successfully")

                    // Validate if the card is a valid Viva Wallet card
                    val cardValid = isValidVivaCard(isoDep)
                    if (cardValid) {
                        val chargeToken = processNfcCard(isoDep)
                        nfcStatusTextView.text = "Viva card detected! Processing payment..."
                        Log.d("NFC Debug", "Viva card detected with token: $chargeToken")
                        initiatePayment(chargeToken, 5000) // Example: 5000 cents = 50.00 EUR
                    } else {
                        nfcStatusTextView.text = "This is not a Viva card. Please tap a Viva card."
                        Log.d("NFC Debug", "Not a valid Viva card")
                        Toast.makeText(this, "This is not a Viva card. Please tap a Viva card.", Toast.LENGTH_LONG).show()
                    }

                    isoDep.close()
                } catch (e: Exception) {
                    Log.e("NFC Error", "Error processing NFC card: ${e.message}")
                    nfcStatusTextView.text = "Error reading NFC tag: ${e.message}"
                    Toast.makeText(this, "Error reading NFC tag: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                nfcStatusTextView.text = "NFC tag is not supported."
                Log.d("NFC Debug", "No compatible IsoDep tag detected")
                Toast.makeText(this, "NFC tag is not supported.", Toast.LENGTH_LONG).show()
            }
        } else if (NfcAdapter.ACTION_TAG_DISCOVERED == action) {
            Log.d("NFC Debug", "ACTION_TAG_DISCOVERED action received but no tech discovered")
            Toast.makeText(this, "NFC tag detected, but no technology to handle it.", Toast.LENGTH_LONG).show()
        } else if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            Log.d("NFC Debug", "ACTION_NDEF_DISCOVERED action received")
            Toast.makeText(this, "NFC NDEF tag detected, but no handler for this type of tag.", Toast.LENGTH_LONG).show()
        } else {
            Log.d("NFC Debug", "No known action received")
            Toast.makeText(this, "NFC tag detected, but unsupported action: $action", Toast.LENGTH_LONG).show()
        }
    }

    private fun isValidVivaCard(isoDep: IsoDep): Boolean {
        // Add logic here to check if the card is a valid Viva card
        // For now, it's a placeholder for validation logic
        return true // Dummy validation for example purposes
    }

    private fun processNfcCard(isoDep: IsoDep): String {
        // Dummy token generation, replace with real card processing logic
        return "DUMMY_CARD_TOKEN"
    }

    private fun initiatePayment(chargeToken: String, amount: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tokenResponse = vivaAuthService.getAccessToken(
                    clientId = "nkf62v9up076ov8n1nc3se0qb0fzm44powvl4dny5q554.apps.vivapayments.com",
                    clientSecret = "J608BBtYh45h897Z02AtTZQDM2ytUA"
                )

                if (tokenResponse.isSuccessful) {
                    val accessToken = tokenResponse.body()?.access_token ?: return@launch
                    val paymentResponse = vivaPaymentService.createPayment(
                        authorization = "Bearer $accessToken",
                        amount = amount,
                        description = "Vending Machine Payment",
                        sourceCode = "YOUR_SOURCE_CODE",
                        chargeToken = chargeToken
                    )

                    withContext(Dispatchers.Main) {
                        if (paymentResponse.isSuccessful && paymentResponse.body()?.statusId == 2) {
                            Toast.makeText(this@MainActivity, "Payment Successful", Toast.LENGTH_SHORT).show()
                            nfcStatusTextView.text = "Payment Successful!"
                        } else {
                            val errorText = paymentResponse.body()?.errorText ?: "Unknown error"
                            nfcStatusTextView.text = "Payment Failed: $errorText"
                            Toast.makeText(this@MainActivity, "Payment Failed: $errorText", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val errorBody = tokenResponse.errorBody()?.string() ?: "Unknown error"
                    Log.e("Auth Error", "Failed to get access token: $errorBody")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to get access token: $errorBody", Toast.LENGTH_SHORT).show()
                        nfcStatusTextView.text = "Failed to get access token: $errorBody"
                    }
                }
            } catch (e: Exception) {
                Log.e("Payment Error", "Error initiating payment: ${e.message}")
                withContext(Dispatchers.Main) {
                    nfcStatusTextView.text = "Error initiating payment: ${e.message}"
                    Toast.makeText(this@MainActivity, "Payment failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
