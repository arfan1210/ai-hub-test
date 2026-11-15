package com.arfan.aihub

/**
 * Per-site DOM adapters.
 *
 * Every chat site in the app is a React-style SPA with hashed CSS class names,
 * so nothing here may rely on a stable class or id. Instead each adapter uses
 * heuristic selectors (semantic attributes first, structure last) and reports
 * `CALIBRATION NEEDED` when the page does not look like it expects.
 *
 * JavaScript contract returned by [captureJs] / [sendJs]:
 *
 * ```
 * { ok: true|false, reason: "...", busy: true|false,
 *   text: "...", count: n, role: "user"|"assistant" }
 * ```
 *
 * `evaluateJavascript` replies with a JSON-encoded string, which
 * [SiteAdapters.decode] unwraps into a [JsResult].
 */
object SiteAdapters {

    /** Outcome of one injected script. */
    data class JsResult(
        val ok: Boolean,
        val reason: String,
        val busy: Boolean,
        val text: String,
        val count: Int
    ) {
        companion object {
            val FAILED = JsResult(false, "no response", busy = false, text = "", count = 0)
        }
    }

    /** Which message was requested from the page. */
    enum class Target {
        /** Qwen: the newest USER message (the frozen task). */
        NEWEST_USER,

        /** Qwen: the newest ASSISTANT reply (the frozen answer). */
        NEWEST_ASSISTANT,

        /** Reviewer: the newest ASSISTANT reply (the verdict). */
        REVIEWER_REPLY
    }

    // ------------------------------------------------------------------
    // Script assembly
    // ------------------------------------------------------------------

    /**
     * Builds the capture script for [target] on [serviceId].
     *
     * The scripts only ever read *assistant* nodes for verdicts. The user's own
     * prompt is deliberately out of scope, which is what stops the loop from
     * reading a `VERDICT:` mention inside the instruction we just typed as if it
     * were the reviewer's answer.
     */
    fun captureJs(serviceId: String, target: Target): String {
        val flavour = name(serviceId)
        val targetJs = when (target) {
            Target.NEWEST_USER -> "'user'"
            Target.NEWEST_ASSISTANT -> "'assistant'"
            Target.REVIEWER_REPLY -> "'assistant'"
        }
        return "var AIHUB_$flavour = (function(){" + core(serviceId) + "return api;})();\n" +
            "AIHUB_$flavour.capture($targetJs);"
    }

    /** Builds the type-and-send script for [serviceId] with [text]. */
    fun sendJs(serviceId: String, text: String): String {
        val flavour = name(serviceId)
        val payload = JsonUtil.jsonString(text)
        return "var AIHUB_$flavour = (function(){" + core(serviceId) + "return api;})();\n" +
            "AIHUB_$flavour.send($payload);"
    }

    /** Readiness probe: is a composer present and the page interactive? */
    fun probeJs(serviceId: String): String {
        val flavour = name(serviceId)
        return "var AIHUB_$flavour = (function(){" + core(serviceId) + "return api;})();\n" +
            "AIHUB_$flavour.probe();"
    }

    /** Quick "is the site generating a reply right now" probe. */
    fun busyJs(serviceId: String): String {
        val flavour = name(serviceId)
        return "var AIHUB_$flavour = (function(){" + core(serviceId) + "return api;})();\n" +
            "AIHUB_$flavour.busy();"
    }

    private fun name(serviceId: String): String =
        serviceId.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /**
     * [CORE] with the flavour placeholder filled in. The placeholder is a plain
     * token (not a comment, not a quote) so nothing here can close the raw
     * string it is embedded in.
     */
    private fun core(serviceId: String): String =
        CORE.replace(FLAVOUR_PLACEHOLDER, name(serviceId))

    // ------------------------------------------------------------------
    // Result decoding
    // ------------------------------------------------------------------

    /**
     * Decodes the string handed to `ValueCallback<String>`. WebView wraps the
     * result in JSON (so it arrives quoted and escaped), and `null` is reported
     * as the literal `null`.
     */
    fun decode(raw: String?): JsResult {
        // WebView hands back a JSON-encoded string (often still quoted and
        // escaped), so the value is unwrapped before any field is read.
        val cleaned = JsonUtil.unwrap(raw) ?: return JsResult.FAILED
        val ok = JsonUtil.bool(cleaned, "ok")
        val count = JsonUtil.int(cleaned, "count")
        val text = JsonUtil.str(cleaned, "text")
        val reason = JsonUtil.str(cleaned, "reason")
        return JsResult(
            ok = ok,
            reason = reason,
            busy = JsonUtil.bool(cleaned, "busy"),
            text = text,
            count = count
        )
    }

