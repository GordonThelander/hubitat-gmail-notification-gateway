import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(
        name: "Gmail Notification Gateway",
        namespace: "Hubitat Integrations",
        author: "Gordon Thelander",
        importUrl: ""
    ) {
        capability "Notification"
        capability "Actuator"
        capability "Refresh"

        attribute "lastStatus", "string"
        attribute "lastSentAt", "string"
        attribute "lastError", "string"
        attribute "lastResponse", "string"
        attribute "lastHttpStatus", "number"
        attribute "lastWebhookCheck", "string"

        command "sendTest"
        command "checkWebhook"
        command "clearStatus"
    }

    preferences {
        input name: "webhookUrl",
              type: "text",
              title: "Google Apps Script Web App URL",
              description: "Paste the Apps Script deployment URL ending in /exec",
              required: true

        input name: "token",
              type: "password",
              title: "Shared Secret Token",
              description: "Paste the same token used in Apps Script",
              required: true

        input name: "recipientGroup",
              type: "text",
              title: "Recipient Group",
              description: "Example: gordon, family, critical",
              defaultValue: "gordon",
              required: true

        input name: "subjectPrefix",
              type: "text",
              title: "Email Subject",
              defaultValue: "Hubitat Alert",
              required: true

        input name: "fromName",
              type: "text",
              title: "Sender Display Name",
              defaultValue: "Hubitat",
              required: true

        input name: "timeoutSeconds",
              type: "number",
              title: "HTTP Timeout Seconds",
              defaultValue: 30,
              range: "5..60",
              required: true

        input name: "logEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: true
    }
}

void installed() {
    initialiseState()
}

void updated() {
    initialiseState()

    if (logEnable) {
        log.debug "${device.displayName}: preferences updated"
        runIn(1800, "logsOff")
    }
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging disabled"
}

void refresh() {
    initialiseState()
}

void clearStatus() {
    sendEvent(name: "lastStatus", value: "ready")
    sendEvent(name: "lastSentAt", value: "")
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: "")
    sendEvent(name: "lastHttpStatus", value: null)
    sendEvent(name: "lastWebhookCheck", value: "")
}

void sendTest() {
    String ts = nowLocal()
    deviceNotification("Hubitat Gmail notification test from ${device.displayName} at ${ts}")
}

void checkWebhook() {
    String url = normaliseUrl(webhookUrl)

    if (!url) {
        markFailed("Missing Google Apps Script Web App URL")
        return
    }

    if (!isValidExecUrl(url)) {
        markFailed("Webhook URL must start with https://script.google.com/macros/s/ and end with /exec")
        return
    }

    Map params = [
        uri            : url,
        timeout        : safeTimeout(),
        followRedirects: true,
        headers        : [
            "Accept": "application/json"
        ]
    ]

    sendEvent(name: "lastWebhookCheck", value: "checking")
    sendEvent(name: "lastStatus", value: "checking webhook")
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: "")
    sendEvent(name: "lastHttpStatus", value: null)

    if (logEnable) {
        log.debug "${device.displayName}: checking webhook GET: ${redactUrl(url)}"
    }

    try {
        httpGet(params) { resp ->
            Integer status = safeStatus(resp)
            String body = safeBody(resp)

            sendEvent(name: "lastHttpStatus", value: status)
            sendEvent(name: "lastResponse", value: body.take(255))

            if (logEnable) {
                log.debug "${device.displayName}: GET status=${status}, body=${body.take(500)}"
            }

            if (status == 401 || status == 403) {
                markFailed("Webhook GET rejected HTTP ${status}. Apps Script access must be set to Anyone.")
                return
            }

            if (status < 200 || status >= 400) {
                markFailed("Webhook GET failed HTTP ${status}: ${body.take(180)}")
                return
            }

            Map parsed = parseJsonOrNull(body)

            if (parsed?.ok == true) {
                sendEvent(name: "lastWebhookCheck", value: "ok HTTP ${status}")
                sendEvent(name: "lastStatus", value: "webhook reachable")
                sendEvent(name: "lastError", value: "")
                sendEvent(name: "lastResponse", value: JsonOutput.toJson(parsed).take(255))
                return
            }

            if (looksLikeGoogleHtml(body)) {
                markFailed("Webhook GET returned Google HTML, not JSON. Redeploy Apps Script as Web app: Execute as Me, access Anyone.")
                return
            }

            markFailed("Webhook GET did not return expected JSON ok:true")
        }
    } catch (Exception e) {
        String msg = safeException(e)

        if (is302Redirect(msg)) {
            markWebhookReachableViaRedirect()
            return
        }

        markFailed("Webhook GET exception: ${msg}")
    }
}

void deviceNotification(String text) {
    sendEmailNotification(text)
}

