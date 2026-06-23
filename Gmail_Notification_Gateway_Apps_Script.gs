const SECRET_TOKEN = 'REPLACE_WITH_A_LONG_RANDOM_TOKEN';

// Keep real email addresses here, not in Hubitat.
// Hubitat sends a recipient group name. Apps Script resolves that group to actual recipients.
const RECIPIENT_GROUPS = {
  user: [
    'your.email@gmail.com'
  ],
  family: [
    'your.email@gmail.com'
    // 'another.person@example.com'
  ],
  critical: [
    'your.email@gmail.com'
    // 'another.person@example.com'
  ]
};

const DEFAULT_RECIPIENT_GROUP = 'user'; // Must match a key in RECIPIENT_GROUPS.
const DEFAULT_FROM_NAME = 'Hubitat';
const MAX_BODY_LENGTH = 5000;
const MAX_SUBJECT_LENGTH = 120;

function doGet(e) {
  return json_({
    ok: true,
    service: 'Hubitat Gmail Notification Gateway',
    method: 'GET',
    remainingQuota: MailApp.getRemainingDailyQuota(),
    time: new Date().toISOString()
  });
}

function doPost(e) {
  try {
    const payload = parseJson_(e);
    validateToken_(payload.token);

    const groupName = String(payload.group || DEFAULT_RECIPIENT_GROUP).trim().toLowerCase();
    const recipients = RECIPIENT_GROUPS[groupName];

    if (!recipients || recipients.length === 0) {
      throw new Error(`Unknown or empty recipient group: ${groupName}`);
    }

    const remainingQuota = MailApp.getRemainingDailyQuota();

    if (remainingQuota < recipients.length) {
      throw new Error(`Insufficient MailApp quota. Remaining: ${remainingQuota}, required: ${recipients.length}`);
    }

    const subject = sanitiseSubject_(payload.subject || 'Hubitat Alert');
    const message = sanitiseBody_(payload.text || payload.message || '');

    if (!message) {
      throw new Error('Empty notification body');
    }

    const source = payload.source ? `\n\nSource: ${payload.source}` : '';
    const hub = payload.hub ? `\nHub: ${payload.hub}` : '';
    const timestamp = Utilities.formatDate(new Date(), 'Australia/Perth', 'yyyy-MM-dd HH:mm:ss z');

    const body =
      message +
      source +
      hub +
      `\n\n--\nSent by Hubitat Gmail Notification Gateway\n${timestamp}`;

    MailApp.sendEmail({
      to: recipients.join(','),
      subject: subject,
      body: body,
      name: payload.fromName || DEFAULT_FROM_NAME
    });

    return json_({
      ok: true,
      group: groupName,
      recipientCount: recipients.length,
      quotaBeforeSend: remainingQuota,
      quotaAfterSendApprox: remainingQuota - recipients.length
    });

  } catch (err) {
    return json_({
      ok: false,
      error: String(err && err.message ? err.message : err)
    });
  }
}

function testSend() {
  MailApp.sendEmail({
    to: 'your.email@gmail.com',
    subject: 'Hubitat Gmail Gateway Test',
    body: 'Manual Apps Script test successful.',
    name: 'Hubitat'
  });
}

function parseJson_(e) {
  if (!e || !e.postData || !e.postData.contents) {
    throw new Error('Missing POST body');
  }

  return JSON.parse(e.postData.contents);
}

function validateToken_(token) {
  if (!token || token !== SECRET_TOKEN) {
    throw new Error('Unauthorised');
  }
}

function sanitiseSubject_(value) {
  return String(value)
    .replace(/[\r\n]/g, ' ')
    .trim()
    .slice(0, MAX_SUBJECT_LENGTH);
}

function sanitiseBody_(value) {
  return String(value)
    .replace(/\u0000/g, '')
    .trim()
    .slice(0, MAX_BODY_LENGTH);
}

function json_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