    // ------------------------------------------------------------------
    // Shared page-side implementation
    // ------------------------------------------------------------------

    /**
     * The JavaScript "core". [FLAVOUR_PLACEHOLDER] is replaced with the service
     * id so anything the page logs is attributable to a service.
     *
     * Everything is defensive: any exception returns `{ok:false}` instead of
     * breaking the page.
     */
    /** Marker replaced by [core]; deliberately quote- and comment-free. */
    private const val FLAVOUR_PLACEHOLDER = "@@FLAVOUR@@"

    private val CORE = """
        // CFG_FLAVOUR_MARKER is substituted with the service id by the app.
        var FLAVOUR = "@@FLAVOUR@@";
        function q(sel, root) { try { return (root || document).querySelector(sel); } catch (e) { return null; } }
        function qa(sel, root) { try { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); } catch (e) { return []; } }
        function visible(el) {
          if (!el) return false;
          try {
            var r = el.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) return false;
            var st = window.getComputedStyle(el);
            return st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
          } catch (e) { return false; }
        }
        function textOf(el) {
          if (!el) return '';
          var t = el.innerText || el.textContent || '';
          return String(t).replace(/\u00a0/g, ' ').trim();
        }
        // ---- assistant-only message discovery -------------------------
        // 1. Semantic hooks the sites expose on markdown replies.
        var ASSISTANT_SELECTORS = [
          '[data-message-author-role="assistant"]',
          '[data-author="assistant"]',
          '[data-role="assistant"]',
          '[data-testid="assistant-message"]',
          '[class*="assistant-message"]',
          '[class*="message-assistant"]'
        ];
        var USER_SELECTORS = [
          '[data-message-author-role="user"]',
          '[data-author="user"]',
          '[data-role="user"]',
          '[data-testid="user-message"]',
          '[class*="user-message"]',
          '[class*="message-user"]'
        ];
        // 2. Fallback: nodes that own a markdown body and are not inputs.
        var MARKDOWN_SELECTORS = [
          '.markdown', '.markdown-body', '.prose', '[class*="markdown"]',
          '[class*="message-content"]'
        ];
        function bySelector(selectors) {
          var out = [];
          for (var i = 0; i < selectors.length; i++) {
            var found = qa(selectors[i]);
            for (var j = 0; j < found.length; j++) {
              var el = found[j];
              if (!visible(el)) continue;
              if (el.closest && el.closest('textarea, input, form, [contenteditable="true"]')) continue;
              if (out.indexOf(el) < 0) out.push(el);
            }
            if (out.length) break; // first selector family wins
          }
          return out;
        }
        function markdownNodes() {
          var nodes = bySelector(MARKDOWN_SELECTORS);
          var out = [];
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            // Keep the outermost markdown container per message so we do not
            // return both a wrapper and its inner blocks.
            var parentMd = el.parentElement && el.parentElement.closest ? el.parentElement.closest('.markdown, [class*="markdown"], .prose') : null;
            if (parentMd && parentMd !== el) continue;
            out.push(el);
          }
          return out;
        }
        function assistantNodes() {
          var semantic = bySelector(ASSISTANT_SELECTORS);
          if (semantic.length) return semantic;
          var md = markdownNodes();
          var userTexts = bySelector(USER_SELECTORS).map(textOf);
          return md.filter(function (el) {
            var t = textOf(el);
            return t && userTexts.indexOf(t) < 0;
          });
        }
        function userNodes() {
          var semantic = bySelector(USER_SELECTORS);
          if (semantic.length) return semantic;
          // Without semantic hooks we cannot reliably tell a user bubble from a
          // reply, so report nothing rather than guess. Callers treat this as
          // calibration needed.
          return [];
        }
        // ---- composer -------------------------------------------------
        function composer() {
          var candidates = qa('textarea, [contenteditable="true"], [role="textbox"]');
          var best = null;
          for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            if (!visible(el)) continue;
            if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') continue;
            if (el.closest && el.closest('[aria-hidden="true"]')) continue;
            var r = el.getBoundingClientRect();
            // Prefer the widest visible editor near the bottom of the viewport.
            var score = r.width * 0.5 + r.top;
            if (!best || score > best.score) best = { el: el, score: score };
          }
          return best ? best.el : null;
        }
        function setNativeValue(el, value) {
          // React tracks the previous value on the DOM node; assigning through
          // the prototype setter then dispatching an input event is the only
          // reliable way to make React accept typed text.
          var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
                    : el.tagName === 'INPUT' ? window.HTMLInputElement.prototype : null;
          if (proto) {
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) { desc.set.call(el, value); }
            else { el.value = value; }
          } else {
            el.value = value;
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        }
        function fillComposer(el, value) {
          if (!el) return false;
          el.focus();
          var isEditable = el.getAttribute && el.getAttribute('contenteditable') === 'true';
          if (isEditable) {
            el.innerHTML = '';
            var lines = String(value).split('\n');
            for (var i = 0; i < lines.length; i++) {
              if (i > 0) el.appendChild(document.createElement('br'));
              el.appendChild(document.createTextNode(lines[i]));
            }
            el.dispatchEvent(new Event('input', { bubbles: true }));
            return true;
          }
          setNativeValue(el, value);
          return true;
        }
        function clickSend() {
          var selectors = [
            'button[data-testid="send-button"]',
            'button[aria-label*="Send" i]',
            'button[aria-label*="send" i]',
            '[data-testid*="send" i]',
            'button[type="submit"]'
          ];
          for (var i = 0; i < selectors.length; i++) {
            var buttons = qa(selectors[i]);
            for (var j = 0; j < buttons.length; j++) {
              var b = buttons[j];
              if (!visible(b) || b.disabled) continue;
              if (b.closest && b.closest('form[data-login], form[action*="login"]')) continue;
              b.click();
              return true;
            }
          }
          return false;
        }
        function pressEnter(el) {
          var opts = { bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', keyCode: 13, which: 13 };
          el.dispatchEvent(new KeyboardEvent('keydown', opts));
          el.dispatchEvent(new KeyboardEvent('keypress', opts));
          el.dispatchEvent(new KeyboardEvent('keyup', opts));
        }
        function composerText(el) {
          // innerText is unavailable in some engines; textContent always works
          // for contenteditable editors, and .value for textarea/input.
          if (!el) return '';
          var v = el.value;
          if (typeof v === 'string' && v.length) return v.trim();
          var t = el.innerText;
          if (typeof t === 'string' && t.length) return t.trim();
          return (el.textContent || '').trim();
        }
        function isBusy() {
          var selectors = [
            '[data-testid*="stop" i]', 'button[aria-label*="Stop" i]',
            '[class*="stop-button"]', '[class*="streaming"]', '[data-testid*="loading" i]'
          ];
          for (var i = 0; i < selectors.length; i++) {
            var found = qa(selectors[i]);
            for (var j = 0; j < found.length; j++) {
              if (visible(found[j])) return true;
            }
          }
          return false;
        }
        var api = {
          capture: function (which) {
            try {
              var nodes = which === 'user' ? userNodes() : assistantNodes();
              if (!nodes.length) {
                // Distinguish "page not a chat yet" from "logged out".
                var composerHere = !!composer();
                return {
                  ok: false, busy: isBusy(), count: 0, text: '', kind: which,
                  reason: composerHere ? 'no message nodes' : 'login or page unavailable'
                };
              }
              var text = textOf(nodes[nodes.length - 1]);
              if (!text) return { ok: false, busy: isBusy(), count: nodes.length, text: '', reason: 'empty message node' };
              return { ok: true, busy: isBusy(), count: nodes.length, text: text, kind: which, reason: '' };
            } catch (e) {
              return { ok: false, busy: false, count: 0, text: '', reason: 'capture error: ' + e.message };
            }
          },
          send: function (text) {
            try {
              var el = composer();
              if (!el) return { ok: false, busy: isBusy(), count: 0, text: '', reason: 'no composer' };
              if (isBusy()) return { ok: false, busy: true, count: 0, text: '', reason: 'busy' };
              var before = composerText(el);
              fillComposer(el, text);
              var filled = composerText(el);
              if (!filled) return { ok: false, busy: false, count: 0, text: '', reason: 'typing failed' };
              if (before === filled) return { ok: false, busy: false, count: 0, text: '', reason: 'composer unchanged' };
              var clicked = clickSend();
              if (!clicked) pressEnter(el);
              return { ok: true, busy: false, count: 1, text: '', reason: clicked ? 'sent' : 'enter key' };
            } catch (e) {
              return { ok: false, busy: false, count: 0, text: '', reason: 'send error: ' + e.message };
            }
          },
          busy: function () {
            try { return { ok: true, busy: isBusy(), count: 0, text: '', reason: '' }; }
            catch (e) { return { ok: false, busy: false, count: 0, text: '', reason: 'busy probe failed' }; }
          },
          probe: function () {
            return {
              ok: !!composer(), busy: isBusy(),
              count: assistantNodes().length, text: '',
              reason: composer() ? '' : 'no composer'
            };
          }
        };
    """.trimIndent()
}
