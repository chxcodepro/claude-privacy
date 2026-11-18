package dev.chx.claudeprivacy

import android.webkit.WebView
import android.os.Looper
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class WebViewSpoof(private val languageEnabled: Boolean, private val timezoneEnabled: Boolean, language: String, timezone: String) {
    private val views = WeakHashMap<WebView, Boolean>()
    @Volatile private var zone = timezone
    private val lang = language
    private val reported = AtomicBoolean()

    fun install() {
        if (!languageEnabled && !timezoneEnabled) return
        XposedBridge.hookAllConstructors(WebView::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as WebView
                synchronized(views) {
                    if (views.put(view, true) != null) return
                }
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    param.throwable = SecurityException("WebView document-start script unavailable")
                    return
                }
                val handler = WebViewCompat.addDocumentStartJavaScript(view, script(), setOf("*"))
                XposedHelpers.setAdditionalInstanceField(view, "cp_script", handler)
                if (reported.compareAndSet(false, true)) Log.i("ClaudePrivacyAudit", "webviewDocumentStart=registered")
            }
        })
    }

    fun updateZone(timezone: String) {
        if (!timezoneEnabled || zone == timezone) return
        zone = timezone
        val update = "globalThis.__cpZone=${JSONObject.quote(timezone)};"
        val active = synchronized(views) { views.keys.toList() }
        fun apply(view: WebView) {
            (XposedHelpers.getAdditionalInstanceField(view, "cp_script") as? ScriptHandler)?.remove()
            val handler = WebViewCompat.addDocumentStartJavaScript(view, script(), setOf("*"))
            XposedHelpers.setAdditionalInstanceField(view, "cp_script", handler)
            view.evaluateJavascript(update, null)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            active.forEach(::apply)
            return
        }
        val completed = CountDownLatch(active.size)
        val failure = AtomicReference<Throwable?>()
        for (view in active) {
            if (!view.post {
                try {
                    apply(view)
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    completed.countDown()
                }
            }) {
                synchronized(views) { views.remove(view) }
                completed.countDown()
            }
        }
        if (!completed.await(3, TimeUnit.SECONDS) || failure.get() != null) throw SecurityException("WebView timezone update failed", failure.get())
    }

    private fun script(): String {
        val preamble = """
            let preferredLanguage;
            let usePrivacyZone = false;
            const NativeDateTimeFormat = Intl.DateTimeFormat;
        """
        val language = if (languageEnabled) """
            preferredLanguage = ${JSONObject.quote(lang)};
            Object.defineProperty(navigator, 'language', {get: () => preferredLanguage, configurable: true});
            Object.defineProperty(navigator, 'languages', {get: () => [preferredLanguage], configurable: true});
        """ else ""
        val timezone = if (timezoneEnabled) """
            globalThis.__cpZone = ${JSONObject.quote(zone)};
            usePrivacyZone = true;
            const nativeOffset = Date.prototype.getTimezoneOffset;
            Date.prototype.getTimezoneOffset = function() {
              if (!Number.isFinite(this.getTime())) return nativeOffset.call(this);
              const part = new NativeDateTimeFormat('en-US', {timeZone: globalThis.__cpZone, timeZoneName: 'shortOffset'})
                .formatToParts(this).find(p => p.type === 'timeZoneName')?.value || 'GMT';
              const match = part.match(/(?:GMT|UTC)([+-])(\d{1,2})(?::(\d{2}))?/);
              return match ? (match[1] === '+' ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3] || 0)) :
                part === 'GMT' || part === 'UTC' ? 0 : nativeOffset.call(this);
            };
        """ else ""
        val formatters = """
            const selectedLocales = (locales) => locales === undefined ? preferredLanguage : locales;
            const selectedOptions = (options) => usePrivacyZone && options !== null
              ? {...options, timeZone: options?.timeZone ?? globalThis.__cpZone} : options;
            function PrivacyDateTimeFormat(locales, options) {
              return new NativeDateTimeFormat(selectedLocales(locales), selectedOptions(options));
            }
            PrivacyDateTimeFormat.prototype = NativeDateTimeFormat.prototype;
            Object.setPrototypeOf(PrivacyDateTimeFormat, NativeDateTimeFormat);
            Intl.DateTimeFormat = PrivacyDateTimeFormat;
            for (const name of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString']) {
              const original = Date.prototype[name];
              Date.prototype[name] = function(locales, options) {
                return original.call(this, selectedLocales(locales), selectedOptions(options));
              };
            }
        """
        return "(function(){ $preamble $language $timezone $formatters })();"
    }
}