private void sendEmailNotification(String text) {
    String url = normaliseUrl(webhookUrl)
    String cleanToken = token?.toString()?.trim()
    String groupName = recipientGroup?.toString()?.trim()?.toLowerCase()
    String cleanText = text?.toString()?.trim()

    if (!url) {
        markFailed("Missing Google Apps Script Web App URL")
        return
    }

    if (!isValidExecUrl(url)) {
        markFailed("Webhook URL must be original script.google.com /exec URL")
        return
    }

    if (!cleanToken) {
        markFailed("Missing shared secret token")
        return
    }

    if (!groupName) {
        markFailed("Missing recipient group")
        return
    }

    if (!cleanText) {
        markFailed("Notification text is empty")
        return
    }

    Map payload = [
        token   : cleanToken,
        group   : groupName,
        subject : safeSubject(subjectPrefix),
        text    : cleanText,
        source  : device.displayName,
        hub     : safeHubName(),
        fromName: safeFromName(fromName)
    ]

    Map params = [
        uri               : url,
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson(payload),
        timeout           : safeTimeout(),
        followRedirects   : true,
        headers           : [
            "Accept": "application/json"
        ]
    ]

    sendEvent(name: "lastStatus", value: "sending")
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: "")
    sendEvent(name: "lastHttpStatus", value: null)

    if (logEnable) {
        log.debug "${device.displayName}: sending Gmail notification"
        log.debug "${device.displayName}: url=${redactUrl(url)}"
        log.debug "${device.displayName}: group=${groupName}"
        log.debug "${device.displayName}: subject=${safeSubject(subjectPrefix)}"
        log.debug "${device.displayName}: textLength=${cleanText.length()}"
    }

    try {
        httpPost(params) { resp ->
            Integer status = safeStatus(resp)
            String body = safeBody(resp)

            sendEvent(name: "lastHttpStatus", value: status)
            sendEvent(name: "lastResponse", value: body.take(255))

            if (logEnable) {
                log.debug "${device.displayName}: POST status=${status}, body=${body.take(500)}"
            }

            handlePostResponse(status, body)
        }
    } catch (Exception e) {
        String msg = safeException(e)

        if (is302Redirect(msg)) {
            markSentViaRedirect()
            return
        }

        if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")) {
            markFailed("POST rejected HTTP 401 Unauthorized. Fix Apps Script deployment access: Execute as Me, Who has access Anyone.")
            return
        }

        if (msg.contains("403") || msg.toLowerCase().contains("forbidden")) {
            markFailed("POST rejected HTTP 403 Forbidden. Fix Apps Script deployment access: Execute as Me, Who has access Anyone.")
            return
        }

        markFailed("POST exception: ${msg}")
    }
}

private void handlePostResponse(Integer status, String body) {
    String cleanBody = body ?: ""

    if (status == null) {
        markFailed("No HTTP status returned by gateway")
        return
    }

    if (status == 302) {
        markSentViaRedirect()
        return
    }

    if (status == 401) {
        markFailed("POST rejected HTTP 401 Unauthorized. Apps Script access must be Anyone, not Google-account-only.")
        return
    }

    if (status == 403) {
        markFailed("POST rejected HTTP 403 Forbidden. Apps Script access must be Anyone and Execute as Me.")
        return
    }

    if (status < 200 || status >= 400) {
        markFailed("HTTP ${status}: ${cleanBody.take(180)}")
        return
    }

    if (!cleanBody.trim()) {
        markSentTransportOnly(status)
        return
    }

    if (looksLikeGoogleHtml(cleanBody)) {
        markSentTransportOnly(status, "POST returned Google redirect/HTML after send")
        return
    }

    Map parsed = parseJsonOrNull(cleanBody)

    if (parsed == null) {
        markSentTransportOnly(status, "POST returned non-JSON after send")
        return
    }

    if (parsed?.ok == true) {
        markSent(status, JsonOutput.toJson(parsed))
        return
    }

    String scriptError = parsed?.error ?: "Apps Script returned ok:false"
    markFailed("Apps Script error: ${scriptError}".take(255))
}

private void markSent(Integer status, String body) {
    String ts = nowLocal()

    sendEvent(name: "lastStatus", value: "sent")
    sendEvent(name: "lastSentAt", value: ts)
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: body?.take(255) ?: "HTTP ${status}, sent")
    sendEvent(name: "lastHttpStatus", value: status)

    log.info "${device.displayName}: Gmail notification sent at ${ts}"
}

private void markSentViaRedirect() {
    String ts = nowLocal()

    sendEvent(name: "lastStatus", value: "sent")
    sendEvent(name: "lastSentAt", value: ts)
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: "Google Apps Script returned HTTP 302 redirect after POST. Email send treated as successful.")
    sendEvent(name: "lastHttpStatus", value: 302)

    log.info "${device.displayName}: Gmail notification treated as sent after Google Apps Script HTTP 302 redirect at ${ts}"
}

