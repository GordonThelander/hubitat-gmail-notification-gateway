# Gmail Notification Gateway for Hubitat

Hubitat notification driver for sending Gmail alerts via a Google Apps Script HTTP webhook.

## Overview

The Gmail Notification Gateway lets Hubitat send email notifications through a normal Hubitat `Notification` device without using paid messaging services such as SendGrid, Mailgun, SMTP2Go, Pushover or Twilio.

Important Note

Google imposes daily sending limits on Gmail accounts. When using this gateway, a standard Gmail account is typically limited to approximately 100 recipients per day. The limit applies to recipients, not messages. For example, sending one email to ten recipients counts as ten recipients against the daily quota.

For most Hubitat use cases, 100 recipients per day is unlikely to be an issue unless the automation is particularly chatty or sending to multiple recipients for every event. That said, if someone is concerned about consuming quota on their personal Gmail account, there is nothing preventing them from creating a dedicated Gmail account specifically for home automation alerts. In fact, that can be a good practice as it separates automation traffic from personal email and makes it easier to manage notification volume independently.


The design is deliberately simple:

| Component | Role |
|---|---|
| Hubitat virtual notification device | Receives normal Hubitat notification messages from Rule Machine or apps. |
| Gmail Notification Gateway Groovy driver | Sends the notification text to a Google Apps Script webhook using HTTP POST. |
| Google Apps Script | Validates the shared token, resolves the recipient group and sends the email. |
| Gmail / Google account | Sends the final email using `MailApp.sendEmail()`. |

Hubitat does not send SMTP directly. Hubitat only performs an HTTPS POST to Google Apps Script. Google handles the email send.

## Files

| File | Purpose |
|---|---|
| `Gmail_Notification_Gateway.groovy` | Hubitat driver. Install this under **Drivers Code**. |
| `Gmail_Notification_Gateway_Apps_Script.gs` | Google Apps Script webhook code. Paste this into Apps Script. |
| `README.md` | Setup and operating instructions. |

## Prerequisites

| Requirement | Detail |
|---|---|
| Hubitat hub | Must have internet access to call Google Apps Script. |
| Google account | Gmail or Google Workspace account. |
| Google Apps Script project | Used as the HTTPS webhook and email sender. |
| Shared secret token | Same token must be configured in Apps Script and Hubitat. |
| Recipient groups | Defined in Apps Script, for example `gordon`, `Family`, `Critical`. |

## Architecture

```text
Hubitat Rule Machine / App
        |
        v
Hubitat virtual Notification device
        |
        v
Gmail Notification Gateway Groovy driver
        |
        v
HTTPS POST to Google Apps Script /exec URL
        |
        v
MailApp.sendEmail()
        |
        v
Nominated recipients
```

## Security model

The Google Apps Script web app must be accessible to Hubitat without an interactive Google login. That means the web app is deployed with **Who has access: Anyone**.

Security is handled by:

| Control | Purpose |
|---|---|
| Shared secret token | Rejects unauthorised POSTs. |
| Recipient groups | Prevents Hubitat from sending to arbitrary email addresses. |
| Apps Script recipient whitelist | Real email addresses are stored in Apps Script, not Hubitat. |
| URL secrecy | The `/exec` URL should not be published publicly. |
| Token rotation | Regenerate the token as required. |

This design avoids creating an open mail relay. Hubitat sends a group name, not raw recipient addresses.

## 1. Create the Google Apps Script project

| Step | Action |
|---:|---|
| 1 | Go to `script.google.com`. |
| 2 | Click **New project**. |
| 3 | Rename the project to **Hubitat Gmail Notification Gateway**. |
| 4 | Delete any default code in the editor. |
| 5 | Paste the contents of `Gmail_Notification_Gateway_Apps_Script.gs`. |
| 6 | Replace `SECRET_TOKEN` with a long random token. |
| 7 | Replace the example email addresses in `RECIPIENT_GROUPS`. |
| 8 | Save the project. |

## 2. Configure the Apps Script token and recipient groups

In `Gmail_Notification_Gateway_Apps_Script.gs`, update these values:

| Field | What to configure |
|---|---|
| `SECRET_TOKEN` | A long random token. Use the same value in the Hubitat driver. |
| `RECIPIENT_GROUPS.gordon` | Your personal email recipients. |
| `RECIPIENT_GROUPS.family` | Household or family recipients. |
| `RECIPIENT_GROUPS.critical` | High-priority recipients. |
| `testSend()` recipient | Your own email address for the manual Apps Script test. |

Example:

```javascript
const SECRET_TOKEN = 'REPLACE_WITH_A_LONG_RANDOM_TOKEN';

const RECIPIENT_GROUPS = {
  gordon: [
    'your.email@gmail.com'
  ],
  family: [
    'your.email@gmail.com',
    'someone.else@example.com'
  ],
  critical: [
    'your.email@gmail.com',
    'someone.else@example.com'
  ]
};
```

