package com.example.llamadroid.ui.ai

import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.max

private const val KEYBOARD_VIEWPORT_FIX_SCRIPT = """
    (function () {
      if (window.__aidtViewportFixInstalled) return;
      window.__aidtViewportFixInstalled = true;

      const apply = () => {
        const vv = window.visualViewport;
        const keyboardInset = vv ? Math.max(0, window.innerHeight - vv.height - vv.offsetTop) : 0;
        document.documentElement.style.setProperty('--aidt-keyboard-inset', keyboardInset + 'px');

        if (document.body) {
          document.body.style.paddingBottom = `calc(env(safe-area-inset-bottom, 0px) + ${'$'}{keyboardInset}px)`;
        }

        const active = document.activeElement;
        if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
          window.setTimeout(() => {
            try {
              active.scrollIntoView({ block: 'center', behavior: 'smooth' });
            } catch (error) {
              active.scrollIntoView(true);
            }
          }, 60);
        }
      };

      document.addEventListener('focusin', apply, true);
      window.addEventListener('resize', apply);
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', apply);
        window.visualViewport.addEventListener('scroll', apply);
      }
      apply();
    })();
"""

fun WebView.applyKeyboardAwareInsetsFix() {
    clipToPadding = false
    isFocusable = true
    isFocusableInTouchMode = true

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.updatePadding(bottom = max(imeBottom, navBottom))
        insets
    }
}

fun WebView.injectKeyboardViewportFix() {
    evaluateJavascript(KEYBOARD_VIEWPORT_FIX_SCRIPT, null)
}
