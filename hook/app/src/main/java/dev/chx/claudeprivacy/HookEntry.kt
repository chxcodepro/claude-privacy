package dev.chx.claudeprivacy

import android.app.Application
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.TimeZone as IcuTimeZone
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.telephony.TelephonyManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal val BRIDGE_GRANT_URI: Uri
    get() = Uri.parse("content://dev.chx.claudeprivacy.bridge/config")

class HookEntry : IXposedHookLoadPackage {
    private class EntryShell : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) = super.onCreate(null)

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            setIntent(intent)
        }
    }
    private data class VerifiedExit(
        val ip: String, val iface: String, val network: Network, val country: String, val timezone: String?,
    )
    private val networkWorker = Executors.newSingleThreadExecutor()
    private val audited = Collections.synchronizedSet(mutableSetOf<String>())
    private val firewallOrder = Any()
    private val foreground = AtomicBoolean(false)
    private val generation = AtomicInteger()
    // LSPosed constructs the entry before ActivityThread prepares the main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var startedActivities = 0
    private var visible: Activity? = null
    private var pane: LaunchGateView? = null
    private var paneOwner: Activity? = null
    private var admittedGeneration = -1
    private val admitted get() = admittedGeneration == generation.get()
    private var handoff = false
    private var webViewSpoof: WebViewSpoof? = null
    private var startupConfig: JSONObject? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "com.anthropic.claude") return
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as Context
                var reason = "hook_failed"
                try {
                    bridge(context, "block", "startup")
                    retainBridgeGrant(context)
                    val config = JSONObject(bridge(context, "config"))
                    reason = "unsupported_version"
                    val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                    if (version != 26111319L) throw SecurityException("Unsupported Claude version $version")
                    reason = "hook_failed"
                    applySpoof(context, lpparam, config)
                    startupConfig = config
                } catch (error: Throwable) {
                    reportFailure(context, reason, error)
                    param.throwable = SecurityException("Claude Privacy Gate blocked startup", error)
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as Context
                if (param.hasThrowable()) {
                    if (startupConfig != null) try { bridge(context, "block", "hook_failed") } catch (_: Throwable) { }
                    return
                }
                try {
                    val config = startupConfig ?: throw IllegalStateException("Missing startup config")
                    watchForeground(param.thisObject as Application, context, config)
                    watchNetwork(context, config)
                } catch (error: Throwable) {
                    reportFailure(context, "hook_failed", error)
                    param.throwable = SecurityException("Claude Privacy Gate could not register lifecycle", error)
                }
            }
        })
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java, "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!admitted && (param.args[1] as String).startsWith("com.anthropic.claude.")) {
                        param.result = EntryShell()
                    }
                }
            }
        )
    }

    private fun bridge(context: Context, method: String, arg: String? = null, extras: Bundle? = null): String {
        return context.contentResolver.call(BRIDGE_GRANT_URI, method, arg, extras)?.getString("result")
            ?: throw IllegalStateException("Privacy bridge unavailable")
    }

    private fun retainBridgeGrant(context: Context) {
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.any { it.uri == BRIDGE_GRANT_URI && it.isReadPermission }) return
        try {
            // Android revokes temporary grants on force-stop; keep only this bridge URI.
            resolver.takePersistableUriPermission(BRIDGE_GRANT_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Log.w("ClaudePrivacyGate", "持久桥接授权尚未就绪，请在模块设置检查 Hook 授权。", error)
        }
    }

    private fun reportFailure(context: Context, reason: String, error: Throwable) {
        val detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(600)
        Log.e("ClaudePrivacyGate", detail, error)
        XposedBridge.log("Claude Privacy Gate: $detail")
        val extras = if (error is ExitDenied) null else Bundle().apply { putString("detail", detail) }
        try { bridge(context, "block", reason, extras) } catch (_: Throwable) { }
    }

    private fun probeClaudeEdge(network: Network, addresses: List<Inet4Address>): ClaudeEdgeExit {
        val url = "https://$CLAUDE_PRODUCTION_HOST/cdn-cgi/trace"
        // A system HTTP/SOCKS proxy needs its own restricted firewall path. Never silently bypass it.
        check(ProxySelector.getDefault()?.select(URI(url)).orEmpty().none { it.type() != Proxy.Type.DIRECT }) {
            "Claude exit probe requires VPN or direct routing, not a system proxy"
        }
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == CLAUDE_PRODUCTION_HOST) addresses else throw UnknownHostException(hostname)
            })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Claude-Privacy-Gate")
            .header("Cache-Control", "no-store")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.code == 200) { "Claude trace HTTP ${response.code}" }
            val source = checkNotNull(response.body).source()
            check(!source.request(4097L)) { "Claude trace response too large" }
            parseClaudeEdgeTrace(source.readUtf8())
        }
    }

    private fun verifyExit(context: Context, attempt: Int): VerifiedExit {
        val config = JSONObject(bridge(context, "config"))
        val entries = config.getJSONArray("allowedIps")
        val allowed = List(entries.length(), entries::getString)
        requireAllowlisted(allowed)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: throw IllegalStateException("No active network")
        val link = checkNotNull(connectivity.getLinkProperties(network)) { "No active network link" }
        check(link.httpProxy == null) { "Claude exit probe requires VPN or direct routing, not a system proxy" }
        val iface = checkNotNull(link.interfaceName) { "No network interface" }
        val addresses = network.getAllByName(CLAUDE_PRODUCTION_HOST).filterIsInstance<Inet4Address>().distinct()
        check(addresses.isNotEmpty()) { "Claude API has no IPv4 address on the active network" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Probe cancelled")
        synchronized(firewallOrder) {
            check(generation.get() == attempt)
            bridge(context, "probe", addresses.joinToString(",") { checkNotNull(it.hostAddress) })
        }
        val (ip, country) = probeClaudeEdge(network, addresses)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Probe cancelled")
        check(connectivity.activeNetwork == network) { "Network changed during probe" }
        val zone = IcuTimeZone.getAvailableIDs(IcuTimeZone.SystemTimeZoneType.CANONICAL_LOCATION, country, null)
            .singleOrNull()
        synchronized(firewallOrder) {
            check(generation.get() == attempt)
            bridge(context, "observed", "$ip,$country,${zone ?: "-"}")
            permittedCountry(ip, country)
            requireAllowlisted(allowed, ip)
        }
        val savedZone = config.optJSONObject("ipTimezones")?.optString(ip)
            ?.takeIf { it in TimeZone.getAvailableIDs() }
        val configuredZone = config.optString("timezone", "UTC")
        val effectiveZone = if (config.getJSONObject("spoof").optBoolean("timezone")) {
            if (config.optString("timezoneMode") == "ip") zone ?: savedZone ?: configuredZone else configuredZone
        } else null
        return VerifiedExit(ip, iface, network, country, effectiveZone)
    }

    private fun watchNetwork(context: Context, config: JSONObject) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        var initial = true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (initial) { initial = false; return }
                changed()
            }

            override fun onLost(network: Network) {
                changed()
            }

            private fun changed() {
                val change = invalidate(context, "network_changed")
                main.post {
                    if (generation.get() != change) return@post
                    admittedGeneration = -1
                    (visible ?: paneOwner)?.let { beginAssessment(it, context, config) }
                }
            }
        })
    }

    private fun beginAssessment(activity: Activity, context: Context, config: JSONObject) {
        if (!foreground.get() || activity.isFinishing) return
        admittedGeneration = -1
        val attempt = generation.incrementAndGet()
        val view = if (paneOwner === activity) checkNotNull(pane).also { it.checking() } else {
            pane?.dismiss()
            LaunchGateView(activity).also { pane = it; paneOwner = activity }
        }
        val pending = networkWorker.submit {
            val result = runCatching {
                synchronized(firewallOrder) {
                    check(generation.get() == attempt)
                    bridge(context, "block", "startup")
                }
                val exit = verifyExit(context, attempt)
                exit.timezone?.let(::setDefaultTimezone)
                webViewSpoof?.updateZone(TimeZone.getDefault().id)
                val appliedTimezone = exit.timezone?.takeIf { TimeZone.getDefault().id == it }
                if (exit.timezone != null) audit("timezone") { appliedTimezone != null }
                val appliedLanguage = Locale.getDefault().takeIf {
                    config.getJSONObject("spoof").optBoolean("language") &&
                        it == Locale.forLanguageTag(config.getString("language"))
                }?.toLanguageTag()
                synchronized(firewallOrder) {
                    val current = context.getSystemService(ConnectivityManager::class.java).activeNetwork
                    check(generation.get() == attempt && foreground.get() && current == exit.network)
                    bridge(context, "allow", listOf(exit.ip, exit.iface, exit.country,
                        appliedTimezone ?: "-", appliedLanguage ?: "-").joinToString(","))
                }
            }
            val error = result.exceptionOrNull()
            synchronized(firewallOrder) {
                if (generation.get() != attempt) return@submit
                if (error != null) reportFailure(context, (error as? ExitDenied)?.rejection?.status ?: "probe_failed", error)
            }
            main.post {
                if (generation.get() != attempt || !foreground.get() || activity.isFinishing) return@post
                if (error != null) {
                    val denial = error as? ExitDenied
                    view.denied(when (denial?.rejection) {
                        ExitRejection.EMPTY_LIST -> "请先在模块设置中添加允许的公网 IPv4。"
                        ExitRejection.NOT_LISTED -> "当前 IPv4 未在允许列表：${denial.ip}"
                        ExitRejection.MAINLAND -> "中国大陆公网 IPv4 已锁定：${denial.ip}"
                        ExitRejection.REGION_UNKNOWN -> "未能确认 IP 归属地，请检查网络后重试。"
                        null -> "出口核验失败，请检查网络后重试。"
                    })
                } else {
                    view.passed {
                        if (generation.get() == attempt && foreground.get() && !activity.isFinishing) {
                            enter(activity, context, attempt)
                        }
                    }
                }
            }
        }
        main.postDelayed({
            if (!pending.isDone && errorTimeout(context, attempt) && pane === view) {
                pending.cancel(true)
                view.denied("出口核验超时，请检查网络后重试。")
            }
        }, 20000)
    }

    private fun errorTimeout(context: Context, attempt: Int): Boolean = synchronized(firewallOrder) {
        if (admitted || generation.get() != attempt) return@synchronized false
        invalidate(context, "probe_failed")
        true
    }

    private fun invalidate(context: Context, reason: String): Int = synchronized(firewallOrder) {
        val next = generation.incrementAndGet()
        try { bridge(context, "block", reason) } catch (_: Throwable) { }
        next
    }

    private fun enter(activity: Activity, context: Context, attempt: Int) {
        admittedGeneration = attempt
        if (activity !is EntryShell) {
            pane?.dismiss()
            pane = null
            paneOwner = null
            return
        }
        handoff = true
        try {
            val target = Intent(activity.intent).setClassName(
                context.packageName, checkNotNull(activity.componentName).className
            )
            // MainActivity is singleTop; retire its placeholder before relaunching.
            activity.finish()
            activity.startActivity(target)
        } catch (error: Throwable) {
            handoff = false
            admittedGeneration = -1
            reportFailure(context, "hook_failed", error)
        }
        main.postDelayed({
            if (!handoff) return@postDelayed
            handoff = false
            admittedGeneration = -1
            closeForeground(context)
        }, 3000)
    }

    private fun closeForeground(context: Context) {
        foreground.set(false)
        admittedGeneration = -1
        invalidate(context, "background")
    }

    private fun watchForeground(application: Application, context: Context, config: JSONObject) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
            override fun onActivityStarted(activity: Activity) {
                val first = startedActivities++ == 0
                foreground.set(true)
                if (handoff && activity !is EntryShell) handoff = false
                if (first && !admitted) beginAssessment(activity, context, config)
            }
            override fun onActivityResumed(activity: Activity) {
                visible = activity
                if (!admitted && paneOwner !== activity) beginAssessment(activity, context, config)
            }
            override fun onActivityPaused(activity: Activity) {
                if (visible === activity) visible = null
            }
            override fun onActivityStopped(activity: Activity) {
                if (visible === activity) visible = null
                if (paneOwner === activity) {
                    pane?.dismiss()
                    pane = null
                    paneOwner = null
                }
                if (--startedActivities == 0 && !handoff) closeForeground(context)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
            override fun onActivityDestroyed(activity: Activity) { }
        })
    }

    private fun applySpoof(context: Context, lpparam: LoadPackageParam, config: JSONObject) {
        val spoof = config.getJSONObject("spoof")
        val values = config.getJSONObject("values")
        if (spoof.optBoolean("timezone")) setConfiguredTimezone(config)
        if (spoof.optBoolean("language")) Locale.setDefault(Locale.forLanguageTag(config.optString("language", "en-US")))

        if (spoof.optBoolean("androidId")) {
            val id = values.getString("androidId")
            if (!id.matches(Regex("[0-9a-f]{16}"))) throw SecurityException("Invalid fake Android ID")
            XposedBridge.hookAllMethods(Settings.Secure::class.java, "getString", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size > 1 && param.args[1] == Settings.Secure.ANDROID_ID) param.result = id
                }
            })
        }
        if (spoof.optBoolean("device")) {
            mapOf("MANUFACTURER" to values.getString("manufacturer"), "BRAND" to values.getString("brand"), "MODEL" to values.getString("model"), "DEVICE" to values.getString("device"), "PRODUCT" to values.getString("product"))
                .forEach { (field, value) -> XposedHelpers.setStaticObjectField(android.os.Build::class.java, field, value) }
        }
        if (spoof.optBoolean("build")) {
            val release = android.os.Build.VERSION.RELEASE
            val fingerprint = values.getString("buildFingerprint").replace("{release}", release)
            mapOf("TAGS" to "release-keys", "TYPE" to "user", "FINGERPRINT" to fingerprint)
                .forEach { (field, value) -> XposedHelpers.setStaticObjectField(android.os.Build::class.java, field, value) }
        }
        if (spoof.optBoolean("hardware")) {
            XposedHelpers.setStaticObjectField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        }
        if (spoof.optBoolean("carrier")) hookCarrier(values)
        hookSiftPayload(lpparam, values, spoof)
        if (spoof.optBoolean("network")) hookNetworkMetadata(lpparam)
        if (spoof.optBoolean("packages")) hookPackageProbe()
        hookPlaidPayload(lpparam, config, spoof)
        hookSentry(lpparam, spoof)
        hookTelemetryHosts(spoof)
        webViewSpoof = WebViewSpoof(
            spoof.optBoolean("language"), spoof.optBoolean("timezone"),
            config.optString("language", "en-US"), TimeZone.getDefault().id
        ).also { it.install() }
        logNativeReadback(context, config)
    }

    private fun audit(name: String, readback: () -> Boolean) {
        if (audited.add(name)) {
            val result = runCatching(readback).fold({ if (it) "verified" else "mismatch" }, { "unavailable" })
            Log.i("ClaudePrivacyAudit", "$name=$result")
        }
    }

    private fun logNativeReadback(context: Context, config: JSONObject) {
        val spoof = config.getJSONObject("spoof")
        val values = config.getJSONObject("values")
        if (spoof.optBoolean("language")) audit("language") {
            Locale.getDefault() == Locale.forLanguageTag(config.getString("language"))
        }
        if (spoof.optBoolean("androidId")) audit("androidId") {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) == values.getString("androidId")
        }
        if (spoof.optBoolean("device")) audit("device") {
            android.os.Build.MANUFACTURER == values.getString("manufacturer") &&
                android.os.Build.BRAND == values.getString("brand") &&
                android.os.Build.MODEL == values.getString("model") &&
                android.os.Build.DEVICE == values.getString("device") &&
                android.os.Build.PRODUCT == values.getString("product")
        }
        if (spoof.optBoolean("build")) audit("buildFingerprint") {
            android.os.Build.FINGERPRINT == values.getString("buildFingerprint").replace("{release}", android.os.Build.VERSION.RELEASE)
        }
        if (spoof.optBoolean("hardware")) audit("abi") {
            android.os.Build.SUPPORTED_ABIS.contentEquals(arrayOf("arm64-v8a", "armeabi-v7a"))
        }
        if (spoof.optBoolean("carrier")) audit("carrier") {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            telephony.networkOperatorName == values.getString("carrierName") &&
                telephony.simCountryIso == values.getString("simCountry") &&
                telephony.networkCountryIso == values.getString("networkCountry")
        }
    }

    private fun setConfiguredTimezone(config: JSONObject) =
        setDefaultTimezone(config.optString("timezone", "UTC"))

    private fun setDefaultTimezone(zone: String) {
        if (zone !in TimeZone.getAvailableIDs()) throw SecurityException("Invalid configured timezone")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    private fun hookCarrier(values: JSONObject) {
        val methods = mapOf(
            "getNetworkOperatorName" to values.getString("carrierName"), "getSimCountryIso" to values.getString("simCountry"), "getNetworkCountryIso" to values.getString("networkCountry"),
            "getSimOperator" to "", "getNetworkOperator" to "", "getSimCarrierIdName" to ""
        )
        methods.forEach { (method, value) -> XposedBridge.hookAllMethods(TelephonyManager::class.java, method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = value }
        }) }
        mapOf("getSimCarrierId" to -1, "getPhoneType" to 0).forEach { (method, value) ->
            XposedBridge.hookAllMethods(TelephonyManager::class.java, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = value }
            })
        }
    }

    private fun hookSiftPayload(lpparam: LoadPackageParam, values: JSONObject, spoof: JSONObject) {
        val device = XposedHelpers.findClass("ht0", lpparam.classLoader)
        XposedBridge.hookAllConstructors(device, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val target = param.thisObject
                if (spoof.optBoolean("androidId")) XposedHelpers.setObjectField(target, "i", values.getString("androidId"))
                if (spoof.optBoolean("carrier")) {
                    XposedHelpers.setObjectField(target, "d", values.getString("carrierName"))
                    XposedHelpers.setObjectField(target, "e", values.getString("simCountry"))
                }
                if (spoof.optBoolean("device")) {
                    XposedHelpers.setObjectField(target, "f", values.getString("manufacturer"))
                    XposedHelpers.setObjectField(target, "g", values.getString("model"))
                }
                if (spoof.optBoolean("build")) XposedHelpers.setObjectField(target, "j", "release-keys")
                if (spoof.optBoolean("root")) for (field in listOf("k", "l", "m", "n")) {
                    XposedHelpers.setObjectField(target, field, Collections.emptyList<Any>())
                }
                if (spoof.optBoolean("packages")) XposedHelpers.setObjectField(target, "o", Collections.emptyList<Any>())
            }
        })
        val state = XposedHelpers.findClass("fo0", lpparam.classLoader)
        XposedBridge.hookAllConstructors(state, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val target = param.thisObject
                if (spoof.optBoolean("battery")) {
                    XposedHelpers.setDoubleField(target, "c", values.getInt("batteryPercent") / 100.0)
                    XposedHelpers.setLongField(target, "d", 3)
                    XposedHelpers.setLongField(target, "e", 2)
                    XposedHelpers.setLongField(target, "f", 0)
                    audit("batteryPayload") {
                        XposedHelpers.getDoubleField(target, "c") == values.getInt("batteryPercent") / 100.0 &&
                            XposedHelpers.getLongField(target, "d") == 3L &&
                            XposedHelpers.getLongField(target, "e") == 2L &&
                            XposedHelpers.getLongField(target, "f") == 0L
                    }
                }
                if (spoof.optBoolean("localIps")) {
                    XposedHelpers.setObjectField(target, "g", Collections.emptyList<Any>())
                    audit("localIpsPayload") { (XposedHelpers.getObjectField(target, "g") as List<*>).isEmpty() }
                }
            }
        })
    }

    private fun hookNetworkMetadata(lpparam: LoadPackageParam) {
        val builder = XposedHelpers.findClass("s24", lpparam.classLoader)
        val info = XposedHelpers.findClass("w24", lpparam.classLoader)
        val setter = builder.getDeclaredMethod("A", info)
        val fixture = builder.getDeclaredConstructor().newInstance()
        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // CCT sends both dimensions in one optional field, including events already queued.
                param.args[0] = null
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== fixture) audit("cctQueuedEvent") {
                    !param.hasThrowable() && XposedHelpers.getObjectField(param.thisObject, "h") == null
                }
            }
        })
        audit("cctFixture") {
            setter.invoke(fixture, info.declaredConstructors.single().newInstance(null, null))
            XposedHelpers.getObjectField(fixture, "h") == null
        }
    }

    private fun hookPackageProbe() {
        val manager = Class.forName("android.app.ApplicationPackageManager")
        val hidden = setOf("com.onepassword.android", "com.google.android.apps.healthdata", "com.google.android.apps.maps", "com.android.vending")
        XposedBridge.hookAllMethods(manager, "getPackageInfo", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() in hidden) param.throwable = PackageManager.NameNotFoundException()
            }
        })
    }

    private fun hookPlaidPayload(lpparam: LoadPackageParam, config: JSONObject, spoof: JSONObject) {
        val metadata = XposedHelpers.findClass("com.plaid.internal.s1", lpparam.classLoader)
        val values = config.getJSONObject("values")
        val locale = Locale.forLanguageTag(config.optString("language", "en-US"))
        XposedBridge.hookAllConstructors(metadata, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val target = param.thisObject
                if (spoof.optBoolean("device")) {
                    XposedHelpers.setObjectField(target, "a", values.getString("manufacturer"))
                    XposedHelpers.setObjectField(target, "b", values.getString("model"))
                }
                if (spoof.optBoolean("language")) {
                    XposedHelpers.setObjectField(target, "e", locale.toLanguageTag())
                    XposedHelpers.setObjectField(target, "f", locale.country)
                    XposedHelpers.setObjectField(target, "g", locale.language)
                }
                if (spoof.optBoolean("screen")) {
                    XposedHelpers.setIntField(target, "h", values.getInt("screenWidth"))
                    XposedHelpers.setIntField(target, "i", values.getInt("screenHeight"))
                    audit("screenPayload") {
                        XposedHelpers.getIntField(target, "h") == values.getInt("screenWidth") &&
                            XposedHelpers.getIntField(target, "i") == values.getInt("screenHeight")
                    }
                }
            }
        })
    }

    private fun hookSentry(lpparam: LoadPackageParam, spoof: JSONObject) {
        val names = mapOf(
            "setAttachScreenshot" to "sentryScreenshot",
            "setAttachViewHierarchy" to "sentryHierarchy",
            "setSendDefaultPii" to "sentryPii"
        )
        for (className in listOf("io.sentry.SentryOptions", "io.sentry.android.core.SentryAndroidOptions")) {
            val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
            for ((method, setting) in names) {
                if (!spoof.optBoolean(setting)) continue
                XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { if (param.args.isNotEmpty()) param.args[0] = false }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        audit(setting) {
                            !param.hasThrowable() && XposedHelpers.callMethod(param.thisObject, method.replaceFirst("set", "is")) == false
                        }
                    }
                })
            }
        }
    }

    private fun hookTelemetryHosts(spoof: JSONObject) {
        val blocked = buildSet {
            if (spoof.optBoolean("sentryTelemetry")) add("ingest.us.sentry.io")
            if (spoof.optBoolean("datadog")) {
                add("browser-intake-datadoghq.com")
                add("browser-intake-datadoghq.eu")
                add("browser-intake-ddog-gov.com")
            }
            if (spoof.optBoolean("firebaseId")) add("firebaseinstallations.googleapis.com")
            if (spoof.optBoolean("fcm")) add("fcmregistrations.googleapis.com")
            if (spoof.optBoolean("sift")) add("api3.siftscience.com")
            if (spoof.optBoolean("plaid")) {
                add("production.plaid.com")
                add("sandbox.plaid.com")
                add("analytics.plaid.com")
            }
        }
        if (blocked.isEmpty()) return
        fun isBlocked(host: String) = blocked.any { host.equals(it, true) || host.endsWith(".$it", true) }
        XposedBridge.hookAllMethods(InetAddress::class.java, "getAllByName", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val host = param.args.firstOrNull() as? String ?: return
                if (isBlocked(host)) param.throwable = UnknownHostException("Blocked telemetry host: $host")
            }
        })
        XposedBridge.hookAllMethods(URL::class.java, "openConnection", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val host = (param.thisObject as URL).host
                if (isBlocked(host)) param.throwable = UnknownHostException("Blocked telemetry host: $host")
            }
        })
        blocked.forEach { host ->
            audit("javaBlock:$host") {
                // openConnection creates a handle; without connect() it does not send a request.
                runCatching { URL("https://$host/").openConnection() }.exceptionOrNull() is UnknownHostException
            }
        }
    }

}