private void markSentTransportOnly(Integer status, String reason = "") {
    String ts = nowLocal()
    String response = reason ?: "HTTP ${status}, transport completed after POST"

    sendEvent(name: "lastStatus", value: "sent")
    sendEvent(name: "lastSentAt", value: ts)
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: response.take(255))
    sendEvent(name: "lastHttpStatus", value: status)

    log.info "${device.displayName}: Gmail notification treated as sent at ${ts}. ${response}"
}

private void markWebhookReachableViaRedirect() {
    sendEvent(name: "lastWebhookCheck", value: "ok HTTP 302 redirect")
    sendEvent(name: "lastStatus", value: "webhook reachable")
    sendEvent(name: "lastError", value: "")
    sendEvent(name: "lastResponse", value: "Google Apps Script returned HTTP 302 redirect during webhook check.")
    sendEvent(name: "lastHttpStatus", value: 302)

    log.info "${device.displayName}: webhook reachable via Google Apps Script HTTP 302 redirect"
}

private void markFailed(String message) {
    String msg = message?.toString()?.take(255) ?: "Unknown error"

    sendEvent(name: "lastStatus", value: "failed")
    sendEvent(name: "lastError", value: msg)

    log.warn "${device.displayName}: ${msg}"
}

private void initialiseState() {
    if (!device.currentValue("lastStatus")) {
        sendEvent(name: "lastStatus", value: "ready")
    }

    if (!device.currentValue("lastError")) {
        sendEvent(name: "lastError", value: "")
    }
}

private Boolean isValidExecUrl(String url) {
    if (!url) {
        return false
    }

    return url.startsWith("https://script.google.com/macros/s/") && url.endsWith("/exec")
}

private Boolean is302Redirect(String msg) {
    if (!msg) {
        return false
    }

    String m = msg.toLowerCase()

    return m.contains("302") ||
           m.contains("moved temporarily") ||
           m.contains("moved permanently") ||
           m.contains("redirect")
}

private String normaliseUrl(Object value) {
    return value?.toString()?.trim()
}

private Integer safeTimeout() {
    try {
        Integer t = (timeoutSeconds ?: 30) as Integer
        if (t < 5) return 5
        if (t > 60) return 60
        return t
    } catch (Exception ignored) {
        return 30
    }
}

private String safeSubject(Object value) {
    String s = value?.toString()?.trim()

    if (!s) {
        s = "Hubitat Alert"
    }

    s = s.replaceAll("[\\r\\n]", " ")
    return s.take(120)
}

private String safeFromName(Object value) {
    String s = value?.toString()?.trim()

    if (!s) {
        s = "Hubitat"
    }

    s = s.replaceAll("[\\r\\n]", " ")
    return s.take(80)
}

private String safeHubName() {
    try {
        return location?.hub?.name?.toString() ?: "Hubitat"
    } catch (Exception ignored) {
        return "Hubitat"
    }
}

private String nowLocal() {
    TimeZone tz = null

    try {
        tz = location?.timeZone
    } catch (Exception ignored) {
        tz = null
    }

    if (!tz) {
        tz = TimeZone.getTimeZone("Australia/Perth")
    }

    return new Date().format("yyyy-MM-dd HH:mm:ss", tz)
}

private Integer safeStatus(Object resp) {
    try {
        if (resp?.hasProperty("status")) {
            return resp.status as Integer
        }
    } catch (Exception ignored) {
    }

    try {
        return resp?.getStatus() as Integer
    } catch (Exception ignored) {
    }

    return null
}

private String safeBody(Object resp) {
    try {
        Object data = resp?.data

        if (data == null) {
            return ""
        }

        if (data instanceof Map || data instanceof List) {
            return JsonOutput.toJson(data)
        }

        return data.toString()
    } catch (Exception ignored) {
    }

    try {
        String data = resp?.getData()
        return data ?: ""
    } catch (Exception ignored) {
    }

    return ""
}

private Map parseJsonOrNull(String value) {
    try {
        if (!value?.trim()) {
            return null
        }

        Object parsed = new JsonSlurper().parseText(value)

        if (parsed instanceof Map) {
            return parsed as Map
        }

        return null
    } catch (Exception ignored) {
        return null
    }
}

private Boolean looksLikeGoogleHtml(String body) {
    if (!body) {
        return false
    }

    String b = body.toLowerCase()

    return b.contains("<html") ||
           b.contains("<!doctype") ||
           b.contains("@-webkit-keyframes") ||
           b.contains("mdc-ripple") ||
           (b.contains("google") && b.contains("sign in"))
}

private String safeException(Exception e) {
    String msg = e?.message ?: e?.toString() ?: "Unknown exception"
    return msg.take(255)
}

private String redactUrl(String url) {
    if (!url) {
        return ""
    }

    try {
        if (url.length() <= 48) {
            return url
        }

        return url.take(42) + "..." + url.takeRight(12)
    } catch (Exception ignored) {
        return "redacted-url"
    }
}