## 3. Authorise MailApp

Before Hubitat can send mail through Apps Script, Google must authorise the script to send email.

| Step | Action |
|---:|---|
| 1 | In Apps Script, select the `testSend` function from the function dropdown. |
| 2 | Click **Run**. |
| 3 | Google will request authorisation. |
| 4 | Approve the requested permissions. |
| 5 | Confirm that the manual test email arrives. |

If `testSend()` does not send an email, Hubitat will not work yet. Fix the Apps Script authorisation first.

## 4. Deploy the script as a web app

| Step | Action |
|---:|---|
| 1 | Click **Deploy**. |
| 2 | Select **New deployment**. |
| 3 | Choose deployment type **Web app**. |
| 4 | Set **Execute as** to **Me**. |
| 5 | Set **Who has access** to **Anyone**. |
| 6 | Click **Deploy**. |
| 7 | Copy the generated **Web app URL**. |

The URL must look like this:

```text
https://script.google.com/macros/s/AKfycbx................................../exec
```

Use the `/exec` URL in the Hubitat Driver on the Preferences Tab on the virtual child device.

Do not use:

| URL type | Why not |
|---|---|
| `https://script.googleusercontent.com/macros/echo?...` | This is the redirected runtime URL that Google shows after opening the web app. |
| `/dev` URL | This is the development/test URL, not the deployed endpoint. |

## 5. Test the web app URL

Open the `/exec` URL in a browser.

Expected result:

```json
{
  "ok": true,
  "service": "Hubitat Gmail Notification Gateway",
  "method": "GET",
  "remainingQuota": 100,
  "time": "..."
}
```

If you see `Script function not found: doGet`, the `doGet(e)` function is missing or the deployed version is not current.

If you see an authorisation error, check the deployment settings:

| Setting | Required value |
|---|---|
| Execute as | Me |
| Who has access | Anyone |

If you change the Apps Script code later, create a **new deployment version**. Saving the script alone may not update the deployed `/exec` endpoint.

## 6. Install the Hubitat driver

| Step | Action |
|---:|---|
| 1 | In Hubitat, go to **Drivers Code**. |
| 2 | Click **New Driver**. |
| 3 | Paste the contents of `Gmail_Notification_Gateway.groovy`. |
| 4 | Click **Save**. |
| 5 | Confirm there are no compile errors. |

The driver creates a normal Hubitat notification-capable device.

| Driver item | Purpose |
|---|---|
| `capability "Notification"` | Allows Rule Machine and apps to use it as a normal notification device. |
| `deviceNotification(text)` | Receives the notification message from Hubitat. |
| `checkWebhook` | Tests the Apps Script `/exec` endpoint. |
| `sendTest` | Sends a test email. |
| `clearStatus` | Clears visible status and error values. |
| `lastStatus` | Shows `ready`, `sending`, `sent`, `failed` or `webhook reachable`. |
| `lastError` | Shows the latest failure reason. |
| `lastHttpStatus` | Shows the latest HTTP result, including Google Apps Script redirects such as `302`. |

## 7. Create the Hubitat virtual notification device

| Step | Action |
|---:|---|
| 1 | Go to **Devices**. |
| 2 | Click **Add Device**. |
| 3 | Choose **Virtual**. |
| 4 | Name the device, for example **Gmail - Gordon**. |
| 5 | Set the device type to **Gmail Notification Gateway**. |
| 6 | Save the device. |

You can create multiple virtual devices if you want different recipient groups or subject lines.

Example setup:

| Device name | Recipient group | Subject |
|---|---|---|
| Gmail - Gordon | `gordon` | `Hubitat Alert` |
| Gmail - Family | `family` | `Home Alert` |
| Gmail - Critical | `critical` | `Critical Home Alert` |

## 8. Configure the Hubitat device preferences

Open the virtual device and go to **Preferences**.

| Preference | What to enter |
|---|---|
| Google Apps Script Web App URL | Paste the Apps Script `/exec` URL. |
| Shared Secret Token | Paste the same value used in `SECRET_TOKEN`. |
| Recipient Group | Enter a group from `RECIPIENT_GROUPS`, for example `gordon`. |
| Email Subject | Enter the subject line, for example `Hubitat Alert`. |
| Sender Display Name | Enter the display name, for example `Hubitat`. |
| HTTP Timeout Seconds | Leave at `30` unless you have a reason to change it. |
| Enable debug logging | Leave on during testing, then disable later. |

Click **Save Preferences**.

## 9. Test from Hubitat

Run the tests in this order:

