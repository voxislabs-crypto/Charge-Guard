package com.voxislabs.chargeguard

import android.app.Activity
import android.content.SharedPreferences
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class MonetizationManager(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val premiumStatusText: TextView,
    private val buyPremiumButton: Button,
    private val restorePurchaseButton: Button,
    private val adView: AdView
) {
    private var billingClient: BillingClient? = null
    private var premiumProductDetails: ProductDetails? = null

    fun initialize() {
        buyPremiumButton.setOnClickListener { launchPurchaseFlow() }
        restorePurchaseButton.setOnClickListener { restorePurchases() }

        updatePremiumUi(isPremiumUnlocked())
        setupBilling()
        requestConsentAndInitializeAds()
    }

    fun onDestroy() {
        adView.destroy()
        billingClient?.endConnection()
    }

    private fun setupBilling() {
        val purchaseListener = PurchasesUpdatedListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                handlePurchases(purchases)
            }
        }

        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchaseListener)
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() = Unit

            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPremiumProductDetails()
                    restorePurchases()
                }
            }
        })
    }

    private fun queryPremiumProductDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.PREMIUM_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                premiumProductDetails = productDetailsList.firstOrNull()
            }
        }
    }

    private fun launchPurchaseFlow() {
        val details = premiumProductDetails
        if (details == null) {
            Toast.makeText(activity, activity.getString(R.string.premium_not_ready), Toast.LENGTH_SHORT).show()
            queryPremiumProductDetails()
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient?.launchBillingFlow(activity, flowParams)
    }

    private fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        var unlocked = false
        purchases.forEach { purchase ->
            if (purchase.products.contains(BuildConfig.PREMIUM_PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            ) {
                unlocked = true
                if (!purchase.isAcknowledged) {
                    val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient?.acknowledgePurchase(acknowledgeParams) { }
                }
            }
        }

        setPremiumUnlocked(unlocked)
    }

    private fun requestConsentAndInitializeAds() {
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    loadAdsIfAllowed(consentInfo)
                }
            },
            {
                loadAdsIfAllowed(consentInfo)
            }
        )
    }

    private fun loadAdsIfAllowed(consentInfo: ConsentInformation) {
        if (isPremiumUnlocked()) {
            adView.isVisible = false
            return
        }

        if (consentInfo.canRequestAds()) {
            MobileAds.initialize(activity) {}
            adView.loadAd(AdRequest.Builder().build())
            adView.isVisible = true
        }
    }

    private fun isPremiumUnlocked(): Boolean {
        return prefs.getBoolean(KEY_PREMIUM_UNLOCKED, false)
    }

    private fun setPremiumUnlocked(unlocked: Boolean) {
        prefs.edit().putBoolean(KEY_PREMIUM_UNLOCKED, unlocked).apply()
        updatePremiumUi(unlocked)
    }

    private fun updatePremiumUi(unlocked: Boolean) {
        premiumStatusText.text = if (unlocked) {
            activity.getString(R.string.premium_status_unlocked)
        } else {
            activity.getString(R.string.premium_status_locked)
        }

        buyPremiumButton.isVisible = !unlocked
        restorePurchaseButton.isVisible = !unlocked
        adView.isVisible = !unlocked
    }

    companion object {
        const val KEY_PREMIUM_UNLOCKED = "premium_unlocked"
    }
}
