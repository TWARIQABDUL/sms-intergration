// Loose E.164 check: optional leading +, 8-15 digits total, first digit 1-9.
// This intentionally rejects spaces, dashes, letters, and obviously invalid
// strings without trying to fully validate per-country numbering plans.
const E164_RE = /^\+?[1-9]\d{7,14}$/;

function isValidE164(value) {
  return typeof value === 'string' && E164_RE.test(value.trim());
}

function normalizePhone(value) {
  const trimmed = value.trim();
  return trimmed.startsWith('+') ? trimmed : `+${trimmed}`;
}

function isNonEmptyString(value, maxLen = 2000) {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLen;
}

module.exports = { isValidE164, normalizePhone, isNonEmptyString };
