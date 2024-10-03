package com.example.nfcvivapayment

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

interface VivaPaymentService {
    @FormUrlEncoded
    @POST("checkout/v2/payments")
    suspend fun createPayment(
        @Header("Authorization") authorization: String,
        @Field("amount") amount: Long,
        @Field("customerTrns") description: String,
        @Field("sourceCode") sourceCode: String, // Your Viva Wallet Source Code
        @Field("chargeToken") chargeToken: String // Card token from NFC
    ): Response<PaymentResponse>
}

data class PaymentResponse(
    val orderCode: Long,
    val statusId: Int,
    val errorCode: Int?,
    val errorText: String?
)