| Step | Command | Expected result |
|---:|---|---|
| 1 | **Check Webhook** | `lastWebhookCheck = ok HTTP 200` or `ok HTTP 302 redirect`. |
| 2 | **Send Test** | Email arrives in Gmail. |
| 3 | **Device Notification** with text `test` | Email body contains `test`. |
| 4 | **Clear Status** | Clears the visible state fields. |

Expected successful state:

| Current state | Expected value |
|---|---|
| Last Status | `sent` |
| Last Error | blank / null |
| Last Http Status | usually `200` or `302` |
| Last Response | Success message or Google Apps Script redirect message. |

A `302 Moved Temporarily` from Google Apps Script can be normal. The driver treats it as successful because the email is often sent before Hubitat sees the redirect.

## 10. Use it in Rule Machine

Once the virtual device works, use it like any other Hubitat notification device.

| Rule Machine action | What to select |
|---|---|
| Action type | Send, speak or log a message |
| Notification device | Select the Gmail Notification Gateway virtual device |
| Message | Enter the alert text |

Example messages:

| Use case | Message |
|---|---|
| Garage door open | `Garage door has been open for 10 minutes.` |
| Water leak | `Water leak detected in the laundry.` |
| Panic switch | `Emergency switch activated at home.` |
| Hub health | `Hubitat free memory has dropped below threshold.` |

## Message limits

Google Apps Script email limits are based on **recipients**, not just messages.

At the time of writing, Google lists these Apps Script MailApp limits:

| Account type | Daily recipient limit |
|---|---:|
| Consumer Gmail account | 100 recipients/day |
| Google Workspace account | 1,500 recipients/day |

Google also lists a per-message recipient limit of **50 recipients/message**.

Quota examples:

| Scenario | Quota consumed |
|---|---:|
| 1 email to 1 recipient | 1 recipient unit |
| 1 email to 3 recipients | 3 recipient units |
| 10 alerts to a 3-person group | 30 recipient units |
| 100 alerts to 1 recipient on consumer Gmail | 100 recipient units, quota exhausted |

Practical recommendations:

| Recommendation | Reason |
|---|---|
| Keep recipient groups small | Avoid burning quota on noisy alerts. |
| Use `critical` sparingly | High-priority alerts should not be triggered by chatty devices. |
| Avoid repeated alerts every few seconds | Gmail quota can be exhausted quickly. |
| Use Rule Machine throttling/repeat controls | Prevent runaway notification loops. |
| Treat email as awareness, not life-safety | Gmail delivery is not guaranteed real-time emergency infrastructure. |

Google states that Apps Script quotas are subject to change. Check the current Google Apps Script quotas page before relying on exact limits:

```text
https://developers.google.com/apps-script/guides/services/quotas
```

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Browser says `Script function not found: doGet` | `doGet(e)` missing or not deployed | Add `doGet(e)`, save, deploy a new version. |
| Hubitat shows `401 Unauthorized` | Apps Script access is not public | Set **Who has access** to **Anyone** and redeploy. |
| Browser works, Hubitat fails | Browser is logged into Google, Hubitat is not | Use **Anyone**, not **Only myself**. |
| Email does not arrive from `testSend()` | MailApp not authorised or wrong recipient | Run `testSend()` manually and approve permissions. |
| `Check Webhook` works but email does not send | `doPost(e)` or token/group issue | Check Apps Script **Executions**. |
| Apps Script returns `Unauthorised` | Token mismatch | Make the Hubitat token and `SECRET_TOKEN` identical. |
| Apps Script returns unknown group | Recipient group mismatch | Ensure Hubitat group exactly matches `RECIPIENT_GROUPS`. |
| Hubitat shows `302 Moved Temporarily` | Google Apps Script redirect | Normal if email sends. Driver treats this as success. |
| No Apps Script execution appears | Wrong URL or Hubitat not reaching Google | Recopy the `/exec` URL and save preferences. |

## Updating later

| Change | Required action |
|---|---|
| Change Hubitat driver only | Save driver code in Hubitat. |
| Change Hubitat preferences | Save preferences on the device. |
| Change Apps Script code | Save script, then deploy a **new version**. |
| Change recipient groups | Save Apps Script and deploy a new version. |
| Change token | Update Apps Script, deploy new version, update Hubitat token, save preferences. |
| Create another recipient group | Add group in Apps Script, deploy, then create/configure another Hubitat virtual device. |

## Recommended final setup

| Item | Recommended value |
|---|---|
| One device for personal alerts | `Gmail - Gordon`, group `gordon`. |
| One device for household alerts | `Gmail - Family`, group `family`. |
| One device for high-priority alerts | `Gmail - Critical`, group `critical`. |
| Debug logging | On during setup, off once stable. |
| Rule Machine usage | Use only for meaningful alerts, not high-frequency status updates. |
